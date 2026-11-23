package com.rain.sdk.internal.core

import com.rain.sdk.error.RainError
import com.rain.sdk.interfaces.CollateralAdminCheck
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.constants.RainConstants
import com.rain.sdk.internal.network.Web3jProvider
import com.rain.sdk.internal.network.chainreader.isRpcRevert
import com.rain.sdk.internal.utils.RainAmountUtils
import com.rain.sdk.internal.utils.RainEip712Utils
import com.rain.sdk.internal.utils.RainHexUtils
import com.rain.sdk.internal.utils.RainSignatureExpiry
import com.rain.sdk.internal.utils.RainSignatureSalt
import com.rain.sdk.internal.utils.strippingHexPrefix
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.RainEIP712Message
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.utils.EthereumConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.utils.Numeric
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import java.security.SecureRandom
import org.web3j.abi.datatypes.Function as Web3jFunction

private const val WORD_HEX_LENGTH = 64
private val HEX_WORDS = Regex("^(?:[0-9a-fA-F]{$WORD_HEX_LENGTH})*$")

/**
 * Withdrawal-building primitives bound to one [RainSdk][com.rain.sdk.RainSdk]'s chain
 * configuration.
 *
 * Instance-scoped, not a singleton: two SDKs configured with different endpoints must not read
 * each other's. [web3jFactory] is injectable for tests.
 */
