# Rain SDK for Android

Android SDK that connects a wallet to Rain collateral: the Rain wallet, or an MPC or embedded
wallet from [Portal](https://portalhq.io), [Turnkey](https://turnkey.com) or [Privy](https://privy.io).
It builds EIP-712 messages, composes withdrawal transactions, signs and submits through a
registered wallet provider, reads balances and history, and estimates fees, on EVM chains and
Solana.

## Installation

> **Beta.** `5.0.0-beta.2` is the current public beta. Pin the exact version, as below, and don't use
> a range or `+`: Maven has no pre-release flag, so a range picks up every later beta. A beta may
> still change API between versions (see [Compatibility](#compatibility)).

The modules are on Maven Central; `google()` and `mavenCentral()`, the two repositories a new
Android project already declares, resolve them and everything they depend on. Pick the module for
the wallet you use:

```kotlin
dependencies {
    // The Rain wallet.
    implementation("xyz.rain:rain-wallet-android:5.0.0-beta.2")
    // Turnkey, bring-your-own.
    // implementation("xyz.rain:rain-turnkey-android:5.0.0-beta.2")
    // Portal MPC.
    // implementation("xyz.rain:rain-portal-android:5.0.0-beta.2")
    // Privy embedded wallets.
    // implementation("xyz.rain:rain-privy-android:5.0.0-beta.2")
    // Your own wallet or signer: core alone.
    // implementation("xyz.rain:rain-core-android:5.0.0-beta.2")
}
```

Each adapter depends on core, so one line is enough. `rain-wallet-android` and
`rain-turnkey-android` cannot share one `RainSdk`; the `build()` row under
[RainSdk.Builder](docs/METHODS.md#rainsdkbuilder) in docs/METHODS.md has the rule.

All Rain modules share one version number; mixing versions in one app is not supported.

| Module        | Contains                                                                 |
|---------------|--------------------------------------------------------------------------|
| [`rain-core-android`](rain-core-android/README.md)   | The `WalletProvider` port, capability model, provider registry, and all Rain domain logic. No wallet vendor SDK. |
| [`rain-turnkey-android`](rain-turnkey-android/README.md) | The Turnkey adapter (`TurnkeyProvider`, `com.rain.sdk.turnkey`); depends on `rain-core-android` + the Turnkey Kotlin SDK. |
| [`rain-wallet-android`](rain-wallet-android/README.md) | The Rain wallet (`RainProvider`, `com.rain.sdk.wallet`): SDK-owned login by code or passkey, provisioning, sessions and key export under Rain's names; depends on `rain-core-android` + `rain-turnkey-android`. |
| [`rain-portal-android`](rain-portal-android/README.md) | The Portal MPC adapter (`PortalProvider`); depends on `rain-core-android` + `portal-android`. |
| [`rain-privy-android`](rain-privy-android/README.md)  | The Privy embedded-key adapter (`PrivyProvider`); depends on `rain-core-android` + `privy-core`. |

## Requirements

- minSdk 28 or higher
- compileSdk 36 or higher
- Kotlin 2.1 or higher, 2.2 recommended (the SDK is built with 2.2.21 and ships Java 11 bytecode)
- Android Gradle Plugin 8.11 or higher (tested with 8.11.2 and 9.4.1)
- JDK 24 to run JVM unit tests that load Turnkey's code through `rain-turnkey-android` or
  `rain-wallet-android`; app builds are unaffected.
  [Requirements](docs/TURNKEY_SUPPORT.md#requirements) in docs/TURNKEY_SUPPORT.md says why.

## Compatibility

From `5.0.0` on, nothing outside the three opt-in markers below breaks before `6.0.0`. Until then
an API can still change between betas, so an app built on one beta may need a migration on
each later beta:

- Rain avoids breaking changes. When one can't be avoided, the [CHANGELOG](CHANGELOG.md) lists it as
  a **Breaking:** entry with a migration note.
- A deprecated API stays for at least one release before it is removed.
- `RAIN_xxx` error codes keep their meaning. Message text may change, so branch on the code or the
  error type, never on the message.
- The three opt-in markers carry no compatibility guarantee, so code that opts in to them may break
  in any release. `@ExperimentalRainApi` marks API that may still change; `@RainAdapterApi` and
  `@InternalRainTurnkeyApi` mark code that Rain's own modules share. Implementing `WalletProvider`,
  `ProviderDescriptor` or `RainClient` requires opting in to `@ExperimentalRainApi`, because Rain
  may add members to them.

## Quick start

The snippets below are the shortest path. Configuration and sessions are in the module READMEs;
the methods are in [docs/METHODS.md](docs/METHODS.md).

### 1. Initialize with Portal (full wallet flow)

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

// Resolve the Portal-backed client (suspending: resolves the wallet on first access).
val client = rain.provider(ProviderId.PORTAL)
```

[rain-portal-android/README.md](rain-portal-android/README.md) has the configuration and the sessions.

### 2. Initialize with Turnkey (bring your own auth)

**Bring-your-own** (the public Turnkey integration): drive Turnkey's Kotlin SDK yourself
(passkeys / auth proxy / OAuth / OTP). Then hand the authenticated `TurnkeyContext` to Rain via
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

[rain-turnkey-android/README.md](rain-turnkey-android/README.md) has the capabilities and the two
adapter-only error rows, and [docs/TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md) is the full guide:
configuration, chains, sessions and errors. The managed mode behind `@InternalRainTurnkeyApi`
reaches hosts as the Rain wallet,
`RainProvider(application, RainWalletConfig(authTokenProvider = ..., environment = RainWalletEnvironment.Sandbox))`
in [rain-wallet-android/README.md](rain-wallet-android/README.md); internals:
[Managed mode (internal API)](docs/TURNKEY_SUPPORT.md#managed-mode-internal-api).

### 3. Bring your own provider, or resolve by capability

[Bring your own provider](rain-core-android/README.md#bring-your-own-provider) in
rain-core-android/README.md has the `WalletProvider` implementation and the error contract.

With several providers registered, resolve by id as above or by capability:

```kotlin
import com.rain.sdk.provider.Capability

// ...or resolve the first registered provider with a given capability
val exporter = rain.first { Capability.EXPORT in it.capabilities }
```

[Capabilities](docs/METHODS.md#capabilities) in docs/METHODS.md says what `first` returns and
where the export methods live.

### 4. Get wallet address

```kotlin
val address = client.getWalletAddress()
```

The [getWalletAddress(chainId)](docs/METHODS.md#getwalletaddresschainid) row in docs/METHODS.md
has the per-chain overload.

### 5. Read balances

```kotlin
import com.rain.sdk.models.Balance
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo

// Native token balance (e.g. AVAX): exact rawAmount plus resolved decimals/symbol/name
val native = client.getBalance(chainId = 43114, token = Token.Native)
println("${native.formatted} ${native.symbol}") // e.g. "1.5 AVAX"

// ERC-20 balance by contract address; the SDK resolves decimals and symbol.
val usdc = client.getBalance(chainId = 43114, token = Token.Contract("0x..."))

// The native balance plus the contract tokens the provider lists
val balances: List<Balance> = client.getTokenBalances(chainId = 43114)

// Every configured chain, flattened into one list; each Balance carries its own chainId
val all: List<Balance> = client.getAllBalances()

// Register a token the SDK cannot discover on chain; the registerTokens rows in docs/METHODS.md have the rules.
client.registerTokens(
    listOf(TokenInfo(chainId = 43114, address = "0x...", symbol = "FOO", decimals = 18))
)
```

The [Balance value type](docs/METHODS.md#balance-value-type) row in docs/METHODS.md lists the
fields, and [getTokenBalances](docs/METHODS.md#gettokenbalanceschainid) says which tokens each
provider lists.

### 6. Send tokens

```kotlin
import java.math.BigDecimal

// Send native token (ETH on Base).
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

The [sendNative](docs/METHODS.md#sendnativechainid-to-amount) and
[sendToken](docs/METHODS.md#sendtokenchainid-contractaddress-to-amount-decimals) rows in
docs/METHODS.md have the chain gating and the `decimals` rule.

### 7. Rain API: collateral contracts and admin signature (host responsibility)

The SDK does not call the Rain issuing API. The two things a withdrawal needs from Rain come from
your backend, which holds the program Api-Key and sends it as the `Api-Key` request header on every
call it makes on the user's behalf:

- `GET /v1/issuing/users/{userId}/contracts` returns the user's collateral contracts. From the one
  on your chain, take `chainId`, `proxyAddress`, `controllerAddress`, `depositAddress` (Solana), `tokens[].address` and, for
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
    depositAddress = contract.depositAddress,
)
val adminSignature = RainAdminSignature(
    salt = response.signature.salt,      // base64, 32 bytes
    signature = response.signature.data, // EVM: 0x-hex, 65 bytes; Solana: base64, 64 bytes
    expiresAt = response.expiresAt,      // unix seconds, or ISO-8601 with Z or a numeric offset
)
```

Token `name`, `symbol` and `decimals` are not on the wire; resolve them with
`rain.tokenMetadata(chainId, address)`, whose
[row](docs/METHODS.md#tokenmetadatachainid-int-address-string-tokeninfo) in docs/METHODS.md has
the resolution order and the `null` rule.

Keep the program Api-Key on your server. The demo app takes it as on-device input only because it has no
backend; its reference client is `app/src/main/java/com/rain/sdk/sample/RainApiClient.kt`.

### 8. Withdraw collateral

`RainWithdrawAddresses` and `RainAdminSignature` come from the Rain API responses your backend
fetches, as section 7 shows; the
[withdrawCollateral](docs/METHODS.md#withdrawcollateralchainid-addresses-amount-decimals-adminsignature-nonce)
row in docs/METHODS.md has the rules.

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

Solana uses the same `RainClient` methods as EVM; the SDK routes on the chain ID, and `RainChain`
holds the sentinel IDs ([Configuration](rain-core-android/README.md#configuration) in
rain-core-android/README.md).

```kotlin
val client = rain.provider(ProviderId.TURNKEY)   // or PRIVY; the Portal adapter uses no Solana account
val chainId = RainChain.SOLANA_DEVNET

client.getWalletAddress(chainId)                 // the Solana account, not the EVM address
client.getBalance(chainId, Token.Native)         // SOL
client.getTokenBalances(chainId)                 // SPL holdings
client.sendNative(chainId, recipientBase58, BigDecimal("0.01"))
client.sendToken(chainId, mintAddress, recipientBase58, BigDecimal("1.5"))
```

The [withdrawCollateral](docs/METHODS.md#withdrawcollateralchainid-addresses-amount-decimals-adminsignature-nonce)
and [sendToken](docs/METHODS.md#sendtokenchainid-contractaddress-to-amount-decimals) rows in
docs/METHODS.md have the Solana rules, `decimals` included, and
[Solana notes](docs/TURNKEY_SUPPORT.md#solana-notes) in docs/TURNKEY_SUPPORT.md has the mechanism.

### 10. Transaction history

```kotlin
import com.rain.sdk.models.RainTransactionOrder

val transactions = client.getTransactions(
    chainId = 43114,
    limit = 20,
    offset = 0,
    order = RainTransactionOrder.DESC
)

transactions.forEach { tx ->
    println("${tx.hash} - ${tx.from} -> ${tx.to}: ${tx.value}")
}
```

The [getTransactions](docs/METHODS.md#gettransactionschainid-limit-offset-order) row in
docs/METHODS.md says where each provider's history comes from.

### 11. Auth Pull: approve the collateral contract

The SDK's part of Auth Pull is the ERC-20 allowance for the user's collateral contract; the pull
itself is Rain's. [docs/AUTH_PULL.md](docs/AUTH_PULL.md) is the full guide.

```kotlin
val rain = RainSdk.builder()
    .rpcEndpoints(rpcEndpoints)
    .authPullConfig(RainAuthPullConfig.sandbox())
    .register(provider)
    .build()
val client = rain.provider(provider.id)

// Unlimited by default. A hash means submitted, not ready: confirm reads the allowance back.
val result = client.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdcAddress, contract.proxyAddress)
val confirmed = client.confirmTokenAllowance(
    result.transactionHash, RainChain.BASE_SEPOLIA, usdcAddress, contract.proxyAddress
)
```

### 12. QR code generation

```kotlin
val bitmap = client.generateAddressQRCode(dimension = 256)
// Use the bitmap in an ImageView
imageView.setImageBitmap(bitmap)
```

The [generateAddressQRCode](docs/METHODS.md#generateaddressqrcodeaddress-dimension) row in
docs/METHODS.md has the parameters.

## Documentation

- [docs/METHODS.md](docs/METHODS.md): the method reference and the Errors table.
- [docs/TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md): the Turnkey guide.
- [docs/AUTH_PULL.md](docs/AUTH_PULL.md): the Auth Pull guide.
- [app/README.md](app/README.md): the sample app.
- [CHANGELOG.md](CHANGELOG.md): changes and known issues, newest first.
- [CONTRIBUTING.md](CONTRIBUTING.md): build, test, CI and the PR checklist.

## License

Apache License 2.0. See the [LICENSE](LICENSE) file for details.
