# Rain SDK for Android

Android SDK that connects a wallet — the Rain wallet, or an MPC or embedded wallet from
[Portal](https://portalhq.io), [Turnkey](https://turnkey.com), or [Privy](https://privy.io) — to Rain collateral: build EIP-712
messages, compose withdrawal transactions, sign and submit via a registered wallet provider, read
balances and history, and estimate fees. Works on EVM chains and Solana.

- **Rain wallet** — Register a `RainProvider` (`rain-wallet-android`); the SDK runs login by a one-time code (email or SMS) or by a passkey bound to your domain, provisions one wallet with Ethereum and Solana accounts on first login, manages the session and exports the recovery phrase and private keys, all under Rain's names with no wallet-vendor type on its surface. See [rain-wallet-android/README.md](rain-wallet-android/README.md).
- **Portal wallet integration** — Register a `PortalProvider` with a Portal session token and resolve a client; use the connected MPC wallet for signing and sending transactions. Session refresh is host-driven via `PortalConfig.onSessionTokenNeeded` / `onSessionExpired`; see the adapter table in [docs/METHODS.md](docs/METHODS.md#provider-adapters).
- **Turnkey wallet integration** — Register a `TurnkeyProvider` with a `TurnkeyContext` your app authenticated (passkeys / auth proxy / OAuth / OTP). The SDK also carries a managed mode (a one-time code by email or SMS, or a passkey) behind the `@InternalRainTurnkeyApi` opt-in marker: the building block of the Rain wallet provider (`rain-wallet-android`), not a host-facing API. In both modes the provider exports the wallet's recovery phrase and private keys, decrypted on the device, through `exportRecoveryPhrase` and `exportPrivateKey`. See [docs/TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md).
- **Privy wallet integration** — Register a `PrivyProvider` with an authenticated `Privy` instance; embedded EVM and Solana wallets are used for custody.
- **Solana support** — Native SOL and SPL transfers, balances, history, and collateral withdrawal, on the same `RainClient` methods as EVM. See [Solana](#9-solana).
- **Wallet-agnostic utilities** — The transaction-building methods (EIP-712 message, withdraw calldata) are available straight off `RainSdk` from the configured RPC endpoints, with no wallet provider resolved — use them with your own wallet or backend.
- **Pluggable providers** — Bring your own `WalletProvider` behind a `ProviderDescriptor` and register it; resolve providers by id or by `Capability`.
- **EIP-712 message building** — Build typed data for admin signature required by the collateral contract.
- **Withdrawal transaction building** — Build ABI-encoded withdraw calldata for submission.
- **Full withdrawal flow** — Builds the transaction, signs via the backing provider, and submits; returns the transaction hash. `prepareWithdrawal` builds the same transaction without broadcasting.
- **Fee estimation** — Returns the estimated gas cost in the chain's native token (e.g. AVAX).
- **Wallet information** — Get current wallet address and generate a QR code `Bitmap` for it.
- **Balances** — Get native, ERC-20, and SPL token balances for the current wallet.
- **Transaction history** — Get transactions for the current wallet with optional pagination and sort order.
- **Send tokens** — Send native, ERC-20, or SPL tokens from the current wallet.
- **Auth Pull approvals** — Approve Rain's operator to spend the user's USDC, read the allowance back, and estimate the approval fee. See [docs/AUTH_PULL.md](docs/AUTH_PULL.md).
- **Exact money handling** — Public money APIs are `BigDecimal`; base-unit conversion is exact and rejects an amount finer than the token's scale rather than truncating it.

## Installation

> **Beta.** `5.0.0-beta.1` is the first public beta. Pin the exact version, as below, and don't use
> a range or `+`: Maven has no pre-release flag, so a range picks up every later beta, and a beta
> may still change API between versions (see [Compatibility](#compatibility)).

The modules are on Maven Central. `google()` and `mavenCentral()`, the two repositories a new
Android project already declares, resolve them and everything they depend on. The SDK is
modular (ports & adapters): a vendor-free **`rain-core-android`** plus one adapter module per
wallet provider. Link only the providers you use — an unselected provider's vendor SDK never enters
your dependency graph.

```kotlin
dependencies {
    // Pick the module for the wallet you use. Each one pulls rain-core-android in; the other
    // vendors' SDKs never enter your dependency graph.

    // The Rain wallet: SDK-owned login, sessions and key export under Rain's names. Pulls in
    // rain-turnkey-android and with it the Turnkey Kotlin SDK; no vendor type is on the surface
    // you compile against.
    implementation("xyz.rain:rain-wallet-android:5.0.0-beta.1")

    // Turnkey, bring-your-own: your app authenticates the TurnkeyContext.
    // implementation("xyz.rain:rain-turnkey-android:5.0.0-beta.1")

    // Portal MPC.
    // implementation("xyz.rain:rain-portal-android:5.0.0-beta.1")

    // Privy embedded wallets.
    // implementation("xyz.rain:rain-privy-android:5.0.0-beta.1")

    // Your own wallet or signer: core alone. See "Bring your own provider" below.
    // implementation("xyz.rain:rain-core-android:5.0.0-beta.1")
}
```

Each adapter depends on core, so one line is enough. Add a second adapter only if the app offers a
choice of wallet provider at runtime. The one pair that cannot share a `RainSdk` is
`rain-wallet-android` and `rain-turnkey-android`: both drive one process-wide wallet backend, so
`build()` refuses a registry holding both.

All Rain modules share one version number. Use the same version for every Rain module in one app;
mixing versions is not supported.

| Module        | Contains                                                                 |
|---------------|--------------------------------------------------------------------------|
| `rain-core-android`   | The `WalletProvider` port, capability model, provider registry, and all Rain domain logic. No wallet vendor SDK. |
| `rain-turnkey-android` | The Turnkey adapter (`TurnkeyProvider`, `com.rain.sdk.turnkey`); depends on `rain-core-android` + the Turnkey Kotlin SDK. |
| `rain-wallet-android` | The Rain wallet (`RainProvider`, `com.rain.sdk.wallet`): SDK-owned login by code or passkey, provisioning, sessions and key export under Rain's names; depends on `rain-core-android` + `rain-turnkey-android`. |
| `rain-portal-android` | The Portal MPC adapter (`PortalProvider`); depends on `rain-core-android` + `portal-android`. |
| `rain-privy-android`  | The Privy embedded-key adapter (`PrivyProvider`); depends on `rain-core-android` + `privy-core`. |

## Requirements

- minSdk 28 or higher (the wallet vendors require it)
- compileSdk 36 or higher (the AARs declare minCompileSdk 36; the SDK itself compiles against 37)
- Kotlin 2.2 recommended; Kotlin 2.1 compiles against the SDK's metadata through the compiler's one-version-ahead reading (the SDK is built with Kotlin 2.2.21 and ships Java 11 bytecode)
- Android Gradle Plugin 8.11 or higher (tested with 8.11.2 and 9.4.0; older versions are untested)
- JDK 24 to run JVM unit tests that reach Turnkey's code through `rain-turnkey-android` or
  `rain-wallet-android`, because Turnkey's `com.turnkey:encoding` ships Java 24 bytecode. App
  builds are unaffected.

## Compatibility

Rain treats the beta as a real release, so an app built on `5.0.0-beta.1` should keep building on
later betas:

- Rain avoids breaking changes. When one can't be avoided, the [CHANGELOG](CHANGELOG.md) lists it as
  a **Breaking:** entry with a migration note.
- A deprecated API stays for at least one release before it is removed.
- `RAIN_xxx` error codes keep their meaning. Message text may change, so branch on the code or the
  error type, never on the message.
- Enums and sealed types may gain new cases in a minor release, unless their docs say a `when` over
  them is exhaustive (the Rain wallet's session and auth states). Give any other `when` over them an
  `else` branch.
- Anything marked `@ExperimentalRainApi` may change in any release. Implementing `WalletProvider`,
  `ProviderDescriptor` or `RainClient` requires opting in to it, because Rain may add members to them.
- `@RainAdapterApi` and `@InternalRainTurnkeyApi` mark code that Rain's own modules share. They carry
  no compatibility guarantee, so code that opts in to them may break in any release.

From `5.0.0` on, nothing outside these three opt-in markers breaks before `6.0.0`.

## Quick Start

### 1. Initialize with Portal (full wallet flow)

Use this when you want the SDK to use Portal for signing and sending transactions.

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.portal.PortalConfig
import com.rain.sdk.portal.PortalProvider
import com.rain.sdk.provider.ProviderId

val rain = RainSdk.builder()
    .rpcEndpoints(
        mapOf(
            43114 to "https://avalanche-c-chain-rpc.publicnode.com",
            43113 to "https://avalanche-fuji-c-chain-rpc.publicnode.com"
        )
    )
    .register(PortalProvider(PortalConfig(sessionToken = "<your-portal-session-token>")))
    .build()

// Resolve the Portal-backed client (suspending — resolves the wallet on first access).
val client = rain.provider(ProviderId.PORTAL)
```

### 2. Initialize with Turnkey (full wallet flow)

**Bring-your-own** (the public Turnkey integration) — drive Turnkey's Kotlin SDK yourself
(passkeys / auth proxy / OAuth / OTP), then hand the authenticated `TurnkeyContext` to Rain via
`TurnkeyConfig(turnkey = TurnkeyContext)` and register it like any provider:

```kotlin
val provider = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext))
val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(8453 to "https://mainnet.base.org", 84532 to "https://sepolia.base.org"))
    .register(provider)
    .build()
val client = rain.provider(ProviderId.TURNKEY)

// Backup, in either mode: the recovery phrase, or one private key per chain family, decrypted on the device.
val phrase = provider.exportRecoveryPhrase()
val solanaKey = provider.exportPrivateKey(TurnkeyKeyFamily.SOLANA)
```

See [docs/TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md) for the bring-your-own mode in full, including
`authState`, `logout()`, and the one-shot configuration rule. The SDK also carries a managed mode behind
`@InternalRainTurnkeyApi`; hosts reach it as the Rain wallet, `RainProvider(application)` in
[rain-wallet-android/README.md](rain-wallet-android/README.md), which exposes the same steps with no
vendor type or name. Internals: [Managed mode (internal API)](docs/TURNKEY_SUPPORT.md#managed-mode-internal-api).

### 3. Bring your own provider, or resolve by capability

The registry is designed for the multi-provider case; a single-provider app is just the trivial
`N = 1` instance of it. A wallet Rain ships no adapter for (Coinbase, Dynamic, a custom MPC stack,
your own signing backend) plugs in through core alone: implement the `WalletProvider` port, wrap it
in a `ProviderDescriptor`, and register that. Only `rain-core-android` is needed.

`WalletProvider` lives in `com.rain.sdk.provider`. Implementing it or `ProviderDescriptor` needs
`@OptIn(ExperimentalRainApi::class)` on your class, as below: Rain may add members to these
interfaces in any release, with a default body wherever one makes sense. Calling the SDK needs
no opt-in. Nine members are required, the rest have defaults, and an EVM-only wallet keeps the
default `sendSolanaTransaction`, which refuses with `RAIN_102`.

```kotlin
import com.rain.sdk.ExperimentalRainApi
import com.rain.sdk.RainSdk
import com.rain.sdk.error.RainError
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransaction
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderDescriptor
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.WalletProvider
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException

/** What you register. `create` runs once, on the first `rain.provider(id)`. */
@OptIn(ExperimentalRainApi::class)
class MyWalletDescriptor(private val wallet: MyWalletSdk) : ProviderDescriptor {
    override val id = ProviderId("my-wallet")
    override val capabilities = emptySet<Capability>() // add EXPORT, MULTI_CHAIN, ... as you support them

    override suspend fun create(context: ProviderContext): WalletProvider =
        MyWalletProvider(wallet, context.rpcEndpoints)
}

/** The port. Every call on the resolved `RainClient` ends in one of these. */
@OptIn(ExperimentalRainApi::class)
class MyWalletProvider(
    private val wallet: MyWalletSdk, // your wallet SDK, whatever its shape
    private val rpcEndpoints: Map<Int, String>,
) : WalletProvider {
    override val id = ProviderId("my-wallet")

    // A chain your wallet cannot broadcast on fails closed here, before any read or signing prompt.
    override fun requireSendSupport(chainId: Int) {
        if (chainId !in rpcEndpoints) throw RainError.ChainNotSupported(chainId, "no RPC endpoint configured")
    }

    override suspend fun getWalletAddress(): String = guarded { wallet.address() }

    override suspend fun signTypedData(chainId: Int, walletAddress: String, typedDataJson: String): String =
        guarded { wallet.signTypedData(chainId, typedDataJson) }

    override suspend fun sendTransaction(chainId: Int, from: String, to: String, data: String, value: String): String =
        guarded { wallet.send(chainId, to, data, value) }

    override suspend fun estimateTransactionFee(chainId: Int, from: String, to: String, data: String, value: String): BigDecimal =
        guarded { wallet.estimateFee(chainId, to, data, value) }

    override suspend fun sendNativeToken(chainId: Int, toAddress: String, amountInEth: BigDecimal): String =
        guarded { wallet.sendNative(chainId, toAddress, amountInEth) }

    override suspend fun sendToken(chainId: Int, contractAddress: String, toAddress: String, amount: BigDecimal, decimals: Int): String =
        guarded { wallet.sendToken(chainId, contractAddress, toAddress, amount, decimals) }

    override suspend fun getBalance(chainId: Int, token: Token): Balance = guarded {
        val (raw, decimals) = wallet.balance(chainId, (token as? Token.Contract)?.address)
        Balance(token = token, chainId = chainId, rawAmount = raw, decimals = decimals)
    }

    override suspend fun getBalances(chainId: Int): List<Balance> = listOf(getBalance(chainId, Token.Native))

    override suspend fun getTransactions(chainId: Int, limit: Int?, offset: Int?, order: RainTransactionOrder?): List<RainTransaction> =
        emptyList() // or map your indexer's rows onto RainTransaction

    // The error contract: a vendor exception never leaves the adapter. Core keeps a RainError's code,
    // so an expired session reaches the host as RAIN_201 and its re-authentication path runs.
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RainError) {
        throw e // already carries its code; wrapping it would replace the code with RAIN_501
    } catch (e: MyWalletSdk.SessionExpired) {
        throw RainError.TokenExpired()
    } catch (e: Exception) {
        throw RainError.ProviderError(e)
    }
}

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(8453 to "https://mainnet.base.org"))
    .register(MyWalletDescriptor(myWalletSdk))
    .build()
val client = rain.provider(ProviderId("my-wallet"))
```

With several providers registered, resolve by id as above or by capability:

```kotlin
import com.rain.sdk.provider.Capability

// …or resolve the first registered provider with a given capability
val exporter = rain.first { Capability.EXPORT in it.capabilities }
```

A client resolved by `Capability.EXPORT` has no export method of its own. The export methods live on
the descriptor you registered: `exportRecoveryPhrase` and `exportPrivateKey` on `TurnkeyProvider` and
on the Rain wallet's `RainProvider` (see [rain-wallet-android/README.md](rain-wallet-android/README.md)),
so keep a reference to it.

### 4. Get Wallet Address

```kotlin
val address = client.getWalletAddress()
```

### 5. Check Balances

```kotlin
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo

// `client` is the RainClient resolved in Quick Start (rain.provider(...))

// Native token balance (e.g. AVAX) — exact rawAmount plus resolved decimals/symbol/name
val native = client.getBalance(chainId = 43114, token = Token.Native)
println("${native.formatted} ${native.symbol}") // e.g. "1.5 AVAX"

// Specific ERC-20 token balance (e.g. USDC). The SDK resolves the token's decimals/symbol
// itself, so you only pass the contract address (case-insensitive).
val usdc = client.getBalance(chainId = 43114, token = Token.Contract("0x..."))

// The native balance plus the contract tokens the provider lists (see METHODS.md, getTokenBalances)
val balances: List<Balance> = client.getTokenBalances(chainId = 43114)

// Every configured chain, flattened into one list — each Balance carries its own chainId
val all: List<Balance> = client.getAllBalances()

// Optionally register extra tokens so their metadata resolves without an on-chain lookup.
// Built-in tokens are trusted: a registration for an address the SDK already ships is ignored.
// The same call exists on the builder and, after build(), on RainSdk itself (rain.registerTokens).
client.registerTokens(
    listOf(TokenInfo(chainId = 43114, address = "0x...", symbol = "FOO", decimals = 18))
)
```

Each `Balance` exposes `rawAmount` (`BigInteger`, exact base units), `decimals`, `symbol`,
`name`, plus derived `decimalAmount` (`BigDecimal`) and `formatted` (`String`) for display.

### 6. Send Tokens

```kotlin
import java.math.BigDecimal

// `client` is the RainClient resolved in Quick Start (rain.provider(...))

// Send native token (ETH on Base). Turnkey sends work only on Turnkey's
// managed-broadcast chains; other configured chains (e.g. Avalanche) stay
// read-only and sends there fail fast with RAIN_104.
val result = client.sendNative(
    chainId = 8453,
    to = "0x...",
    amount = BigDecimal("0.1")
)
println("Tx Hash: ${result.transactionHash}")

// Send ERC-20 token (e.g. USDC). Omit decimals to let the SDK resolve them.
val tokenResult = client.sendToken(
    chainId = 8453,
    contractAddress = "0x...",
    to = "0x...",
    amount = BigDecimal("100.0")
)
```

### 7. Rain API: collateral contracts and admin signature (host responsibility)

The SDK does not call the Rain issuing API. The two things a withdrawal needs from Rain come from
your backend, which holds the program Api-Key and sends it as the `Api-Key` request header on every
call it makes on the user's behalf:

- `GET /v1/issuing/users/{userId}/contracts` returns the user's collateral contracts. From the one
  on your chain, take `chainId`, `proxyAddress`, `controllerAddress`, `tokens[].address` and, for
  the signature request, one of `adminAddresses`.
- `GET /v1/issuing/users/{userId}/signatures/withdrawals?chainId=&token=&amount=&adminAddress=&recipientAddress=&isAmountNative=true`
  returns Rain's authorization for one withdrawal: `signature.salt`, `signature.data` and
  `expiresAt`. Poll while `status` is not `"ready"`, waiting `retryAfter` seconds when the response
  carries that field, and treat `"ready"` without `signature.data` as not ready. `token` is the token
  contract address (the SPL mint on Solana) and `amount` is in the token's base units, the same scale
  `withdrawCollateral` derives from `amount` and `decimals`.

Build the SDK's inputs from those fields and hand them to the withdrawal methods:

```kotlin
val addresses = RainWithdrawAddresses(
    proxyAddress = contract.proxyAddress,
    controllerAddress = contract.controllerAddress,
    tokenAddress = token.address,
    recipientAddress = recipient,
)
val adminSignature = RainAdminSignature(
    salt = response.signature.salt,      // base64, 32 bytes
    signature = response.signature.data, // EVM: 0x-hex, 65 bytes; Solana: base64, 64 bytes
    expiresAt = response.expiresAt,      // unix seconds, or ISO-8601 with Z or a numeric offset
)
```

Token `name`, `symbol` and `decimals` are not on the wire. Resolve them with
`rain.tokenMetadata(chainId, address)`. It answers from the built-in registry, then from
host-registered tokens, then from an on-chain read. It returns `null` when decimals cannot be
established and never guesses, so disable money actions for such a token rather than assuming a
scale. It throws `RainError.InvalidConfig` when the SDK has no RPC endpoint for the chain or the
address is malformed; both are configuration errors, not lookups that failed.

Keep the program Api-Key on your server. The demo app takes it as on-device input only because it has no
backend; its reference client is `app/src/main/java/com/rain/sdk/sample/RainApiClient.kt`.

### 8. Withdraw Collateral

The SDK uses `RainWithdrawAddresses` and `RainAdminSignature` to group withdrawal parameters.
Both come from the Rain API responses your backend fetches, as section 7 shows (the collateral
contract supplies the addresses and the withdrawal-signature response supplies the `RainAdminSignature`):

```kotlin
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.RainAdminSignature
import java.math.BigDecimal

val addresses = RainWithdrawAddresses(
    proxyAddress = "0x...",
    controllerAddress = "0x...",
    tokenAddress = "0x...",
    recipientAddress = "0x..."
)

val adminSignature = RainAdminSignature(
    salt = "...",
    signature = "...",
    expiresAt = "..."   // unix seconds or an ISO-8601 instant, from the Rain API response (section 7)
)

// Sign and submit via the backing provider, returns the tx hash.
// Always broadcasts; prepareWithdrawal builds without sending.
val txHash = client.withdrawCollateral(
    chainId = 8453,
    addresses = addresses,
    amount = BigDecimal("100.0"),
    decimals = 6,
    adminSignature = adminSignature
)
println("Tx Hash: $txHash")

// Or build it without broadcasting, for custom submission
val prepared = client.prepareWithdrawal(
    chainId = 8453,
    addresses = addresses,
    amount = BigDecimal("100.0"),
    decimals = 6,
    adminSignature = adminSignature
)
println("Tx: ${prepared.evmParameters}")   // solanaTransfer on a Solana chain

// Quote the fee of the prepared withdrawal, with no second signature
val fee = client.estimateWithdrawalFee(chainId = 8453, prepared = prepared)
```

### 9. Solana

> **Known issue in `5.0.0-beta.1`.** Solana `sendNative`, `sendToken` and `withdrawCollateral`
> through the Turnkey provider or the Rain wallet fail with `RAIN_501` and broadcast nothing.
> Privy's Solana sends are not affected. See the [CHANGELOG](CHANGELOG.md).

Solana uses the same `RainClient` methods as EVM — the SDK routes on the chain ID. `RainChain`
exposes the sentinel IDs (`SOLANA_MAINNET` 900, `SOLANA_DEVNET` 901, `SOLANA_TESTNET` 902); these
are Rain's routing IDs, not Solana chain IDs. Register a Solana RPC URL against them like any other
chain.

```kotlin
val client = rain.provider(ProviderId.TURNKEY)   // or PRIVY; Portal has no Solana account
val chainId = RainChain.SOLANA_DEVNET

client.getWalletAddress(chainId)                 // the Solana account, not the EVM address
client.getBalance(chainId, Token.Native)         // SOL
client.getTokenBalances(chainId)                 // SPL holdings
client.sendNative(chainId, recipientBase58, BigDecimal("0.01"))
client.sendToken(chainId, mintAddress, recipientBase58, BigDecimal("1.5"))
```

`withdrawCollateral` works unchanged, with `proxyAddress` as the collateral account and
`tokenAddress` as the SPL mint. Under the hood the withdrawal is authorized by Rain's coordinator
signing a message off chain rather than by EVM calldata, so the SDK composes (and, when the provider
pays its own fee, simulates) a collateral-program transaction and the provider signs it; `prepareWithdrawal` returns those prepared
bytes along with their `recentBlockhash`. See [TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md#solana-notes) for the details.

On `sendToken`, an SPL mint's decimals are read from the chain, so the `decimals` argument does not
scale the amount. `withdrawCollateral` and `prepareWithdrawal` are the opposite: there `decimals`
**does** scale the amount and is not checked against the mint, so pass the mint's real decimals.
Mints carry no on-chain symbol — `registerTokens(...)` names the ones you want displayed.

### 10. Estimate Gas

```kotlin
val fee = client.estimateGas(
    chainId = 43114,
    from = walletAddress,
    to = controllerAddress,
    data = transactionData
)
println("Estimated fee: $fee AVAX")
```

On a provider that sponsors fees the estimate is still what the wallet would pay itself; a sponsor pays instead.

### 11. Transaction History

```kotlin
import com.rain.sdk.models.RainTransactionOrder

val transactions = client.getTransactions(
    chainId = 43114,
    limit = 20,
    offset = 0,
    order = RainTransactionOrder.DESC
)

transactions.forEach { tx ->
    println("${tx.hash} — ${tx.from} → ${tx.to}: ${tx.value}")
}
```

### 12. Auth Pull: approve the Rain operator

Auth Pull draws a card authorization's amount straight from the user's wallet into their Rain
collateral contract. The wallet-side prerequisite is an ERC-20 allowance for Rain's operator — that
part is the SDK's; the pull itself is Rain's.

```kotlin
val authPull = RainAuthPullConfig.sandbox(rainOperatorAddress)
val rain = RainSdk.builder()
    .rpcEndpoints(rpcEndpoints)
    .authPullConfig(authPull)
    .register(provider)
    .build()

val client = rain.provider(provider.id)

// 1. What can the operator move today?
val allowance = client.getTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = rainOperatorAddress   // per environment; read it from Rain
)
if (allowance.covers(expectedSpend)) return

