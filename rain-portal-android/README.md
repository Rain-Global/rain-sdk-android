# rain-portal-android

The [Portal](https://portalhq.io) MPC adapter for the modular Rain Android SDK: `PortalProvider`, in
package `com.rain.sdk.portal`, the `ProviderDescriptor` for Portal's MPC signer. The host obtains the
Portal session token; the connected MPC wallet signs and sends. Depends on `rain-core-android` and
`io.portalhq.android:portal-android`; [Installation](../README.md#installation) in README.md has the
dependency line.

## Quickstart

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

## Capabilities and chains

Advertised capabilities: `EXPORT`, `RECOVERY`. Portal signs eip155 chains only; the adapter uses no
Solana account. Sends, signatures and fee estimates refuse a Solana chain id before the vendor is called
(the `RAIN_102` row under [Errors](#errors)). A balance or history read on one is not
refused: it reaches Portal and fails with Portal's answer, `RAIN_501`. Which tokens
`getTokenBalances` lists is in its provider table in
[docs/METHODS.md](../docs/METHODS.md#gettokenbalanceschainid). Transaction history
(`getTransactions`) comes from Portal's own transactions API, asked for the chain as
`eip155:<chainId>`. A token row's symbol, and its decimals when a row carries a raw value but
neither a parsed value nor a decimal, are read from the chain through Portal.

On a chain where the Portal environment uses account abstraction, Portal answers a send with a
UserOperation hash, which no node can look up. The adapter resolves it on every send: in up to 20
checks a second apart it looks the hash up as a transaction and scans the three canonical EntryPoint contracts
(v0.6 to v0.8) for its `UserOperationEvent`. A mined operation returns the hash of the transaction
it was bundled in. One whose `success` flag is false was included and reverted on-chain: the send
throws `RainError.TransactionSimulationFailed` (`RAIN_403`) with the hash in its message, not on
`transactionId`. The withdrawal flows rewrap it as
`RAIN_405`; [Bring your own provider](../rain-core-android/README.md#bring-your-own-provider) in
rain-core-android/README.md has the rule. When the
window expires the send throws `RainError.TransactionPending` (`RAIN_302`) with `statusId` set to
the UserOperation hash. The bundler has it and it will most likely still mine: re-read the state
(an allowance with `getTokenAllowance`) rather than resend. A chain without account abstraction
resolves on the first check. The scan's lower bound is a block-number read made before the submit,
three attempts. When that read fails there is no window: a hash the chain already knows is
returned, and anything else is `TransactionPending` with the UserOperation hash at once. The Portal
row under [Provider support](../docs/AUTH_PULL.md#provider-support) in docs/AUTH_PULL.md has the
Auth Pull rules.

## Session expiry and retry

Portal exposes no auth state, no token expiry and no way to swap the token on a live client, so
the adapter hardens reactively, controlled by `PortalSessionPolicy`:

```kotlin
PortalProvider(
    PortalConfig(
        sessionToken = sessionToken,
        sessionPolicy = PortalSessionPolicy(
            autoRefresh = true,          // re-mint and retry once on a rejected token
            maxTransientRetries = 2,     // backoff retries for HTTP 5xx/429/408 and network I/O on reads
            initialRetryDelayMs = 500,   // first backoff delay; doubles per retry
            maxRetryDelayMs = 4_000,     // backoff ceiling
        ),
        onSessionTokenNeeded = {
            // Portal rejected the token: return a fresh token for the SAME Portal client, or null
            // or blank to decline. Runs under the refresh lock; never call back into this provider
            // from here.
            backend.mintPortalSessionToken(userId)
        },
        onSessionExpired = {
            // No fresh token could be installed. Fired once per session death, on the calling
            // coroutine's thread: hop to the main thread before touching UI, then restart
            // authentication from here.
        },
    ),
    onPortalCreated = { portal -> /* Portal-only APIs; re-fired on every client rebuild */ },
)
```

Out-of-range policy values throw `IllegalArgumentException` at construction.

- **Auth classification and refresh.** An HTTP 401 from Portal's API, or an MPC result error
  carrying id 320 or `INVALID_API_KEY`, is a rejected token. Portal rejects a bad token before
  executing, so the retry after a re-mint is safe for sends too. Refreshes are single-flighted, MPC
  shares survive the client rebuild, and a token known to be dead is re-minted first or fails fast.
- **Transient backoff.** Reads only; sends and signing are retried once at most, after a token
  refresh.
- **Re-auth hook.** `onSessionExpired` re-arms after a successful install. With `autoRefresh =
  false`, or without `onSessionTokenNeeded`, a rejected token is not re-minted (the `RAIN_201` row
  under [Errors](#errors)).

`sessionState`, a `StateFlow<PortalSessionState>`, and `currentSessionState()` report `Unknown`
(before any call completes), `Active` (the last call or refresh succeeded), `Refreshing` (a
re-minted token being installed) and `Expired` (a rejected token with nothing fresh installed).
Call outcomes and refreshes drive it, since Portal offers nothing to watch passively, so it lags
until a call runs.

`refreshSession()` re-mints through `onSessionTokenNeeded` and rebuilds the client, whether or not
`autoRefresh` is on; the installed token is not checked until the next call. `updateSessionToken(sessionToken)` rebuilds it around a token the host minted
for the same Portal client (a blank one is an `IllegalArgumentException`). `refreshSession()`
throws `RainError.TokenExpired`, with the hook fired, when no fresh token is minted
(`onSessionTokenNeeded` unset, a null or blank return, or a throw) or the client cannot be rebuilt
around it. `updateSessionToken` throws the same when the rebuild fails. Before the provider is
resolved both throw `RainError.SdkNotInitialized` (`RAIN_101`), and after `close()` every call does.
`close()` is terminal; build a new `PortalProvider` to use Portal again.

Reference: the sample's `app/src/main/java/com/rain/sdk/sample/RainSession.kt`,
`WalletSessionStatus.kt` and `screens/HomeScreen.kt` (`SessionCard`).

## Portal construction

The adapter constructs the vendor `Portal` with `autoApprove = PortalConfig.autoApprove`,
`FeatureFlags(isMultiBackupEnabled = true)` and an `eip155:<chainId>` to `rpcUrl` RPC config, and
passes no backup storage, since portal-android registers it at backup-call time. `PortalConfig`,
and the descriptor's second constructor argument:

| Parameter | Rules |
|---|---|
| `sessionToken: String` | A Portal session token (Portal API key), checked against Portal's API when the provider resolves. |
| `chainId: Int? = null` | Portal's `legacyEthChainId`, the default chain for its legacy single-chain operations; omitted, the adapter falls back to Avalanche mainnet when configured, else the first configured chain. |
| `sessionPolicy`, `onSessionTokenNeeded`, `onSessionExpired` | The session policy and hooks: [Session expiry and retry](#session-expiry-and-retry). |
| `autoApprove: Boolean = true` | With it on, the adapter answers `PortalEvents.PortalSigningRequested` with `PortalSigningApproved` on the `Portal` instance, so every signing request on it is auto-approved, the host's own through `onPortalCreated` included. Portal raises no approval UI of its own, so an unanswered request hangs. Pass `false` only if the host gates signing itself, and then answer the event on the `onPortalCreated` instance. |
| `onPortalCreated: ((Portal) -> Unit)? = null` | The vendor `Portal` instance each time the adapter creates one, for Portal-only APIs such as wallet generation, backup and recovery; re-invoked after every token refresh or `updateSessionToken` install, each of which rebuilds the client. The sample's `ensurePortalWallet` in `app/src/main/java/com/rain/sdk/sample/RainSession.kt` runs MPC key generation that way. |

## Errors

Conditions this adapter maps itself; the Errors table in [docs/METHODS.md](../docs/METHODS.md#errors)
has the meanings.

| Condition | Code |
|---|---|
| A rejected token not re-minted: the re-mint under [Session expiry and retry](#session-expiry-and-retry) fails or is declined, `autoRefresh` is off, `onSessionTokenNeeded` is unset, or the re-minted token is rejected too; `onSessionExpired` fires. Also a token Portal rejects on the resolution probe: `rain.provider(ProviderId.PORTAL)` throws it with no re-mint and no hook, and `sessionState` stays `Unknown` | `RainError.TokenExpired` (`RAIN_201`) |
| A blank session token, at resolution | `RainError.Unauthorized` (`RAIN_202`) |
| A Solana chain id on a send, a signature or a fee estimate | `RainError.InvalidConfig` (`RAIN_102`) |
| Portal returns no EIP-155 wallet address | `RainError.WalletUnavailable` (`RAIN_404`) |
| JSON-RPC error code 3 or a node message naming an execution failure on a send or a fee estimate; a UserOperation whose `success` flag is false. Any failure of the `eth_call` preflight before a send that is neither a rejected token nor transient, an insufficient-funds answer included | `RainError.TransactionSimulationFailed` (`RAIN_403`) |
| A transport failure or an HTTP 408, 429 or 5xx answer on the resolution probe or the `eth_call` preflight before a send, or on a read or a fee estimate once the transient retries are spent | `RainError.NetworkError` (`RAIN_301`) |
| A failure whose text names a user rejection or carries EIP-1193 code 4001, on a signature, a fee estimate or a send after its preflight | `RainError.UserRejected` (`RAIN_401`) |
| A failure whose text names a funds shortfall, on a signature, a fee estimate or a send after its preflight; `required`, `available` and `currency` are null | `RainError.InsufficientFunds` (`RAIN_402`) |
| Anything else Portal raises. A revert on a read, which is not a failed simulation. A transport failure or an HTTP 408, 429 or 5xx on a send or a signature. The submit itself, after its preflight, and a signature never answer `RAIN_301` or `RAIN_305`: from a send this can be a lost answer after the submit, so read history or the balance before sending again | `RainError.ProviderError` (`RAIN_501`) |

## Notes

- A `Duplicate class org.bouncycastle...` build error:
  [Bouncy Castle dependency conflict](../docs/TURNKEY_SUPPORT.md#bouncy-castle-dependency-conflict-downstream-consumers)
  in docs/TURNKEY_SUPPORT.md has the exclusion to add.
