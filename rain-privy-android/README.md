# rain-privy-android

The [Privy](https://privy.io) embedded-wallet adapter for the modular Rain Android SDK:
`PrivyProvider`, in package `com.rain.sdk.privy`, the `ProviderDescriptor` for Privy's embedded-key
signer. The host authenticates the `Privy` singleton and hands it in; the embedded EVM and Solana
wallets hold custody. Depends on `rain-core-android` and `io.privy:privy-core`, and linking this
module is all a Privy app needs; [Installation](../README.md#installation) in README.md has the
dependency line.

## Quickstart

Privy authentication and `Privy.init` happen outside Rain, through the official Privy Android
SDK; the sample's `app/src/main/java/com/rain/sdk/sample/PrivyAuthSample.kt`
is reference code for that side.

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.privy.PrivyConfig
import com.rain.sdk.privy.PrivyProvider
import com.rain.sdk.provider.ProviderId

// Host app: Privy.init(...) in Application.onCreate(), log the user in, user.createEthereumWallet().
val rain = RainSdk.builder()
    .rpcEndpoints(
        mapOf(
            8453 to "https://mainnet.base.org",
            84532 to "https://sepolia.base.org"
        )
    )
    .register(PrivyProvider(PrivyConfig(privy = privy)))
    .build()

// Resolve the Privy-backed client (suspending; with no walletAddress it probes for an embedded Ethereum wallet).
val client = rain.provider(ProviderId.PRIVY)
```

## Capabilities and chains

Advertised capabilities: `EXPORT`, `RECOVERY`, `MULTI_CHAIN`; no `GAS_SPONSORSHIP`, sends are
self-paid.

`walletAddress` picks an explicit embedded Ethereum wallet, null the user's first; Solana always
uses the user's first embedded Solana wallet. Resolving probes for an embedded Ethereum wallet,
even for Solana-only use, unless `walletAddress` is set. Then the override is returned as given,
without a Privy wallet lookup, and a missing or mismatched wallet fails the first EVM signature,
send or history read. On Solana, core composes the transfer and
the wallet's ed25519 key signs it; an on-device wallet broadcasts through Rain's configured RPC, a
TEE wallet through Privy's backend, and this wallet always pays the fee. Which tokens `getTokenBalances` lists is in its provider table in
[docs/METHODS.md](../docs/METHODS.md#gettokenbalanceschainid). History comes from Privy's
transaction indexer, for TEE wallets only, on Ethereum, Sepolia, Optimism, BNB Smart Chain,
Polygon, Base, Base Sepolia, Arbitrum One, Avalanche, Linea and Solana mainnet; other chains
return an empty history, and an on-device wallet's read fails with `RAIN_501`. The indexer is queried for the native asset and per chunk of
up to ten registry and registered tokens, so token rows cover those tokens only. The pages are
merged, deduplicated and sorted by `createdAt`, newest first unless `order` says otherwise; one
query that fails fails the call. [Provider support](../docs/AUTH_PULL.md#provider-support) has
the Auth Pull row.

## Session expiry and retry

Privy's SDK refreshes its session itself ahead of each call and exposes no JWT expiry, so
`PrivySessionPolicy` controls the transient backoff and `PrivyConfig.onSessionExpired` the re-auth
hook:

```kotlin
PrivyProvider(
    PrivyConfig(
        privy = privy,
        sessionPolicy = PrivySessionPolicy(
            maxTransientRetries = 2,     // backoff retries for transient failures on reads
            initialRetryDelayMs = 500,   // first backoff delay; doubles per retry
            maxRetryDelayMs = 4_000,     // backoff ceiling
        ),
        onSessionExpired = {
            // Re-auth hook: the session died (Privy's refresh failed, or the app logged out). Fired once per
            // session death, on the calling coroutine's thread or a watcher thread: hop to the main
            // thread before touching UI, never call back into the SDK synchronously, restart authentication.
        },
    )
)
```

Out-of-range policy values throw `IllegalArgumentException` at construction.

- **Auth classification.** An auth failure that reaches the adapter is terminal (`RAIN_201`). A
  call racing Privy's credential restore at launch waits up to ten seconds; an unverified session
  is verified once per call.
- **Transient backoff.** Idempotent reads (address reads, history) retry a request that got no
  answer, and an HTTP 408 or 429 answer, with exponential backoff. A 5xx is not retried, because
  privy-core 0.15.0 reports it status-less; sends and signing never are.
- **Re-auth hook.** `onSessionExpired` fires through a passive watcher over Privy's auth state,
  even with no Rain call in flight. Cached accounts are evicted with it, so a re-login as another
  user never signs with the old wallets. The hook re-arms when a live session is seen again; only
  a session Privy refuses or has dropped is a death.

`sessionState`, a `Flow<PrivySessionState>`, and `currentSessionState()` report `Loading`
(credentials being restored), `Active`, `Unverified` (a restored session Privy could not verify
yet; it recovers with connectivity) and `Unauthenticated` (no session); there is no `Expired`.
`sessionState` emits whenever Privy's auth state moves to another of the four.

`refreshSession()` forces a Privy session refresh (`PrivyUser.refresh`): no answer is
`RainError.NetworkError` (`RAIN_301`) with the session untouched; the other codes are under
[Errors](#errors). `close()` stops the watcher; call it on a provider you discard.

Reference: the sample's `app/src/main/java/com/rain/sdk/sample/RainSession.kt`,
`WalletSessionStatus.kt` and `screens/HomeScreen.kt` (`SessionCard`).

## Errors

Conditions this adapter maps itself; the Errors table in [docs/METHODS.md](../docs/METHODS.md#errors)
has the meanings.

| Condition | Code |
|---|---|
| Not logged in, or an invalid or expired JWT; a 401 or 403 from Privy's API, whatever the body says. A session Privy dropped while answering a call or a `refreshSession()`, except a send whose answer was lost (`RAIN_305`) | `RainError.TokenExpired` (`RAIN_201`) |
| No embedded Ethereum wallet at resolution, none matching `walletAddress`, no embedded Solana wallet on a Solana call, or a failed wallet creation | `RainError.WalletUnavailable` (`RAIN_404`) |
| A request that got no answer, on a read, a signature or a refresh. A send that never left the device (the device offline, no DNS answer, no route, a refused connection or a connect timeout). The send cases apply on Privy's server-wallet stack and to an on-device Solana send, which broadcasts through the vendor's HTTP client. An on-device EVM send reports every failure as prose the SDK cannot read, so it lands under `RAIN_501`. An RPC answer that is not JSON, on a fee estimate, an EVM balance read or the preflight before an EVM send. | `RainError.NetworkError` (`RAIN_301`) |
| A send whose answer was lost after it left the device: a request made for the send got no answer, or none the vendor could read. The cases are a timeout, a reset, a 5xx, an unreadable answer, and a resend the HTTP client made after a reset that then could not connect. That request is the send's own, or the signing-key fetch Privy runs before a send when it holds no fresh key, on its server-wallet stack. The session refresh it also runs there is a call of its own, made first, so a refresh that gets no answer is `RAIN_301`. | `RainError.TransactionOutcomeUnknown` (`RAIN_305`) |
| A node error naming a funds shortfall on a fee estimate, an EVM balance read or the preflight before an EVM send (`insufficient funds`, `EVM error: OutOfFunds`). A refusal from Privy's wallet API (a 4xx) or a Privy exception whose text names one. `required`, `available` and `currency` are null for these cases; a Solana `sendNative` or `sendToken` shortfall comes from core's preflight, which fills them | `RainError.InsufficientFunds` (`RAIN_402`) |
| A failure whose text names a user rejection or carries EIP-1193 code 4001 | `RainError.UserRejected` (`RAIN_401`) |
| A refusal Privy's wallet API answered (a 4xx) that no row above claims and that names no revert, and anything else the Privy SDK raises, a history read on an on-device wallet among them. A 4xx naming a revert, and a revert on the estimate or the preflight, are `RAIN_403`; the `RAIN_403` row in docs/METHODS.md has the rule. For an EVM send on Privy's on-device stack a `RAIN_501` may still be an unknown fate; the `RAIN_301` row says why. | `RainError.ProviderError` (`RAIN_501`) |
| A node JSON-RPC error on a fee estimate, an EVM balance read or the preflight before an EVM send, unless it is a funds shortfall, or a revert on the estimate or the preflight. An RPC result it cannot read. | `RainError.InternalError` (`RAIN_502`) |
| A Solana chain id on `sendTransaction`, `signTypedData` or `estimateTransactionFee` | `RainError.InvalidConfig` (`RAIN_102`) |

## Notes

- A `Duplicate class org.bouncycastle...` build error: [Bouncy Castle dependency conflict](../docs/TURNKEY_SUPPORT.md#bouncy-castle-dependency-conflict-downstream-consumers) in docs/TURNKEY_SUPPORT.md has the exclusion to add.