internal class RainTransactionBuilderImpl(
    private val rpcEndpoints: Map<Int, String>,
    private val web3jFactory: (String) -> Web3j = { url -> Web3jProvider.getOrCreate(url) }
) : RainTransactionBuilder {

    /** Resolves the configured RPC endpoint for [chainId], or fails with a precise config error. */
    private fun requireRpcUrl(chainId: Int): String =
        rpcEndpoints[chainId]
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId $chainId")

    @Suppress("ThrowsCount") // the RPC error, the undecodable value and the pass-through-or-wrap catch
    override suspend fun getLatestNonce(
        chainId: Int,
        proxyAddress: String
    ): BigInteger {
        val web3j = web3jFactory(requireRpcUrl(chainId))
        try {
            val validProxyAddress = RainHexUtils.validateAndChecksum(proxyAddress, "proxyAddress")

            val function = Web3jFunction(
                RainConstants.FUNC_ADMIN_NONCE,
                emptyList(),
                listOf(object : TypeReference<Uint256>() {})
            )

            val encodedFunction = FunctionEncoder.encode(function)

            val response = withContext(Dispatchers.IO) {
                web3j.ethCall(
                    Transaction.createEthCallTransaction(null, validProxyAddress, encodedFunction),
                    DefaultBlockParameterName.LATEST
                ).sendAsync().await()
            }

            if (response.error != null) {
                throw RainError.InternalError("RPC Error: ${response.error.message}")
            }

            // Never default to zero: a zero nonce would produce a validly signed but wrong withdrawal.
            return FunctionReturnDecoder.decode(response.value, function.outputParameters)
                .firstOrNull()?.value as? BigInteger
                ?: throw RainError.InternalError(
                    "adminNonce call to $validProxyAddress returned no decodable value"
                )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is RainError) throw e
            throw RainError.NetworkError(cause = e)
        }
    }

    override suspend fun isCollateralAdmin(
        chainId: Int,
        proxyAddress: String,
        walletAddress: String
    ): CollateralAdminCheck {
        return try {
            val validProxyAddress = RainHexUtils.validateAndChecksum(proxyAddress, "proxyAddress")
            val validWallet = RainHexUtils.validateAndChecksum(walletAddress, "walletAddress")

            val function = Web3jFunction(
                RainConstants.FUNC_IS_ADMIN,
                listOf(Address(validWallet)),
                emptyList()
            )

            val response = withContext(Dispatchers.IO) {
                web3jFactory(requireRpcUrl(chainId)).ethCall(
                    Transaction.createEthCallTransaction(null, validProxyAddress, FunctionEncoder.encode(function)),
                    DefaultBlockParameterName.LATEST
                ).sendAsync().await()
            }

            val error = response.error
            if (error != null) {
                Timber.d("Rain SDK: isAdmin unavailable on $validProxyAddress: ${error.message}")
                // A revert means the collateral exposes no `isAdmin`.
                return if (isRpcRevert(error.code, error.message, error.data)) {
                    CollateralAdminCheck.NotCollateral
                } else {
                    CollateralAdminCheck.Failed(RainError.InternalError("RPC error [${error.code}]: ${error.message}"))
                }
            }

            val word = response.value?.strippingHexPrefix()
            if (word == null || !HEX_WORDS.matches(word)) {
                throw RainError.InternalError("isAdmin call to $validProxyAddress returned no decodable value")
            }
            val answer = if (word.isEmpty()) null else EthereumConverter.parseHexToBigIntegerStrict(word.take(WORD_HEX_LENGTH))
            when (answer) {
                BigInteger.ONE -> CollateralAdminCheck.Admin
                BigInteger.ZERO -> CollateralAdminCheck.NotAdmin
                else -> CollateralAdminCheck.NotCollateral
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Rain SDK: isAdmin read failed")
            CollateralAdminCheck.Failed(e as? RainError ?: RainError.NetworkError("RPC request failed for isAdmin", e))
        }
    }

    override suspend fun buildEIP712Message(
        chainId: Int,
        walletAddress: String,
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        nonce: BigInteger?
    ): RainEIP712Message {
        val validatedAddresses = addresses.validated()
        val validWallet = RainHexUtils.validateAndChecksum(walletAddress, "walletAddress")

        // 1. Resolve Nonce
        val finalNonce = nonce ?: getLatestNonce(chainId, validatedAddresses.proxyAddress)

        // 2. Generate Salt
        val saltBytes = ByteArray(32).apply {
            SecureRandom().nextBytes(this)
        }
        val saltHex = Numeric.toHexString(saltBytes)

        // 3. Convert Amount to Base Units
        val amountBaseUnits = RainAmountUtils.toBaseUnits(amount, decimals)

        // 4. Build EIP-712 JSON
        val jsonString = RainEip712Utils.createEIP712Json(
            chainId = chainId.toLong(),
            verifyingContract = validatedAddresses.proxyAddress,
            saltHex = saltHex,
            walletAddress = validWallet,
            tokenAddress = validatedAddresses.tokenAddress,
            recipientAddress = validatedAddresses.recipientAddress,
            amount = amountBaseUnits,
            nonce = finalNonce
        )
        return RainEIP712Message(message = jsonString, salt = saltBytes)
    }

    @Suppress("ThrowsCount") // one precise rejection per malformed input, instead of an opaque on-chain revert
    override fun buildWithdrawTransactionData(
        addresses: RainWithdrawAddresses,
        amount: BigDecimal,
        decimals: Int,
        executorSignature: RainAdminSignature,
        walletSalt: ByteArray,
        walletSignature: String
    ): String {
        try {
            val validatedAddresses = addresses.validated()

            val amountBaseUnits = RainAmountUtils.toBaseUnits(amount, decimals)

            val expiryTimestamp = RainSignatureExpiry.parseEpochSeconds(executorSignature.expiresAt)

            // Reject malformed inputs here, with precise messages, instead of an opaque on-chain revert.
            val executorSalt = RainSignatureSalt.decode(executorSignature.salt)
            // The wallet's own EIP-712 domain salt from buildEIP712Message: a bytes32 word, not Rain's salt above.
            if (walletSalt.size != 32) {
                throw RainError.InvalidConfig("Wallet salt must be 32 bytes, got ${walletSalt.size}")
            }

            val function = Web3jFunction(
                RainConstants.FUNC_WITHDRAW_ASSET,
                listOf(
                    Address(validatedAddresses.proxyAddress),
                    Address(validatedAddresses.tokenAddress),
                    Uint256(amountBaseUnits),
                    Address(validatedAddresses.recipientAddress),
                    Uint256(expiryTimestamp),
                    Bytes32(executorSalt),
                    DynamicBytes(
                        RainHexUtils.hexToBytes(executorSignature.signature, expectedByteCount = 65)
                    ),
                    DynamicArray(Bytes32(walletSalt)),
                    DynamicArray(
                        DynamicBytes(RainHexUtils.hexToBytes(walletSignature, expectedByteCount = 65))
                    ),
                    Bool(true)
                ),
                emptyList()
            )

            return FunctionEncoder.encode(function)
        } catch (e: Exception) {
            if (e is RainError) throw e
            throw RainError.InternalError("Failed to build transaction data: ${e.message}", e)
        }
    }
}