// 2. What will the approval cost?
val fee = client.estimateApprovalFee(RainChain.BASE_SEPOLIA, usdcAddress, rainOperatorAddress)

// 3. Approve. Omitting `amount` approves an unlimited allowance, so the user never re-approves;
//    pass a BigDecimal to cap it, or BigDecimal.ZERO to revoke.
val result = client.approveTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = rainOperatorAddress
)
println(result.transactionHash)

// A hash means submitted, not ready. Confirm reads the allowance back at the mined block; a
// timeout is TransactionPending (carrying the hash), not failure — re-read, don't re-approve.
val confirmed = client.confirmTokenAllowance(
    transactionHash = result.transactionHash,
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = rainOperatorAddress
)
```

Sandbox runs on Base Sepolia and Arbitrum Sepolia, production on Base and Arbitrum; USDC on all four
is in the built-in token registry. Auth Pull remains disabled until `authPullConfig(...)` supplies
Rain's trusted operator and canonical token targets, and the SDK rejects any different chain, token,
or spender before a wallet prompt. The configuration you pass is the environment: `sandbox(...)`
and `production(...)` carry their own chains, and `custom(...)` may name chains from either set for a
non-standard deployment.

Gate your UI on `rain.authPullChainIds` (also on `RainClient`), which is what the approval guard
enforces: the configuration narrowed to chains with an RPC endpoint. `RainAuthPullChains.SANDBOX` and
`RainAuthPullChains.PRODUCTION` answer for an environment and are the wider sets; use them only before
an SDK exists.
`RainTokenAllowance.rawAmount` is the exact base-unit value and the one to compare against — gate on
`isUnlimited` before rendering a number. Full guide: [docs/AUTH_PULL.md](docs/AUTH_PULL.md).

### 13. QR Code Generation

```kotlin
val bitmap = client.generateAddressQRCode(dimension = 256)
// Use the bitmap in an ImageView
imageView.setImageBitmap(bitmap)
```

## Documentation

For a complete reference of all public methods, parameters, types, and error codes, see the [Method Reference](docs/METHODS.md). For the Auth Pull approval flow end to end, see [docs/AUTH_PULL.md](docs/AUTH_PULL.md).

## License

Apache License 2.0. See the [LICENSE](LICENSE) file for details.
