package com.rain.sdk.internal.solana

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.utils.RainSignatureExpiry
import com.rain.sdk.internal.utils.RainSignatureSalt
import com.rain.sdk.models.RainAdminSignature
import com.rain.sdk.models.UnsignedSolanaTransfer
import org.web3j.crypto.Hash
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Base64

/**
 * Composes the withdrawal transaction for Rain's Solana collateral program (single-signer
 * accounts, program v2.02).
 *
 * A withdrawal is authorized by two parties: the collateral **owner** signs the transaction
 * itself (the wallet provider does this), and Rain's **coordinator executor** signs a
 * keccak-encoded withdraw message off chain — returned by the Rain API as the admin signature.
 * The transaction therefore carries two instructions:
 *
 *  1. a native ed25519-program instruction proving the executor signed the withdraw message,
 *     which the collateral program reads back through the instructions sysvar, and
 *  2. the program's `withdraw_single_signer_collateral_asset` instruction.
 *
 * Everything the message and instruction need is read from the chain (collateral account,
 * coordinator executors, mint's token program) or derived locally (the collateral-authority PDA,
 * associated token accounts), so callers supply only the withdrawal parameters and the Rain
 * signature. A self-paid transaction is simulated before being handed to the wallet provider; a
 * fee-sponsored one skips the dry run (see [composeWithdraw]).
 */
