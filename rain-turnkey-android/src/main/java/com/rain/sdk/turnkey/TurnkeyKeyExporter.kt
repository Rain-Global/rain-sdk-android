package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError
import com.rain.sdk.internal.solana.Base58
import com.rain.sdk.internal.utils.strippingHexPrefix
import com.turnkey.core.models.Wallet
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1Curve
import com.turnkey.types.V1WalletAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Every exported key is 32 bytes: a secp256k1 private key and an ed25519 seed alike. */
internal const val KEY_LENGTH = 32

/** The vendor address format an account of this family is stored under. */
internal val TurnkeyKeyFamily.addressFormat: V1AddressFormat
    get() = when (this) {
        TurnkeyKeyFamily.ETHEREUM -> V1AddressFormat.ADDRESS_FORMAT_ETHEREUM
        TurnkeyKeyFamily.SOLANA -> V1AddressFormat.ADDRESS_FORMAT_SOLANA
    }

/** The curve an account of this family must be on. A mismatch means the key is not what the family promises. */
internal val TurnkeyKeyFamily.curve: V1Curve
    get() = when (this) {
        TurnkeyKeyFamily.ETHEREUM -> V1Curve.CURVE_SECP256K1
        TurnkeyKeyFamily.SOLANA -> V1Curve.CURVE_ED25519
    }

/**
 * The seam behind [TurnkeyProvider.exportRecoveryPhrase] and [TurnkeyProvider.exportPrivateKey],
 * in both modes; *Key export* in `docs/TURNKEY_SUPPORT.md` has the contract. Keys follow the
 * accounts the SDK signs with, as [TurnkeyAccounts] resolves them, and the phrase follows the
 * wallet holding the Ethereum account, else the first wallet, so the phrase and the exported
 * Ethereum key always derive the same address. A [walletAddressOverride] that names no Ethereum
 * account of the organization is a configuration error raised before any export call, for every
 * export, because signing with it is already broken and a phrase for another wallet would back up
 * an account the app never signs with.
 *
 * The seam returns raw material, so every check that decides what a caller gets runs here: the
 * key must be 32 bytes, and the address it derives, on either curve, must be the account's.
 * Nothing on this path is logged. The vendor's `String` copy of the material cannot be wiped, so
 * the promise is never logged, cached or persisted, not zeroed.
 */
internal class TurnkeyKeyExporter(
    private val turnkey: TurnkeyContextProtocol,
    private val sessions: TurnkeySessionCoordinator,
    private val walletAddressOverride: String?,
    private val ensureConfigured: suspend () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /** The mnemonic of the wallet holding the Ethereum account, else the first wallet. */
    suspend fun exportRecoveryPhrase(): String {
        ensureConfigured()
        val walletId = ethereumAnchor()?.walletId
            ?: turnkey.wallets.firstOrNull()?.id
            ?: throw RainError.WalletUnavailable(NO_WALLET)
        return boundary {
            sessions.executeRead { _, _ ->
                withContext(dispatcher) { turnkey.exportWalletMnemonic(walletId) }
            }
        }
    }

    /** The private key of the account the SDK signs with for [family], encoded for that family's wallets. */
    suspend fun exportPrivateKey(family: TurnkeyKeyFamily): String {
        ensureConfigured()
        val account = accountFor(family)
        val hex = boundary {
            sessions.executeRead { _, _ ->
                withContext(dispatcher) { turnkey.exportAccountPrivateKeyHex(account.address) }
            }
        }
        // The derivation is CPU work too, so it stays off the caller's dispatcher.
        return withContext(dispatcher) { encode(family, hex, account) }
    }

    private suspend fun accountFor(family: TurnkeyKeyFamily): V1WalletAccount {
        val account = when (family) {
            TurnkeyKeyFamily.ETHEREUM -> ethereumAnchor()
            TurnkeyKeyFamily.SOLANA -> {
                // A walletAddress that names no Ethereum account is a misconfigured provider, so the
                // Solana key is refused too, before any export call, as the docs promise.
                if (hasOverride) ethereumAnchor()
                resolve(TurnkeyAccounts::solanaAccount)
            }
        } ?: throw RainError.WalletUnavailable(noAccount(family))
        if (account.curve != family.curve) throw RainError.WalletUnavailable(CURVE_MISMATCH)
        return account
    }

    private val hasOverride: Boolean get() = !walletAddressOverride.isNullOrEmpty()

    /**
     * The Ethereum account the SDK signs with: the account at the override when one is set, else
     * the first Ethereum-format account across the wallets, or null when the organization has none.
     * An empty override counts as unset, as it does for the manager's address.
     */
    private suspend fun ethereumAnchor(): V1WalletAccount? {
        val override = walletAddressOverride?.takeIf { it.isNotEmpty() }
            ?: return resolve(TurnkeyAccounts::ethereumAccount)
        return resolve { TurnkeyAccounts.accountAt(it, override, V1AddressFormat.ADDRESS_FORMAT_ETHEREUM) }
            ?: throw RainError.InvalidConfig(OVERRIDE_NOT_ETHEREUM)
    }

    /** One refresh when the list is empty or the target is missing, then the answer, null included. */
    private suspend fun resolve(pick: (List<Wallet>) -> V1WalletAccount?): V1WalletAccount? =
        pick(turnkey.wallets) ?: run {
            boundary { sessions.executeRead { _, _ -> turnkey.refreshWallets() } }
            pick(turnkey.wallets)
        }

    private fun encode(family: TurnkeyKeyFamily, hex: String, account: V1WalletAccount): String {
        val bare = hex.strippingHexPrefix()
        val seed = decodeHex(bare)?.takeIf { it.size == KEY_LENGTH } ?: throw RainError.InternalError(BAD_KEY_MATERIAL)
        try {
            return when (family) {
                TurnkeyKeyFamily.ETHEREUM -> ethereumKey(seed, bare, account)
                TurnkeyKeyFamily.SOLANA -> solanaKeypair(seed, account)
            }
        } finally {
            seed.fill(0)
        }
    }

    /** `0x` plus the lowercase hex, once the key sits inside the curve order and derives the account's address. */
    private fun ethereumKey(seed: ByteArray, bare: String, account: V1WalletAccount): String {
        val derived = try {
            EthereumKeyEncoder.address(seed)
        } catch (e: IllegalArgumentException) {
            throw RainError.InternalError(BAD_KEY_MATERIAL, e)
        }
        if (!derived.equals(account.address, ignoreCase = true)) throw RainError.InternalError(ADDRESS_MISMATCH)
        return "0x" + bare.lowercase()
    }

    private fun solanaKeypair(seed: ByteArray, account: V1WalletAccount): String {
        val publicKey = SolanaKeyEncoder.publicKey(seed)
        try {
            if (Base58.encode(publicKey) != account.address) throw RainError.InternalError(ADDRESS_MISMATCH)
            return SolanaKeyEncoder.keypairBase58(seed, publicKey)
        } finally {
            publicKey.fill(0)
        }
    }

    /**
     * The vendor's `exportWallet` wraps the caller's cancellation in its export failure, and the
     * coordinator rethrows only a bare `CancellationException`, so a cancelled export would leave
     * as a `RainError`. Re-checking the coroutine after any `RainError` lets the cancellation win.
     */
    private suspend inline fun <T> boundary(block: () -> T): T = try {
        block()
    } catch (e: RainError) {
        currentCoroutineContext().ensureActive()
        throw e
    }

    private companion object {
        const val HEX_RADIX = 16

        // Fixed messages: none may echo an address, a key or a phrase.
        const val NO_WALLET = "This organization has no wallet to export a recovery phrase from"
        const val CURVE_MISMATCH =
            "The account for this key family is on an unexpected curve, so nothing was exported"
        const val BAD_KEY_MATERIAL = "The exported key was not a valid 32-byte key, so nothing was returned"
        const val ADDRESS_MISMATCH = "The exported key does not derive the account's address, so nothing was returned"
        const val OVERRIDE_NOT_ETHEREUM = "walletAddress is not an Ethereum account of this organization"

        fun noAccount(family: TurnkeyKeyFamily): String {
            val name = when (family) {
                TurnkeyKeyFamily.ETHEREUM -> "Ethereum"
                TurnkeyKeyFamily.SOLANA -> "Solana"
            }
            return "No $name account on the wallet. In managed mode, log in again to provision it"
        }

        /** Strict hex: even length, hex digits only, no prefix. Null for anything else. */
        fun decodeHex(hex: String): ByteArray? {
            val wellFormed = hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it.isHexDigit() }
            if (!wellFormed) return null
            return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(HEX_RADIX).toByte() }
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