internal class SolanaCollateralWithdrawComposer(
    private val solanaRpcClient: SolanaRpcClient,
    private val rpcUrlResolver: (Int) -> String?
) {
    /**
     * @param sponsoredFees true when the signing provider's sends are fee-sponsored. The dry run
     *   charges the fee to [ownerAddress], so for a sponsored withdrawal it would false-fail a
     *   wallet holding no SOL even though the sponsor pays the real send; a failure then
     *   surfaces through the provider's send status instead of as a typed preflight error.
     * @param sponsoredRent true when that provider's organization also funds the rent of a recipient
     *   token account this withdrawal creates, so the owner need not hold it.
     */
    suspend fun composeWithdraw(
        chainId: Int,
        ownerAddress: String,
        collateralAddress: String,
        mintAddress: String,
        recipientAddress: String,
        amountBaseUnits: BigInteger,
        adminSignature: RainAdminSignature,
        sponsoredFees: Boolean = false,
        sponsoredRent: Boolean = false
    ): UnsignedSolanaTransfer {
        val rpcUrl = rpcUrlResolver(chainId)
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")

        val ownerKey = decodeKey(ownerAddress, "owner")
        val collateralKey = decodeKey(collateralAddress, "collateral")
        val mintKey = decodeKey(mintAddress, "mint")
        val recipientKey = decodeKey(recipientAddress, "recipient")

        // The collateral account tells us everything Rain's program needs: which program owns
        // it (the program id is not part of the API contract), its coordinator, and the nonce
        // the coordinator signature was issued against.
        val collateralAccount = solanaRpcClient.getAccountRaw(rpcUrl, collateralAddress)
            ?: throw RainError.InvalidConfig("No collateral account at $collateralAddress on chainId=$chainId")
        val programKey = decodeKey(collateralAccount.ownerProgram, "collateral program")
        val collateral = SolanaCollateralAccounts.parseSingleSigner(collateralAccount.data)
            ?: throw RainError.InvalidConfig(
                "Collateral $collateralAddress is not a single-signer account; only " +
                    "single-signer Solana collateral is supported"
            )
        if (!collateral.owner.contentEquals(ownerKey)) {
            throw RainError.InvalidConfig(
                "Wallet $ownerAddress is not the owner of collateral $collateralAddress " +
                    "(owner is ${Base58.encode(collateral.owner)})"
            )
        }

        val coordinatorAccount =
            solanaRpcClient.getAccountRaw(rpcUrl, Base58.encode(collateral.coordinator))
                ?: throw RainError.InvalidConfig("Coordinator account missing for $collateralAddress")
        val executor = SolanaCollateralAccounts.parseCoordinatorExecutors(coordinatorAccount.data)
            .firstOrNull()
            ?: throw RainError.InvalidConfig("Coordinator has no executors to verify the Rain signature against")

        val salt = RainSignatureSalt.decode(adminSignature.salt)
        val signature = decodeBase64(adminSignature.signature, "signature", expectedSize = 64)
        val expiresAtEpochSeconds = RainSignatureExpiry.parseEpochSeconds(adminSignature.expiresAt)

        val mint = solanaRpcClient.getMintInfo(rpcUrl, mintAddress)
            ?: throw RainError.TokenNotFound(mintAddress, chainId)
        val tokenProgramKey = Base58.decode(mint.tokenProgram)

        // Funds are held in the ATA of the collateral-authority PDA (the API's depositAddress).
        val authority = SolanaAddresses.findProgramAddress(
            seeds = listOf(COLLATERAL_AUTHORITY_SEED, collateralKey),
            programId = programKey
        ).address
        val sourceAta = SolanaAddresses.associatedTokenAddress(authority, mintKey, tokenProgramKey)
        val destinationAta =
            SolanaAddresses.associatedTokenAddress(recipientKey, mintKey, tokenProgramKey)
        val createDestination = !solanaRpcClient.accountExists(rpcUrl, Base58.encode(destinationAta))
        // The owner pays rent for a recipient token account this withdrawal creates, and the fee,
        // each unless the provider sponsors it. Checked up front so a short wallet gets a typed
        // error before anything is signed, the same gate the transfer composer applies.
        SolanaLamportPreflight.require(
            solanaRpcClient,
            rpcUrl,
            ownerAddress,
            includeAccountRent = createDestination,
            feesSponsored = sponsoredFees,
            rentSponsored = sponsoredRent
        )

        // Reconstruct the exact message the executor signed; the program re-derives it on chain
        // and requires the ed25519 instruction to have verified precisely these bytes.
        val message = SolanaWithdrawMessages.coordinatorWithdrawMessage(
            coordinator = collateral.coordinator,
            collateral = collateralKey,
            sender = ownerKey,
            receiver = recipientKey,
            asset = mintKey,
            amountBaseUnits = amountBaseUnits,
            nonce = collateral.nonce,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
            salt = salt
        )

        val instructions = buildList {
            if (createDestination) {
                add(
                    SolanaInstructions.createAssociatedTokenAccountIdempotent(
                        tokenProgramId = tokenProgramKey,
                        payer = ownerKey,
                        associatedAccount = destinationAta,
                        owner = recipientKey,
                        mint = mintKey
                    )
                )
            }
            // The ed25519 instruction must directly precede the withdraw so the program can
            // find the verified message through the instructions sysvar.
            add(SolanaInstructions.ed25519Verify(executor, signature, message))
            add(
                SolanaInstructions.withdrawSingleSignerCollateralAsset(
                    programId = programKey,
                    owner = ownerKey,
                    coordinator = collateral.coordinator,
                    collateral = collateralKey,
                    collateralAuthority = authority,
                    destination = recipientKey,
                    asset = mintKey,
                    collateralTokenAccount = sourceAta,
                    destinationTokenAccount = destinationAta,
                    tokenProgramId = tokenProgramKey,
                    amountBaseUnits = amountBaseUnits,
                    expiresAtEpochSeconds = expiresAtEpochSeconds,
                    coordinatorSignatureSalt = salt
                )
            )
        }

        val blockhash = solanaRpcClient.getLatestBlockhash(rpcUrl)
        val transaction = SolanaTransactionBuilder.buildUnsignedTransaction(
            feePayer = ownerKey,
            recentBlockhash = blockhash,
            instructions = instructions
        )
        // The dry run charges the fee to the owner, so a sponsored withdrawal from a zero-SOL
        // wallet would false-fail here even though the sponsor pays the real send. A sponsored
        // send's failures then surface through the provider's send status instead.
        if (!sponsoredFees) simulate(rpcUrl, transaction)

        Timber.d(
            "Rain SDK: composed Solana collateral withdrawal of %s base units of %s to %s (chainId=%d)",
            amountBaseUnits.toString(),
            mintAddress,
            recipientAddress,
            chainId
        )
        return UnsignedSolanaTransfer(transaction, blockhash, createsRecipientAccount = createDestination)
    }

    private fun decodeBase64(value: String, label: String, expectedSize: Int): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            throw RainError.InvalidConfig("Rain $label is not valid base64")
        }
        if (bytes.size != expectedSize) {
            throw RainError.InvalidConfig("Rain $label must be $expectedSize bytes, got ${bytes.size}")
        }
        return bytes
    }

    private fun decodeKey(address: String, label: String): ByteArray {
        val bytes = runCatching { Base58.decode(address) }.getOrNull()
        if (bytes == null || bytes.size != SolanaAddresses.PUBLIC_KEY_LENGTH) {
            throw RainError.InvalidConfig("Invalid Solana $label address: $address")
        }
        return bytes
    }

    private suspend fun simulate(rpcUrl: String, transaction: ByteArray) {
        val simulation = solanaRpcClient.simulateTransaction(
            rpcUrl,
            Base64.getEncoder().encodeToString(transaction)
        )
        if (simulation.succeeded) return
        if (simulation.fundsShortfall) throw RainError.InsufficientFunds()
        Timber.e(
            "Rain SDK: Solana withdrawal simulation failed: %s; logs: %s",
            simulation.error,
            simulation.logs.joinToString(" | ")
        )
        throw RainError.TransactionSimulationFailed(
            IllegalStateException(simulation.error ?: "Solana withdrawal simulation failed")
        )
    }

    private companion object {
        val COLLATERAL_AUTHORITY_SEED = "CollateralAuthority".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Keccak-encoded messages for Rain's Solana collateral program — the same EIP-712-shaped
 * construction the EVM contracts use, transplanted onto Solana (addresses are 32-byte ed25519
 * keys, and the signature is ed25519 rather than secp256k1).
 *
 * The domain's `chainId` is the fixed constant 900 on every cluster — Rain's Solana *mainnet*
 * id — because the deployed programs bake it into their domain hash. The Rain API's chain
 * routing (900/901) is unrelated to this value. Verified against a live devnet coordinator
 * signature: only 900 validates.
 */
internal object SolanaWithdrawMessages {
    private const val DOMAIN_CHAIN_ID = 900L

    private val DOMAIN_TYPE_HASH = keccak(
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract,bytes32 salt)"
            .toByteArray()
    )
    private val COORDINATOR_WITHDRAW_TYPE_HASH = keccak(
        (
            "Withdraw(address user,address collateral,address asset,uint256 amount," +
                "address recipient,uint256 nonce,uint256 expiresAt)"
            ).toByteArray()
    )

    /**
     * The 32-byte message Rain's coordinator executor signs to authorize a single-signer
     * withdrawal: `keccak(0x1901 || domainSeparator("Coordinator") || structHash)`.
     */
    fun coordinatorWithdrawMessage(
        coordinator: ByteArray,
        collateral: ByteArray,
        sender: ByteArray,
        receiver: ByteArray,
        asset: ByteArray,
        amountBaseUnits: BigInteger,
        nonce: Long,
        expiresAtEpochSeconds: Long,
        salt: ByteArray
    ): ByteArray {
        val domain = keccak(
            concat(
                DOMAIN_TYPE_HASH,
                keccak("Coordinator".toByteArray()),
                keccak("2".toByteArray()),
                u64(DOMAIN_CHAIN_ID),
                coordinator,
                salt
            )
        )
        val struct = keccak(
            concat(
                COORDINATOR_WITHDRAW_TYPE_HASH,
                sender,
                collateral,
                asset,
                u64(amountBaseUnits),
                receiver,
                u32(nonce),
                u64(expiresAtEpochSeconds)
            )
        )
        return keccak(concat(byteArrayOf(0x19, 0x01), domain, struct))
    }

    private fun keccak(data: ByteArray): ByteArray = Hash.sha3(data)

    /** Big-endian, fixed width — matching the reference encoder's zero-padded hex. */
    private fun u64(value: Long): ByteArray = u64(BigInteger.valueOf(value))

    private fun u64(value: BigInteger): ByteArray {
        require(value.signum() >= 0 && value.bitLength() <= 64) { "u64 out of range: $value" }
        val out = ByteArray(8)
        for (i in 0 until 8) {
            out[7 - i] = value.shiftRight(8 * i).and(BigInteger.valueOf(0xFF)).toByte()
        }
        return out
    }

    private fun u32(value: Long): ByteArray {
        require(value in 0..0xFFFFFFFFL) { "u32 out of range: $value" }
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}

/**
 * Minimal borsh readers for the two collateral-program accounts a withdrawal needs. Layouts
 * follow the program's Anchor IDL (v2.02); each account starts with Anchor's 8-byte
 * discriminator (`sha256("account:<Name>")[0..8]`), which is how the account type is checked.
 */
internal object SolanaCollateralAccounts {
    // IDL: [19, 45, 99, 29, 196, 50, 228, 117]
    private val SINGLE_SIGNER_DISCRIMINATOR = byteArrayOf(19, 45, 99, 29, 196.toByte(), 50, 228.toByte(), 117)

    /** `SingleSignerCollateral`: id, coordinator, authority_bump, name, nonce, owner. */
    data class SingleSignerCollateral(
        val coordinator: ByteArray,
        val nonce: Long,
        val owner: ByteArray
    )

    /** Parses a `SingleSignerCollateral` account, or null when the discriminator doesn't match. */
    fun parseSingleSigner(data: ByteArray): SingleSignerCollateral? {
        if (data.size < 8 || !data.copyOfRange(0, 8).contentEquals(SINGLE_SIGNER_DISCRIMINATOR)) {
            return null
        }
        val reader = BorshReader(data, offset = 8)
        reader.pubkey() // id
        val coordinator = reader.pubkey()
        reader.u8() // authority_bump — re-derived locally, the account's copy is unused
        reader.string() // name
        val nonce = reader.u32()
        val owner = reader.pubkey()
        return SingleSignerCollateral(coordinator = coordinator, nonce = nonce, owner = owner)
    }

    /** `Coordinator`: id, bump, owner, collateral_creator, treasury, publishers, executors. */
    fun parseCoordinatorExecutors(data: ByteArray): List<ByteArray> {
        val reader = BorshReader(data, offset = 8)
        reader.pubkey() // id
        reader.u8() // bump
        reader.pubkey() // owner
        reader.pubkey() // collateral_creator
        reader.pubkey() // treasury
        val publishers = reader.u32()
        repeat(publishers.toInt()) { reader.pubkey() }
        val executors = reader.u32()
        return List(executors.toInt()) { reader.pubkey() }
    }

    /** Sequential little-endian borsh reader; throws on truncated data. */
    private class BorshReader(private val data: ByteArray, private var offset: Int) {
        fun u8(): Int = data[next(1)].toInt() and 0xFF

        fun u32(): Long {
            val start = next(4)
            var value = 0L
            for (i in 0 until 4) {
                value = value or ((data[start + i].toLong() and 0xFF) shl (8 * i))
            }
            return value
        }

        fun pubkey(): ByteArray {
            val start = next(SolanaAddresses.PUBLIC_KEY_LENGTH)
            return data.copyOfRange(start, start + SolanaAddresses.PUBLIC_KEY_LENGTH)
        }

        fun string(): String {
            val length = u32()
            require(length <= data.size) { "Implausible borsh string length: $length" }
            val start = next(length.toInt())
            return String(data, start, length.toInt(), Charsets.UTF_8)
        }

        private fun next(size: Int): Int {
            val start = offset
            require(size >= 0 && start + size <= data.size) {
                "Truncated Solana account data at offset $start"
            }
            offset += size
            return start
        }
    }
}
