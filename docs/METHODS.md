# Rain SDK for Android — Method Reference

Reference for the Rain SDK public API. The SDK is **modular**: `rain-core-android` carries the
vendor-free port, registry, and domain logic, and no wallet-vendor SDK; each wallet provider ships
as its own adapter module (`rain-turnkey-android`, `rain-portal-android`, `rain-privy-android`), and the Rain wallet ships as `rain-wallet-android` over the Turnkey adapter. You assemble a `RainSdk` with a builder, register the
provider adapters your app ships, then resolve a `RainClient` per provider.

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.portal.PortalConfig
import com.rain.sdk.portal.PortalProvider

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(43114 to "https://avalanche-c-chain-rpc.publicnode.com"))
    .register(PortalProvider(PortalConfig(sessionToken)))
    .build()

val client = rain.provider(ProviderId.PORTAL)   // suspend — RainClient for wallet operations
rain.buildEIP712Message(...)                     // wallet-agnostic — no provider required
```

A `RainClient` is bound to one provider for its lifetime. See [TURNKEY_SUPPORT.md](TURNKEY_SUPPORT.md) for the Turnkey adapter walkthrough.

---

## RainSdk

Entry point. Built via `RainSdk.builder()`; the host registers exactly the provider adapters it
ships and the chains it talks to. Nothing here references a concrete vendor type — a provider whose
module isn't on the classpath simply can't be registered.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `providerIds` | `Set<ProviderId>` | Ids of every provider the host registered. |
| `providers` | `List<ProviderDescriptor>` | The registered provider descriptors in registration order, for capability resolution. |
| `authPullChainIds` | `Set<Int>` | Chains Auth Pull is enabled on for this instance: the configured `RainAuthPullConfig`'s chains intersected with the chains that have an RPC endpoint. Empty when no `authPullConfig(...)` was supplied. Also exposed on `RainClient`; see [authPullChainIds](#authpullchainids). |

### Methods

#### builder(): Builder

Starts a new `Builder`.

#### provider(id): RainClient

Resolves the `RainClient` backed by the provider registered under `id`, materializing the vendor
wallet on first access and caching it thereafter.

- **Returns:** `RainClient` bound to that provider.
- **Throws:** `RainError.ProviderNotRegistered` if no provider was registered for `id`.
- **Suspend:** Yes (materializes the vendor wallet; e.g. Turnkey probes its wallet list).

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | `ProviderId` | The id the provider was registered under (e.g. `ProviderId.PORTAL`). |

#### first(predicate): RainClient

Resolves the first registered provider matching `predicate` (e.g. by capability) and returns its
`RainClient`.

```kotlin
val exporter = rain.first { Capability.EXPORT in it.capabilities }
```

- **Returns:** `RainClient` for the first matching provider.
- **Throws:** `RainError.ProviderNotRegistered` if no registered provider matches.
- **Suspend:** Yes.

| Parameter | Type | Description |
|-----------|------|-------------|
| `predicate` | `(ProviderDescriptor) -> Boolean` | Match tested against each registered provider descriptor. |

#### reset()

Evicts the resolved clients. Idempotent.

The chain configuration is immutable state fixed at `build()`, so this instance stays usable: the
next `provider(id)` / `first { }` call re-resolves the provider from scratch. Build a new `RainSdk`
via `builder()` to change configuration.

- **Suspend:** No

#### close()

Full teardown: `reset()`, then `close()` on every registered provider, so vendor clients and session
watchers stop and no `onSessionExpired` hook can fire again. Idempotent.

Terminal, unlike `reset()`: `provider(id)`, `first { }`, `tokenMetadata` and `registerTokens` throw
`RainError.SdkNotInitialized` (`RAIN_101`) afterwards. Build a new `RainSdk` via `builder()` for the
next login.

- **Suspend:** No

### Rain API (host responsibility)

The SDK does not call the Rain issuing API. Your backend holds the program Api-Key, fetches the
user's collateral contract (`GET /v1/issuing/users/{userId}/contracts`) and the withdrawal
authorization (`GET /v1/issuing/users/{userId}/signatures/withdrawals`), and hands the SDK a
`RainWithdrawAddresses` and a `RainAdminSignature` built from those responses; README section 7
shows the fields. Metadata for the contract's tokens comes from `tokenMetadata` below.

### Token metadata

These methods need no wallet provider, only the configured RPC endpoints.

#### tokenMetadata(chainId: Int, address: String): TokenInfo?

Metadata (`symbol`, `name`, `decimals`) for a token known only by address, such as a collateral
contract's tokens. Resolution order: the built-in registry, host-registered tokens, then on-chain
`decimals()` / `symbol()` / `name()` reads over the chain's RPC endpoint, cached once decimals
resolve. Returns `null` when decimals could not be established (unknown token, failed read, RPC
unreachable), never a guessed default; `symbol` and `name` inside a non-null result may still be
null. Solana chains resolve from the registry and host-registered tokens only. A `null` result is not
cached, so a later call reads the chain again.

- **Throws:** `RainError.InvalidConfig` (`RAIN_102`) when no RPC endpoint was configured for
  `chainId`, when `address` is malformed for its chain family (on EVM chains `0x` followed by 40 hex
  characters with a correct EIP-55 checksum when mixed-case, on Solana chains base58 decoding to 32
  bytes), or when the chain reports `decimals()` outside `0..77`, a token no money path can scale by;
  nothing is cached then. `RainError.SdkNotInitialized` (`RAIN_101`) after `close()`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Numeric chain ID the token lives on; it must have a configured RPC endpoint. |
| `address` | `String` | Token contract address (SPL mint on Solana). |

#### registerTokens(tokens: List\<TokenInfo\>)

Registers token metadata after `build()`, without a resolved provider. The entries are stored before
the call returns, so a `tokenMetadata` call that follows sees them; every resolved client shares the
store. Re-registering a host-added address replaces its entry; a built-in registry token cannot be
overridden.

Three methods register tokens, run the same checks and store into the same shared store in place:
`Builder.registerTokens` for tokens known before `build()`, this method for a token discovered before
any client exists, and `RainClient.registerTokens` on a resolved client.

- **Throws:** `RainError.InvalidConfig` (`RAIN_102`) when an entry's address is malformed for its
  chain family (as above) or its `decimals` lies outside `0..77`. The whole list is validated first,
  so nothing is registered. `RainError.SdkNotInitialized` (`RAIN_101`) after `close()`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `tokens` | `List<TokenInfo>` | Tokens to add to the shared token store; an empty list is a no-op. |

---

## RainSdk.Builder

Assembles a `RainSdk`. Module dependencies decide which providers can be registered — the builder
never names a vendor SDK itself.

| Method | Description |
|--------|-------------|
| `rpcEndpoints(endpoints: Map<Int, String>)` | Sets the `chainId → RPC URL` map every provider shares. **Required.** |
| `rpcEndpoints(configs: List<NetworkConfig>)` | Same, as `NetworkConfig` values (chain id + RPC URL + optional display name). Replaces rather than appends; a later duplicate `chainId` wins. |
| `register(descriptor: ProviderDescriptor)` | Registers a provider adapter (e.g. `PortalProvider`, `TurnkeyProvider`, `RainProvider`). Re-registering the same id replaces the prior descriptor; `build()` closes the replaced instance once the registry is valid, so a failed build leaves it untouched. Registering the same instance twice is a no-op. |
| `registerTokens(tokens: List<TokenInfo>)` | Seeds the shared token store with extra token metadata. Validated at `build()` exactly as `RainSdk.registerTokens` validates: a malformed address or mint, or `decimals` outside `0..77`, fails the build with `RainError.InvalidConfig`. |
| `authPullConfig(config: RainAuthPullConfig)` | Enables Auth Pull for the exact operator and token contracts in `config` (`RainAuthPullConfig.sandbox(...)` / `.production(...)` / `.custom(...)`). Without it, the approval, allowance, confirmation, and approval-fee methods fail closed. See [AUTH_PULL.md](AUTH_PULL.md). |
| `build(): RainSdk` | Validates endpoints (fail-fast on a bad URL / chain id) and returns the SDK. Throws `RainError.InvalidConfig` if no RPC endpoints were configured, a seed token is invalid (see `registerTokens`), or the Auth Pull configuration is invalid: a malformed or zero operator or token address, an empty token map, a chain outside the set its factory names (`sandbox`, `production`, or either set for `custom`), or no RPC endpoint for any configured Auth Pull chain. Also throws `RainError.InvalidConfig` when both the Rain wallet provider and the Turnkey provider are registered: they drive one process-wide wallet backend, so an app uses one or the other. That check is best-effort; a second `RainSdk` or a provider that is never registered can still collide, which the backend reports as `RainError.InvalidConfig` on the first authentication call. |

Registering **zero** providers is allowed: the SDK is then wallet-agnostic, exposing
the transaction-building methods, `tokenMetadata` and `registerTokens`. Resolving `provider(id)` throws
`RainError.ProviderNotRegistered` until a provider is registered.

### Provider adapters

Each adapter is a `ProviderDescriptor` that owns its vendor SDK as a private dependency.

| Adapter | Module | Config | Notes |
|---------|--------|--------|-------|
| `PortalProvider(PortalConfig(sessionToken, chainId?, sessionPolicy?, onSessionTokenNeeded?, onSessionExpired?, autoApprove?), onPortalCreated?)` | `rain-portal-android` | `sessionToken: String`, `chainId: Int?`, `sessionPolicy: PortalSessionPolicy`, `onSessionTokenNeeded: (suspend () -> String?)?`, `onSessionExpired: (() -> Unit)?`, `autoApprove: Boolean = true`; `onPortalCreated: ((Portal) -> Unit)? = null` hands the host the vendor `Portal` instance each time the adapter creates one, for Portal-only APIs such as backup and recovery | Portal MPC signer (EVM). Advertises `EXPORT`, `RECOVERY`.|
| `TurnkeyProvider(TurnkeyConfig(turnkey, walletAddress?, sessionPolicy?, onSessionExpired?, sponsorGas?))` (bring-your-own) or `TurnkeyProvider(TurnkeyConfig(application, organizationId, authProxyConfigId, walletAddress?, sessionPolicy?, onSessionExpired?, sponsorGas?, passkeyDomain?))` (managed — internal API, `@InternalRainTurnkeyApi`) | `rain-turnkey-android` | BYO: `turnkey: TurnkeyContext`. Managed: `application: Application`, `organizationId: String`, `authProxyConfigId: String`, `passkeyDomain: String? = null` (the rules of the Rain wallet's `passkeyDomain`, see [Rain wallet provider](#rain-wallet-provider)). Shared: `walletAddress: String?`, `sessionPolicy: TurnkeySessionPolicy`, `onSessionExpired: (() -> Unit)?`, `sponsorGas: Boolean = true` | Turnkey P256 signer (EVM + Solana). The `walletAddress` override is validated and stored in EIP-55 checksum form at construction (`RAIN_102` for a malformed address or a wrong mixed-case checksum). Advertises `EXPORT` (the recovery phrase and private keys through `exportRecoveryPhrase` / `exportPrivateKey`, see [Turnkey key export](#turnkey-key-export)), `MULTI_CHAIN`, and `GAS_SPONSORSHIP` while `sponsorGas` is on; not `BIOMETRIC_GATE`, since the backend stamps with a device key and no user-verification prompt gates signing. Sends work only on Turnkey's managed-broadcast chains (others throw `RAIN_104`; reads unaffected). Sponsorship is on by default (`sponsorGas = true`): every send goes through Turnkey sponsored, all EVM sends (transfers, withdrawals, approvals, raw sends) via Gas Station and Solana network fees too (fee estimates quote what the wallet would pay itself; rent for a new recipient token account is a separate Turnkey toggle and stays with the sender). Requires sponsorship enabled on the Turnkey organization; pass `sponsorGas = false` on an organization without it, or to have users pay their own gas. Cannot be registered together with the Rain wallet's `RainProvider`, which drives the same process-wide Turnkey singleton. See [TURNKEY_SUPPORT.md](TURNKEY_SUPPORT.md). |
| `RainProvider(application, RainWalletConfig(sessionPolicy?, onSessionExpired?, sponsorGas?, passkeyDomain?))` | `rain-wallet-android` | `application: Application`; `sessionPolicy: RainWalletSessionPolicy`, `onSessionExpired: (() -> Unit)?`, `sponsorGas: Boolean = true`, `passkeyDomain: String? = null` (rules under [Rain wallet provider](#rain-wallet-provider)) | The Rain wallet: SDK-owned one-time-code login (email or SMS) and passkeys, one wallet with Ethereum and Solana accounts, sessions and key export, under Rain's names; the backend identity is embedded, and there is no address override: a Rain wallet holds one Ethereum account, the one the SDK provisions. Advertises `EXPORT`, `MULTI_CHAIN`, and `GAS_SPONSORSHIP` while `sponsorGas` is on; not `BIOMETRIC_GATE`, since the backend stamps with a device key and no user-verification prompt gates signing. Sends work only on the chains the wallet backend broadcasts to (others throw `RAIN_104`; reads unaffected). Cannot be registered together with `TurnkeyProvider`. See [Rain wallet provider](#rain-wallet-provider). |
| `PrivyProvider(PrivyConfig(privy, walletAddress?, sessionPolicy?, onSessionExpired?))` | `rain-privy-android` | `privy: Privy`, `walletAddress: String?`, `sessionPolicy: PrivySessionPolicy`, `onSessionExpired: (() -> Unit)?` | Privy embedded-wallet signer (EVM + Solana). Advertises `EXPORT`, `RECOVERY`, `MULTI_CHAIN`.|

Every adapter also exposes its session on the descriptor: `sessionState` (a flow of the adapter's session
state), `currentSessionState()`, `suspend fun refreshSession()` (a forced refresh; `RAIN_201` when the session
cannot be refreshed; on the Turnkey provider and the Rain wallet a refresh the backend refused also clears the
stored session, see [Session expiry, refresh, and retry](TURNKEY_SUPPORT.md#session-expiry-refresh-and-retry)) and `close()` (stops the session watcher so a discarded provider never fires
`onSessionExpired`; `RainSdk.close()` calls it for every registered descriptor). `PortalProvider` adds
`suspend fun updateSessionToken(sessionToken)`, which installs a new Portal session token on the running
client; its `refreshSession()` and `updateSessionToken()` throw `RainError.SdkNotInitialized` (`RAIN_101`)
before the provider was resolved.

#### Portal construction

The adapter constructs the vendor `Portal` with `autoApprove = PortalConfig.autoApprove`,
`FeatureFlags(isMultiBackupEnabled = true)`, and an `eip155:<chainId> → rpcUrl` RPC config. Three
vendor-shaped details are worth knowing:

- **Storage backends.** portal-android registers backup storage at backup-call time, so the
  adapter passes none at construction.
- **`autoApprove`.** Defaults to `true`: the adapter answers `PortalEvents.PortalSigningRequested` with
  `PortalSigningApproved` on the `Portal` instance, so every signing request on it is auto-approved,
  including ones the host makes directly through the `onPortalCreated` instance. Every Rain call that
  signs is already an explicit, user-initiated SDK call and Portal raises no approval UI of its own, so
  an unanswered request simply hangs. Pass `false` only if the host gates signing itself, and then
  answer the event on the `onPortalCreated` instance.
- **`chainId`.** `PortalConfig.chainId` feeds portal-android's **required** `legacyEthChainId`
  constructor parameter, so the adapter must supply one; the field lets the host pick it instead of
  guessing. Omit it and the adapter falls back to Avalanche mainnet when configured, else the first
  configured chain.

**Bring your own provider:** implement the `WalletProvider` port and a `ProviderDescriptor`
(with your own `ProviderId`, and `@OptIn(ExperimentalRainApi::class)` on each implementing class), then `register(...)` it; only `rain-core-android` is needed, and the root
README's section 3 carries a minimal adapter (descriptor, port, error boundary). Convert your vendor's exceptions to `RainError`
before they leave your adapter, the way Rain's own adapters do in their session coordinators: core
passes a `RainError` through with its code (the withdrawal paths alone rewrap a simulation failure
as `WithdrawalRevertedByNetwork`); anything else it wraps as `ProviderError` after its shared prose
heuristics, with two exceptions. On `estimateGas`, `estimateWithdrawalFee` and the Solana
`withdrawCollateral` / `prepareWithdrawal` paths a raw exception floors at `InternalError`, and the
hooks core calls before it enters a wrapper (the EVM wallet-address read and `requireSendSupport`
on `withdrawCollateral`) are not wrapped at all, so a raw exception there reaches the host as
thrown. A vendor exception that
escapes therefore loses the specific code a host branches on, and an expired session arrives as a
generic error with no re-authentication hook. Core needs no change: the transaction-building
utilities are available regardless of which provider you register.

---

#### Rain wallet provider

`RainProvider` in `rain-wallet-android` (package `com.rain.sdk.wallet`) is the Rain wallet: a `ProviderDescriptor` under which the SDK owns authentication, wallet provisioning, sessions and key export, with no wallet-vendor type or name on its surface. The backend identity (Rain's organization and authentication configuration) is embedded, so `RainProvider(application)` is the whole setup; `RainWalletConfig` tunes behaviour. Construct it, authenticate on it (a one-time code to an email or phone, or, with `RainWalletConfig.passkeyDomain` set, a passkey: `signUpWithPasskey` creates the account and `loginWithPasskey` returns to it), then register it and resolve `rain.provider(ProviderId.RAIN)`. Resolving before a session is live throws `RainError.TokenExpired` (`RAIN_201`). The wallet backend's configuration is one-shot per app launch, applied by the first authentication call: a backend the app configured itself, or another provider in this process configured with a different backend identity, makes every authentication call throw `RainError.InvalidConfig` (`RAIN_102`) until the app relaunches, and a failed backend initialization makes them throw `RainError.InternalError` (`RAIN_502`) until then.

The embedded identity is Rain's production wallet backend; there is no host-facing environment switch. What the module sends and stores: the email address or phone number passed to `sendLoginCode` or `sendContactVerificationCode` goes to the wallet backend, which delivers the code and keys the account on it (on confirm of an attach, the verified contact and its verification token go to the backend's account lookup, which names the account the contact signs in to, and then to its user update); on a passkey sign-up the passkey's attestation, the passkey name `passkey-<unix seconds>` and a temporary session key go to the backend, on a passkey sign-in the passkey's assertion, on `addPasskey` a new passkey's attestation; the session the backend issues stays on the device in storage the backend SDK owns; wallet keys live in the wallet backend and reach the device only through the two export methods. This module sends nothing else. Sends are supported on Ethereum, Optimism, BNB Smart Chain, Polygon, Monad, Tempo, Robinhood Chain, Base and Arbitrum One, each with its test network, and on Solana mainnet and devnet; a send on any other configured chain throws `RainError.ChainNotSupported` (`RAIN_104`) before any network work, while reads work on every chain with an RPC endpoint. In `5.0.0-beta.1` every Solana send fails with `RAIN_501`; see the [CHANGELOG](../CHANGELOG.md).

| Config | Description |
|--------|-------------|
| `RainWalletConfig(sessionPolicy: RainWalletSessionPolicy = RainWalletSessionPolicy(), onSessionExpired: (() -> Unit)? = null, sponsorGas: Boolean = true, passkeyDomain: String? = null)` | No address override: a Rain wallet holds one Ethereum account, the one the SDK provisions on first login, and the SDK signs with it and reads for it; `onSessionExpired` fires once per session death that cannot be refreshed, on any thread, and a deliberate `logout()` never fires it; `sponsorGas` makes every send on a supported chain fee-sponsored, with `GAS_SPONSORSHIP` advertised and fee estimates that quote what the wallet would pay itself; rent for a first-time recipient's Solana token account is a separate backend setting that stays with the sender, and a sponsored send runs no client-side revert preflight, so a failure surfaces as the backend's failed status after broadcast; `passkeyDomain` is a registrable domain of at least two labels the partner controls (`passkeys.example.com`) whose `/.well-known/assetlinks.json` carries both statements of Google's passkey example, one for the site itself and one for the app with its package name and every signing-certificate fingerprint (shape under [Passkeys](TURNKEY_SUPPORT.md#passkeys); the app statement alone is refused at the sheet); null or blank turns `loginWithPasskey`, `signUpWithPasskey` and `addPasskey` off (`RAIN_102` when called), a scheme, port, path or a single label such as `localhost` throws `RAIN_102` from the `RainProvider` constructor, and the domain is permanent, since every passkey created against it stops working when it changes; partners host their own file, Rain runs no shared domain. |
| `RainWalletSessionPolicy(refreshBufferSeconds: Long = 60, autoRefresh: Boolean = true, refreshExpirationSeconds: Long? = null, maxTransientRetries: Int = 2, initialRetryDelayMs: Long = 500, maxRetryDelayMs: Long = 4_000)` | Expiry, refresh and retry behaviour for the session guarding every wallet call; `refreshExpirationSeconds` is the lifetime requested for refreshed sessions, null for the backend default of 900. Out-of-range values throw `IllegalArgumentException` at construction: a programming error, not a runtime failure. |

| Member | Signature | Notes |
|--------|-----------|-------|
| `id` / `capabilities` | `ProviderId.RAIN` / `Set<Capability>` | `EXPORT`, `MULTI_CHAIN`, plus `GAS_SPONSORSHIP` while `sponsorGas` is on; never `BIOMETRIC_GATE`, because no biometric prompt gates signing (gate export yourself). |
| `awaitSessionRestore` | `suspend fun awaitSessionRestore(timeoutMs: Long = 5_000)` | Waits for the asynchronous restore of a persisted session; a timeout returns normally with `authState` at `Loading`. As the first authentication call of a launch it also runs the backend's one-shot initialization, which `timeoutMs` does not bound. `RAIN_102` on a configuration conflict; `RAIN_502` when initialization failed. |
| `hasActiveSession` | `fun hasActiveSession(): Boolean` | True when a live session has more than 30 seconds left, so the code step can be skipped. The answer comes from the stored session, and a session the backend refuses to refresh is cleared with that failure, so this reads false from then on; `refreshSession()` is the call that asks, see its row. In `5.0.0-beta.1` a session revoked by a login on another device kept reading true until its own expiry passed, see the [CHANGELOG](../CHANGELOG.md). False until the first authentication call has configured the backend. |
| `authState` / `currentAuthState()` | `val authState: Flow<RainWalletAuthState>` / `fun currentAuthState(): RainWalletAuthState` | `Loading` until the first authentication call has configured the backend and its restore settled, `Authenticated` while a session is live, `Unauthenticated` otherwise. A `when` over it is exhaustive; a new state is a breaking change shipped in a major version. |
| `sendLoginCode` | `suspend fun sendLoginCode(contact: RainWalletContact)` | Sends a one-time code to a `RainWalletContact.Email` or `.Phone`; touches no existing session. Calling it again for the same contact replaces the pending code (codes expire after 5 minutes, lock after 3 wrong attempts, at most 3 active per user); another contact or channel retires the pending code first. An email is trimmed and lowercased, since the backend matches it case-sensitively; a phone number is trimmed, stripped of spaces, dots, hyphens and parentheses, and must then be E.164 (`+`, country code and number, at most 15 digits); a parenthesised trunk zero is refused. `RAIN_102` for a blank email, a phone number outside E.164, a configuration conflict, or a closed provider; `RAIN_502` when the backend's initialization failed for this launch. |
| `confirmLoginCode` | `suspend fun confirmLoginCode(code: String)` | Sign-up or login. A first login creates one wallet holding an Ethereum and a Solana account inside the sign-up request; a returning login backfills a missing account. A rejected code throws `RainError.InvalidLoginCode` (`RAIN_203`) and keeps the challenge for a retry; a rejection the backend wraps in an HTTP 500 arrives as `RainError.ProviderError` (`RAIN_501`) with the challenge kept too, so treat it the same way. A failure after the code was accepted drops the challenge; if it is the switch to the new session the device is signed out without firing `onSessionExpired`, while a provisioning failure keeps the new session and heals at resolution. A successful login revokes the user's other sessions on every device; the signed-out device's hook fires at its next call. `RAIN_102` when no code was requested, the code is blank, or the provider was closed; `RAIN_502` when the backend's initialization failed for this launch. |
| `logout` | `suspend fun logout()` | Clears the stored session, after a restore in flight settles, without firing `onSessionExpired`; a pending login code and a pending contact verification are dropped. `hasActiveSession()` and `currentAuthState()` read unauthenticated as soon as it returns. No-op when no session is selected, but it runs the same readiness checks as every authentication call: `RAIN_102` on a closed provider or a configuration conflict, `RAIN_502` when the backend's initialization failed. |
| `loginWithPasskey` | `suspend fun loginWithPasskey(activity: Activity)` | Signs an existing user in with a passkey bound to `RainWalletConfig.passkeyDomain`, through the system passkey sheet presented from `activity`; the call suspends until the sheet closes, and cancelling the caller ends it with the cancellation, never a mapped error. The same session handling as `confirmLoginCode`: a fresh key, an explicit select over a live session, the previous session cleared, the user's other sessions revoked, a missing account backfilled. A dismissed sheet or no passkey for the domain throws `RainError.UserRejected` (`RAIN_401`) and leaves the current session untouched; no `passkeyDomain` throws `RainError.InvalidConfig` (`RAIN_102`) before anything is sent, and so does an association file that does not vouch for this build; any other refusal by the device or the backend, an HTTP status inside the ceremony included (no session exists yet, so it is never `RAIN_201` or `RAIN_202`), is `RainError.ProviderError` (`RAIN_501`); after a successful ceremony the session switch and the account backfill can still throw `RAIN_201` or `RAIN_202` (a backfill failure keeps the session, which heals at resolution); `RAIN_102` on a closed provider or a configuration conflict; `RAIN_502` when the backend's initialization failed or the backend answered the ceremony with an unusable response. |
| `signUpWithPasskey` | `suspend fun signUpWithPasskey(activity: Activity)` | Creates a new account with a passkey as its only login method and one wallet holding an Ethereum and a Solana account inside the same request, then signs it in as `loginWithPasskey` does. Every call mints a fresh account: returning users sign in or add a passkey, and accounts never merge. Refused with `RainError.InvalidConfig` (`RAIN_102`) while a live session is stored as this device's current one (an expired one is cleared first, with `onSessionExpired` silent, and the sign-up proceeds), so call `logout()` first when `hasActiveSession()` is true; the wallet backend's process-wide client is replaced for the whole ceremony, and a wallet call made meanwhile would read the live session as dead. If the sign-up request succeeded but the login that followed failed, the account exists and `loginWithPasskey` reaches it; if the sign-up request itself failed, no account exists and the passkey the sheet created signs into nothing. Both arrive as `RAIN_501`. `exportRecoveryPhrase` is the backup path for a passkey-only account; `sendContactVerificationCode` is meant to add an email or phone as a second login (what an attach refuses and what it replaces today is in the `confirmContactVerification` row below). Codes as for `loginWithPasskey`. |
| `addPasskey` | `suspend fun addPasskey(activity: Activity)` | Registers a passkey bound to `RainWalletConfig.passkeyDomain` on the signed-in account, so the next sign-in can use it. The session is checked (and refreshed when near its expiry) before the sheet, so `RainError.TokenExpired` (`RAIN_201`) fires before any biometric prompt; a session revoked server-side between that check and the registration still surfaces as `RAIN_201` after the prompt, with `onSessionExpired` firing, and a registration the backend refused leaves a passkey on the device that signs into nothing. The passkey request asks the credential provider for user verification as preferred, not required, so gate the call as you gate export. No new account and no session change; other authentication calls wait while the sheet is open. Each call registers another passkey. A dismissed sheet is `RAIN_401`; the backend refusing the registration is `RainError.Unauthorized` (`RAIN_202`); a ceremony the device refused, or a registration the backend failed (its per-user limit included), is `RAIN_501`; `RAIN_102` without a `passkeyDomain`, on an association file that does not vouch for this build, on a closed provider or a configuration conflict; `RAIN_502` when the backend's initialization failed. |
| `sendContactVerificationCode` | `suspend fun sendContactVerificationCode(contact: RainWalletContact)` | Sends a verification code to a contact the signed-in user wants to attach to this account as a login method; distinct from `sendLoginCode`, which starts a login. Requires a live session (`RAIN_201` before anything is sent). The SDK adds no user-presence check before the attach beyond that session, which can be one restored at launch, so gate the call as you gate export (a biometric prompt or a fresh login) and show the user which contacts sign in to the account; the passkey requests ask for user verification as preferred, not required, so gate `addPasskey` the same way. The contact is canonicalized like a login contact and that string is what the account stores; calling it again for the same contact replaces the pending code, a call for another contact or channel retires it before anything is sent, and a login or a `logout` drops it. Accounts are never merged; what the confirm refuses, with which code, and the known issues of an attach are in the `confirmContactVerification` row below. `RAIN_102` for a blank email, a phone number outside E.164, a closed provider or a configuration conflict; `RAIN_501` when the code request is refused; `RAIN_502` when the backend's initialization failed. |
| `confirmContactVerification` | `suspend fun confirmContactVerification(code: String)` | Confirms the code from `sendContactVerificationCode` and attaches the verified contact. The session is checked before the code is spent (`RAIN_201` otherwise). A rejected code throws `RainError.InvalidLoginCode` (`RAIN_203`) and keeps the challenge; a rejection the backend wraps in an HTTP 500 arrives as `RAIN_501` with the challenge kept too, so treat it the same way. Once the code is accepted, the backend's account lookup names the account the contact signs in to: another account, and the attach is refused with `RAIN_202` and nothing changes; this account or none, and the update runs. A failure after the code was accepted drops the challenge: `RAIN_201` when the session died meanwhile, `RAIN_202` when another account signs in with the contact or the backend refused the update, `RAIN_501` when the lookup or the update failed; request a new code. `RAIN_102` when no code was requested, the code is blank, the provider was closed or on a configuration conflict; `RAIN_502` when the backend's initialization failed. An attach replaces an existing email address or phone number instead of adding a second one, a known issue. In `5.0.0-beta.1` it also succeeded for an email address that already logs in to another account; see the [CHANGELOG](../CHANGELOG.md). |
| `sessionState` / `currentSessionState()` | `val sessionState: Flow<RainWalletSessionState>` / `fun currentSessionState(): RainWalletSessionState` | `Loading`, `Active(expiresAtEpochSeconds: Double)`, `Expired` or `Unauthenticated`; emits on every authentication or session change and when an active session passes its expiry. A `when` over it is exhaustive; a new state is a breaking change shipped in a major version. |
| `refreshSession` | `suspend fun refreshSession()` | Forces a refresh regardless of remaining lifetime, and is the call that tells a revoked session apart from a stored one. `RAIN_201` when the session cannot be refreshed; re-authenticate. When the backend refused the refresh (a login on another device revoked the session), the stored session is cleared before the throw and `onSessionExpired` fires once, so `hasActiveSession()`, `currentAuthState()` and `currentSessionState()` read signed out when it returns; a refresh that failed for another reason (offline, a 5xx) throws the same `RAIN_201` but leaves the session stored. The same happens inside any wallet call that refreshes (`RainWalletSessionPolicy.autoRefresh` on). `RAIN_102` when this provider was closed. |
| `exportRecoveryPhrase` | `suspend fun exportRecoveryPhrase(): String` | The wallet's 12-word BIP-39 phrase, decrypted on the device and returned once; never logged, cached or persisted. One 12-word seed covers both accounts, Ethereum at `m/44'/60'/0'/0/0` and Solana at `m/44'/501'/0'/0'`, so one phrase restores both in any wallet deriving those paths. Needs a live session (`RAIN_201`). `RAIN_202` when the backend refuses the export, `RAIN_404` when the account has no wallet yet, `RAIN_102` when the provider was closed, `RAIN_501` when the export is still pending after about four seconds or its bundle is rejected on the device. A transient failure is retried with a new request. |
| `exportPrivateKey` | `suspend fun exportPrivateKey(account: RainWalletKeyAccount): String` | `ETHEREUM`: the 32-byte secp256k1 key as `0x` plus 64 lowercase hex characters. `SOLANA`: the 64-byte keypair, seed then public key, in plain Base58, the string Solana wallets import. The SDK checks the key is 32 bytes and derives the account's address before returning anything; a failed check is `RAIN_502` with nothing returned. `RAIN_201`, `RAIN_202`, `RAIN_102` and `RAIN_501` as for the phrase, plus `RAIN_501` when the bundle names another account, and `RAIN_404` when the wallet has no account of that family, which a new login provisions, or when that account sits on another curve than the family's. The formats are a cross-platform contract shared by Rain's SDKs. |
| `create` / `close` | `override suspend fun create(context: ProviderContext): WalletProvider` / `override fun close()` | `create` is the registry's hook; it re-checks the account set and probes the Ethereum account, and throws `RAIN_201` before a session is live, `RAIN_102` when the backend was configured outside the SDK or with a different identity or the provider was closed, `RAIN_404` when the account has no Ethereum wallet, and `RAIN_502` when the backend's initialization failed. `close` stops the passive session watcher and makes the provider inert: authentication and export calls throw `RAIN_102`, `currentAuthState()` reads `Unauthenticated`, `hasActiveSession()` is false. |

Host duties after an export are the same as for the Turnkey provider: gate the call, for example behind biometrics, show the value where screenshots and screen recording are blocked, and keep it off the clipboard or clear it. Rain cannot rotate an exported phrase or key for the user: once they have imported it into another wallet, or recovered with it, encourage them to move the funds to a key the export never touched and retire the exported one. Export is the backup path for a user who signs in with a passkey only.

Neutrality: no type from the wallet backend appears in any public signature of `rain-wallet-android`, and a host's compile classpath never carries the backend adapter, because the wallet module depends on it as `implementation`. The backend SDK is still on the host's runtime classpath.

#### Turnkey key export

Public API, on `TurnkeyProvider` in bring-your-own and managed mode alike. It lives on the descriptor because the descriptor exists before resolution, so a host can offer a backup right after login. `rain.first { Capability.EXPORT in it.capabilities }` yields a `RainClient`, which has no export method, so a host that resolves by capability keeps the `TurnkeyProvider` it registered and calls these on it. Every value is decrypted on the device and returned once. The SDK never logs, caches or persists it. Both methods need a live session. In managed mode they also run the one-shot Turnkey configuration when they are the first call of a launch. Which wallet and account each value comes from, timing and retries, the host's duties after the return and the error contract are stated once, under [Key export](TURNKEY_SUPPORT.md#key-export) and [Key export errors](TURNKEY_SUPPORT.md#key-export-errors) in TURNKEY_SUPPORT.md.

| Member | Signature | Notes |
|--------|-----------|-------|
| `exportRecoveryPhrase` | `suspend fun exportRecoveryPhrase(): String` | The wallet's BIP-39 phrase as the wallet was created, space-separated words: 12 for a managed wallet, the creation length for a bring-your-own wallet. Errors: [Key export errors](TURNKEY_SUPPORT.md#key-export-errors). |
| `exportPrivateKey` | `suspend fun exportPrivateKey(family: TurnkeyKeyFamily): String` | `ETHEREUM`: the 32-byte secp256k1 key as `0x` plus 64 lowercase hex characters. `SOLANA`: the 64-byte keypair, the seed followed by the public key, in plain Base58, the string Solana wallets import. Checked against the account's address before it is returned. Errors: [Key export errors](TURNKEY_SUPPORT.md#key-export-errors). |
| `TurnkeyKeyFamily` | `enum class TurnkeyKeyFamily { ETHEREUM, SOLANA }` | Which of the wallet's keys to export. The SDK uses one account per family: the first the organization lists, or for Ethereum the account at `walletAddress`. Families may be added; prefer an `else` branch over an exhaustive `when`. |

#### Turnkey managed authentication

Internal API behind `@InternalRainTurnkeyApi`, the building block of the [Rain wallet provider](#rain-wallet-provider): documented member by member under [Managed mode (internal API)](TURNKEY_SUPPORT.md#managed-mode-internal-api).

## RainClient

Operations Rain exposes against a single, already-resolved wallet provider. Obtained from
`rain.provider(id)` / `rain.first { … }`; bound to one provider for its lifetime, and never references a concrete vendor type.

Money APIs are `BigDecimal`-first.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `providerId` | `ProviderId` | Identifier of the provider backing this client (e.g. `ProviderId.PORTAL`). |
| `capabilities` | `Set<Capability>` | Optional behaviours the backing provider supports (see [Capabilities](#capabilities)). |
| `isInitialized` | `Boolean` | Whether the SDK's chain configuration is set up. |
| `authPullChainIds` | `Set<Int>` | Chains this client will accept an Auth Pull approval on. Empty until `RainSdk.Builder.authPullConfig(...)` supplies the trusted targets; see [authPullChainIds](#authpullchainids). |

---

### withdrawCollateral(chainId, addresses, amount, decimals, adminSignature, nonce)

Full withdrawal flow: builds the transaction, signs via the backing provider, submits, and
returns the transaction hash. Use [prepareWithdrawal](#preparewithdrawalchainid-addresses-amount-decimals-adminsignature-nonce)
to build without broadcasting.

On EVM chains this calls `withdrawAsset` on the Rain coordinator contract (EIP-712 admin
signature + the Rain API signature). On Solana chains it drives Rain's on-chain collateral
program instead: the SDK reads the collateral account (program id, coordinator, nonce) from the
chain, verifies nothing is stale by simulating (skipped when the provider sponsors fees; see the
Capabilities section), and submits a transaction carrying an ed25519
verification of Rain's coordinator signature followed by the program's
`withdraw_single_signer_collateral_asset` instruction. Only single-signer Solana collateral
accounts are supported; the wallet must be the account's owner.

- **Returns:** `String` — the transaction hash (EVM) or transaction signature (Solana).
- **Throws:** `RainError` if construction, signing, or submission fails. A chain the provider cannot
  broadcast on throws `RainError.ChainNotSupported` (`RAIN_104`) before anything is read or signed. A fee-sponsored withdrawal skips the self-paid dry run; a status the provider
  reports with decoded revert details, failed before inclusion or included and reverted, surfaces as
  `WithdrawalRevertedByNetwork` (the transaction hash rides on `transactionId`, and in the cause's message, once included), and a
  failed status without them is `ProviderError`. On Solana, a recipient
  without a token account costs the owner rent, checked up front (`InsufficientFunds`). A raw
  provider failure during the Solana wallet-address read or send passes through the adapter's
  session coordinator on Turnkey and Privy and arrives as its mapping: `ProviderError` (`RAIN_501`), `Unauthorized`
  (`RAIN_202`) for a Turnkey 403, or a code the shared prose rules assign. One that nothing mapped
  floors at `InternalError` (`RAIN_502`).
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID (e.g. `43114`, or `901` for Solana devnet). |
| `addresses` | `RainWithdrawAddresses` | All required addresses: proxy, controller, token, recipient. On Solana, `proxyAddress` is the collateral account, `tokenAddress` is the SPL mint, and `controllerAddress` is unused (the coordinator is read from the collateral account). |
| `amount` | `BigDecimal` | Amount in human-readable token units (e.g. `BigDecimal("100.0")`). |
| `decimals` | `Int` | Token decimals (e.g. 6 for USDC, 18 for most tokens). Load-bearing on every chain including Solana: it scales the amount and is **not** checked against the SPL mint, so pass the mint's real decimals. |
| `adminSignature` | `RainAdminSignature` | Admin signature for authorization (salt, signature, expiresAt). |
| `nonce` | `BigInteger?` | Optional nonce; if `null`, SDK resolves from contract. Ignored on Solana — the nonce always comes from the on-chain collateral account. |

---

### prepareWithdrawal(chainId, addresses, amount, decimals, adminSignature, nonce)

Builds a collateral withdrawal without broadcasting it. Takes the same parameters as
`withdrawCollateral`.

This is **not** an offline build: it still prompts the wallet to sign EIP-712 (EVM) and reads the
collateral's admin set on chain. On Solana it additionally fetches a recent blockhash and simulates
the transaction (the simulation is skipped when the provider sponsors fees).

Not gated on the provider's broadcast chains: preparing signs and composes but never broadcasts, so
it works on a chain the provider cannot send on (Avalanche with the Turnkey and Rain wallet
providers). The EVM result is an unsigned envelope whose `data` carries the typed-data signature; a
host signs it with the wallet's key and submits it through its own RPC there. `withdrawCollateral`
on the same chain still throws `RAIN_104`. On Solana the fee check and the dry run always run, whatever
the provider sponsors, because the prepared transaction is the host's own self-paid submission.

- **Returns:** `RainPreparedWithdrawal` — `Evm(RainTransactionParameters)` carrying a complete,
  submittable transaction (`from` / `to` / `value` / `data`), or `Solana(UnsignedSolanaTransfer)`
  carrying the serialized unsigned transaction (`transaction`, or `transactionHex`), its
  `recentBlockhash`, and `createsRecipientAccount`, true when the transfer creates the recipient's
  token account, whose rent the sender pays even when the fee is sponsored.
- **Throws:** `RainError` if construction or signing fails. On Solana, the wallet-address read passes
  through the adapter's session coordinator on Turnkey and Privy, so a raw provider failure there
  arrives as its mapping: `ProviderError` (`RAIN_501`), `Unauthorized` (`RAIN_202`) for a Turnkey
  403, or a code the shared prose rules assign. One that nothing mapped floors at `InternalError`
  (`RAIN_502`).
- **Suspend:** Yes

> A Solana blockhash lives for roughly 150 slots, 60 to 90 seconds. The SDK fetches it at `finalized`
> commitment, so about 13 seconds have passed when the preparation returns. Submit promptly or prepare again.

Use `evmParameters` / `solanaTransfer` to read the payload without writing a `when`.

---

### getWalletAddress()

Returns the current wallet address from the backing provider.

- **Returns:** `String` — hex-encoded wallet address (e.g. `"0x..."`).
- **Throws:** `RainError` if address cannot be retrieved.
- **Suspend:** Yes

---

### getWalletAddress(chainId)

Returns the wallet address for a specific chain. For EVM chains this is the same hex address as
`getWalletAddress()`. A provider that also holds non-EVM accounts (advertising
`Capability.MULTI_CHAIN`) returns the address matching `chainId`'s family — e.g. a base58 Solana
address for a Solana chain id (`RainChain.SOLANA_DEVNET`). EVM-only providers ignore the family
distinction and return the hex address.

- **Parameters:** `chainId: Int`
- **Returns:** `String` — the wallet address for that chain's family.
- **Throws:** `RainError` if the address cannot be retrieved.
- **Suspend:** Yes

---

### estimateGas(chainId, from, to, data)

Estimates the gas fee required for a transaction.

On a provider that sponsors fees the quote is what the wallet would pay itself; a sponsor pays instead
and its own cost is not quoted, so a host can show what sponsorship saves (see [Capabilities](#capabilities)).

- **Returns:** `BigDecimal` — estimated gas fee in the chain's native token (e.g. AVAX).
- **Throws:** `RainError` if estimation fails. A node revert arrives as `TransactionSimulationFailed` (RAIN_403) on every adapter. Anything else depends on the adapter: Portal maps through its session coordinator, prose rules included, so an unrecognized Portal failure is `ProviderError` (RAIN_501) and one whose text names a funds shortfall is `InsufficientFunds`; Privy's own RPC client also maps a funds shortfall to `InsufficientFunds` and floors at `InternalError` (RAIN_502); Turnkey's self-paid estimate runs through core's RPC client and floors at `InternalError`. A raw exception from the estimate call that nothing mapped floors at `InternalError` as well.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `from` | `String` | Sender wallet address. |
| `to` | `String` | Target contract address. |
| `data` | `String` | Hex-encoded transaction calldata (e.g. from `buildWithdrawTransactionData`). |

---

### estimateWithdrawalFee(chainId, addresses, amount, decimals, adminSignature, nonce)

Estimates the total fee required to execute a collateral withdrawal transaction. The withdrawal
authorization (`salt` / `signature` / `expiresAt`, fetched by the host from the Rain API) is
caller-supplied and embedded in the estimated calldata.

Internally builds the EIP-712 payload, signs it with the wallet, then runs `eth_estimateGas`
against the withdrawal controller. Nothing is broadcast.

On a provider that sponsors the fee the quote is what the wallet would pay itself, as for `estimateGas`;
the withdrawal is built and signed once to price it, as on a self-paid chain.

> **Signing side effect.** The estimated calldata embeds a wallet signature the controller
> verifies (a placeholder would revert the estimate), so estimate-then-withdraw signs twice.
> To quote without a second signature, call `prepareWithdrawal` once and pass the result to
> `estimateWithdrawalFee(chainId, prepared)` below.

- **Returns:** `BigDecimal`, the estimated withdrawal fee in the chain's native token.
- **Throws:** `RainError` if estimation fails. A node revert arrives as `WithdrawalRevertedByNetwork` (RAIN_405), the withdrawal flow's rewrap of a simulation failure; everything else maps as under `estimateGas`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `addresses` | `RainWithdrawAddresses` | All addresses required for the withdrawal (controller, proxy, token, recipient). |
| `amount` | `BigDecimal` | Human-readable amount to withdraw. |
| `decimals` | `Int` | Token decimals (e.g. 6 for USDC, 18 for most tokens). |
| `adminSignature` | `RainAdminSignature` | Rain's withdrawal authorization, fetched by the host from the Rain API. |
| `nonce` | `BigInteger?` | Optional; pin the estimate to the nonce the withdrawal will sign. |

EVM only. A Solana chain id throws `InternalError` (`RAIN_502`), as it does on the overload below.

---

### estimateWithdrawalFee(chainId, prepared)

Estimates the fee of a withdrawal already built by `prepareWithdrawal`, running `eth_estimateGas` on
the prepared parameters. Builds and signs nothing, so the flow is: prepare once (one signature),
quote the fee on the preparation, then submit.

- **Returns:** `BigDecimal`, the estimated withdrawal fee in the chain's native token.
- **Throws:** `RainError` if estimation fails; `InternalError` (`RAIN_502`) for a Solana preparation or chain id (EVM only, like the building overload); a node revert arrives as `WithdrawalRevertedByNetwork` (`RAIN_405`), as for the building overload.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. EVM only. Not checked against `prepared`; pass the chain id `prepareWithdrawal` was called with. |
| `prepared` | `RainPreparedWithdrawal` | The withdrawal built by `prepareWithdrawal`. |

---

### sendNative(chainId, to, amount)

Sends native tokens (e.g. ETH, AVAX, SOL) from the current wallet.

On Turnkey, sends are refused with `RAIN_104` on chains outside Turnkey's managed-broadcast
coverage (Avalanche, Celo, ZKsync, Plasma, and Ink are read-only there); this applies to
`sendToken` and raw sends too. Balance and history reads are never gated.

On Monad (`143`, `10143`) Turnkey's sponsorship runs through EIP-7702 delegation, and Monad reverts
any delegated-account transaction that would leave the balance under 10 MON. A sponsored native MON
send from a wallet below that fails on chain even when the estimate succeeds; token sends are unaffected.

- **Returns:** `RainTokenTransferResult` — containing the transaction hash.
- **Throws:** `RainError` if send fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `to` | `String` | Recipient wallet address. On EVM chains, a mixed-case address must carry its EIP-55 checksum; see [Errors](#errors). |
| `amount` | `BigDecimal` | Amount in human-readable form (e.g. `BigDecimal("0.1")` for 0.1 AVAX). |

---

### sendToken(chainId, contractAddress, to, amount, decimals?)

Sends ERC-20 tokens (EVM chains) or SPL tokens (Solana chains) from the current wallet.
Routed by `chainId`.

- **Returns:** `RainTokenTransferResult` — containing the transaction hash.
- **Throws:** `RainError` if send fails.
- **On Solana chains** (Rain IDs 900 mainnet / 901 devnet, SDK-internal 902 testnet):
  `contractAddress` is the SPL mint. The mint's own on-chain `decimals` are authoritative — the
  `decimals` parameter is not used to scale the amount — and a missing recipient token account is
  created in the same transaction at the sender's expense. This applies to `sendToken` only; see
  `withdrawCollateral`, where `decimals` **is** load-bearing.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `contractAddress` | `String` | ERC-20 token contract address, or the SPL mint on Solana. On EVM chains, a mixed-case address must carry its EIP-55 checksum; see [Errors](#errors). |
| `to` | `String` | Recipient wallet address. On EVM chains, a mixed-case address must carry its EIP-55 checksum; see [Errors](#errors). |
| `amount` | `BigDecimal` | Amount in human-readable form (e.g. `BigDecimal("100.0")` for 100 USDC). |
| `decimals` | `Int?` | Optional token decimals. When `null` (the default), the SDK resolves the token's `decimals()` from its registry or an on-chain read, so callers don't have to track it. If neither can establish it, the send throws `TokenNotFound` rather than scaling by a guessed value. |

---

### authPullChainIds

The chains this client will accept an Auth Pull approval on — the host's `RainAuthPullConfig`
narrowed to the chains that have an RPC endpoint, and the same set the approval guard enforces.
Empty until `RainSdk.Builder.authPullConfig(...)` supplies the trusted targets. Also available on
`RainSdk` itself, for gating before a client is resolved.

Gate host UI on this rather than on the static `RainAuthPullChains.SANDBOX` / `PRODUCTION` sets, which
answer for an environment, not for this SDK instance. See [Auth Pull](AUTH_PULL.md#supported-chains-and-assets).

- **Type:** `Set<Int>`
- **Suspend:** No

---

### approveTokenAllowance(chainId, contractAddress, spender, amount?)

Approves `spender` to move up to `amount` of an ERC-20 token from the current wallet — the
wallet-side prerequisite for Rain's [Auth Pull](AUTH_PULL.md). Rain executes the pull itself; the
SDK only sets the allowance.

Auth Pull is disabled until `RainSdk.Builder.authPullConfig(...)` supplies the trusted operator and
per-chain token targets. The SDK rejects any different chain, token, or spender before wallet access.

- **Returns:** `RainTokenApprovalResult` — the transaction hash of the `approve` call.
- **Throws:** `RainError`. EVM only — a Solana `chainId` throws `RainError.InternalError`, since
  SPL delegation is not an ERC-20 allowance. A `chainId` outside `authPullChainIds` throws
  `RainError.InvalidConfig`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target EVM network chain ID. Must be an Auth Pull chain for the configured `RainAuthPullConfig`. |
| `contractAddress` | `String` | ERC-20 token contract (USDC for Auth Pull). |
| `spender` | `String` | Address being approved — Rain's operator. Source it from Rain; it differs between sandbox and production. |
| `amount` | `BigDecimal?` | Human-readable allowance (e.g. `BigDecimal("250")`). `null` (the default) approves an unlimited (`uint256` max) allowance; `BigDecimal.ZERO` revokes. |

There is deliberately **no `decimals` parameter** on any Auth Pull method: the scale comes from
trusted registry metadata or a strict on-chain `decimals()` read, never from the caller. A token
whose decimals cannot be established throws `RainError.TokenNotFound` rather than being guessed at.

```kotlin
// Unlimited — what Rain recommends, so the user never has to re-approve.
val result = client.approveTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdc,
    spender = rainOperator
)

// Capped, then revoked.
client.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, rainOperator, BigDecimal("250"))
client.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, rainOperator, BigDecimal.ZERO)
```

The new value is written straight over the old one. USDC accepts that; some ERC-20s (USDT and its
clones) revert unless an existing non-zero allowance is set to zero first — see
[Auth Pull](AUTH_PULL.md#3-approve).

---

### getTokenAllowance(chainId, contractAddress, spender, owner?)

Reads the ERC-20 allowance `spender` currently holds over `owner`'s balance. Call it before
approving (to skip a redundant transaction). To confirm an approval was mined, use
`confirmTokenAllowance`, this read is unpinned and can still return the pre-approval value.

- **Returns:** `RainTokenAllowance` — see [RainTokenAllowance value type](#raintokenallowance-value-type).
- **Throws:** `RainError`. EVM only, and gated to the configured `RainAuthPullConfig`'s chains like
  the approval itself.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target EVM network chain ID. |
| `contractAddress` | `String` | ERC-20 token contract. |
| `spender` | `String` | Address whose allowance is being read — Rain's operator. |
| `owner` | `String?` | Wallet whose balance is approved. `null` (the default) reads this client's own wallet, which is the only case that touches the wallet provider at all. |

---

### estimateApprovalFee(chainId, contractAddress, spender, amount?)

Estimates the total fee (estimated gas x gas price) to submit the approval, in the chain's native
token. Nothing is broadcast and no signature is requested; the fee is priced against the exact
calldata `approveTokenAllowance` would send. On a provider that sponsors fees the quote is what the
wallet would pay itself, as for `estimateGas`.

- **Returns:** `BigDecimal` — fee in the chain's native currency (e.g. ETH).
- **Throws:** `RainError`.
- **Suspend:** Yes

Parameters are identical to `approveTokenAllowance`.

---

### confirmTokenAllowance(transactionHash, chainId, contractAddress, spender, amount?, owner?)

Waits for an approval transaction to mine successfully, then reads back the resulting allowance. A
transaction hash alone means submitted, not ready: use this before treating the user as approved for
Auth Pull.

Polls `eth_getTransactionReceipt` once a second for up to 60 seconds, then reads the allowance
through the same path as `getTokenAllowance`, pinned to the block the transaction mined in.

- **Returns:** `RainTokenAllowance` — the allowance actually in place after the transaction mined.
- **Throws:** `RainError`. Reverted receipt → `TransactionSimulationFailed`; poll window exhausted →
  `TransactionPending` with the transaction hash as `statusId` (not confirmed *yet* — re-read, don't
  re-approve); mined allowance not equal to the request, or a Solana `chainId` → `InternalError`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `transactionHash` | `String` | Hash returned by `approveTokenAllowance`. |
| `chainId` | `Int` | Target EVM network chain ID. Must match the approval's chain. |
| `contractAddress` | `String` | ERC-20 token contract the approval was against. |
| `spender` | `String` | Address that was approved — Rain's operator. |
| `amount` | `BigDecimal?` | The allowance that was requested, so the result can be checked against it. `null` (the default) means the unlimited approval; `BigDecimal.ZERO` means a revoke. |
| `owner` | `String?` | Wallet whose allowance to read. `null` (the default) reads this client's own wallet. |

**The returned allowance must equal `amount`.** The read is pinned to the block the approval mined
in, so later blocks cannot move what is read back. Anything but the requested amount means the
approval did not do what was asked — below when it was raising the allowance, above when it was
lowering it, still zero when it mined against the wrong owner, token, or spender, or non-zero after
a revoke — and throws `InternalError` (`RAIN_502`). One edge: an Auth Pull `transferFrom` mined in
the *same block* as the approval also reads back lower and surfaces as `RAIN_502`; re-read with
`getTokenAllowance` before treating that as a failed approval. Later pulls decrement the live
allowance, so use `getTokenAllowance` (and `covers`) for the ongoing check.

```kotlin
val result = client.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, rainOperator)
val allowance = client.confirmTokenAllowance(
    transactionHash = result.transactionHash,
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdc,
    spender = rainOperator
)
```

---

### RainTokenAllowance value type

| Member | Type | Description |
|--------|------|-------------|
| `rawAmount` | `BigInteger` | Exact allowance in the token's smallest unit. Never lossy — compare against this. |
| `decimals` | `Int` | The token's decimals (e.g. 6 for USDC). |
| `chainId` / `tokenAddress` / `owner` / `spender` | | What was read, so a merged list stays self-describing. |
| `isUnlimited` | `Boolean` | `rawAmount == uint256` max. Exact: some tokens decrement even a max allowance, so `false` does not mean "must re-approve" — compare `rawAmount` against what you need. |
| `isZero` | `Boolean` | Nothing approved — the state after a revoke. |
| `decimalAmount` | `BigDecimal` | Derived: `rawAmount / 10^decimals`. For an unlimited approval this is ~1.16e71; gate on `isUnlimited` before rendering. |
| `formatted` | `String` | Derived display string with trailing zeros trimmed. |
| `covers(amount)` | `(BigDecimal) -> Boolean` | Whether a human-readable amount is still covered, compared in exact base units. An amount that cannot be represented at all (negative, or finer than the token's scale) is `false` too. |
| `UNLIMITED_RAW_AMOUNT` | `BigInteger` | Companion constant: `uint256` max. |

---

### Balance value type

All balance methods return rich `Balance` values rather than lossy `Double`s.

| Field | Type | Description |
|-------|------|-------------|
| `token` | `Token` | `Token.Native` or `Token.Contract(address)`. |
| `chainId` | `Int` | EIP-155 chain ID the balance was read on. |
| `rawAmount` | `BigInteger` | Exact balance in the token's smallest unit (never lossy). |
| `decimals` | `Int` | Token decimal places (e.g. 6 for USDC, 18 for ETH). |
| `symbol` | `String?` | Token symbol, when known. |
| `name` | `String?` | Human-readable name, when known. |
| `decimalAmount` | `BigDecimal` | Derived: `rawAmount / 10^decimals`. |
| `formatted` | `String` | Derived display string (e.g. `"1.5"`). |

---

### getBalance(chainId, token)

Fetches a single balance (native or a contract token) for the current wallet.

- **Returns:** `Balance` — exact `rawAmount` plus resolved decimals / symbol / name.
- **Throws:** `RainError` if the request fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID (e.g. `43114` for Avalanche Mainnet). |
| `token` | `Token` | `Token.Native`, or `Token.Contract(address)` (address comparison is case-insensitive). |

---

### getTokenBalances(chainId)

Fetches the current wallet's balances on the given network: the native balance, always, plus one
entry per contract token the wallet holds that the provider lists. Zero-balance contract tokens are
omitted. Which contract tokens a provider lists:

| Provider | EVM chains | Solana |
|----------|------------|--------|
| Turnkey provider, Rain wallet | On Ethereum, Base, Polygon and their test networks: every token the wallet backend's balance service reports, plus every token in the SDK's built-in registry or passed to `registerTokens` that the service left out. Each call on these chains also sends one Multicall3 `eth_call` batch for the registry and registered tokens to the chain's RPC endpoint, alongside the service call; the service's row wins where both answer, and a chain with no endpoint, or a read that fails or takes longer than five seconds, leaves the service's rows and is logged once per chain. On an organization whose wallet backend has no balance service (HTTP 403, see [TURNKEY_SUPPORT.md](TURNKEY_SUPPORT.md)): the registry and registered tokens, read from the chain, with no service call after the first refusal. On other EVM chains: the registry and registered tokens, read from the chain. | The wallet backend's list where it indexes the cluster; otherwise every token account the node reports. See [Solana notes](TURNKEY_SUPPORT.md#solana-notes). |
| Privy | The registry and registered tokens, read from the chain. | Every token account the node reports. |
| Portal | The tokens Portal's own service reports. | No Solana account; a Solana chain id is not supported. |

A token outside the provider's list is not in the result; [getBalance](#getbalancechainid-token)
reads any single token by address. In `5.0.0-beta.1` the Turnkey provider and the Rain wallet leave
out the registry and registered tokens the wallet backend's service does not report, on the chains
that service covers; see the [CHANGELOG](../CHANGELOG.md).

- **Returns:** `List<Balance>` — the native balance plus one per listed contract token with a non-zero balance.
- **Throws:** `RainError` if the request fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |

---

### getAllBalances()

Fetches balances across every chain the SDK was initialized with, in parallel, flattened
into a single list. Each `Balance` carries its own `chainId`. Per-chain failures are
tolerated — a chain that errors out contributes no entries rather than failing the whole
call. It is built from the same per-chain read as [getTokenBalances](#gettokenbalanceschainid),
so that section's table and its `5.0.0-beta.1` note apply here too.

- **Returns:** `List<Balance>` — a flat list spanning all healthy configured chains.
- **Throws:** `RainError` if the SDK was not initialized.
- **Suspend:** Yes

---

### registerTokens(tokens)

Registers additional tokens so their metadata (decimals / symbol) resolves without an
on-chain enrichment call. Retained across `reset()`, since the store is shared by every client the `RainSdk` resolves. Built-in
registry tokens are trusted and cannot be overridden: a registration naming one is ignored, with a
warning when its metadata differs from the built-in entry.

- **Throws:** `RainError.InvalidConfig` (`RAIN_102`) on a malformed entry, validated exactly as
  `RainSdk.registerTokens` validates; the whole list is checked first, so nothing is registered. The
  entries are stored before the call returns, so a lookup that follows sees them.
- **Returns:** `Unit`
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `tokens` | `List<TokenInfo>` | Tokens to add to the SDK's token store. Re-registering a host-added address replaces its entry; built-in registry tokens cannot be overridden. |

---

### generateAddressQRCode(address, dimension)

Generates a square QR code `Bitmap` encoding `address`, or the wallet's own address when `address`
is `null`. Use it for any address the host needs to show — a chain-specific wallet address (the
Solana account rather than the EVM one), or a Rain collateral deposit address.

- **Returns:** `Bitmap` — QR code image.
- **Throws:** `RainError` if wallet is unavailable or QR generation fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `address` | `String?` | Address to encode. If `null`, uses the current wallet address. |
| `dimension` | `Int` | Output width and height in pixels (the QR is square). Defaults to `256`. |

---

### getTransactions(chainId, limit, offset, order)

Fetches transaction history for the current wallet on the given network.

- **Returns:** `List<RainTransaction>` — the transaction records. `value` is a `BigDecimal?` in human-readable units; null when decimals could not be resolved, with `rawValue` still populated.
- **Source (Turnkey and Rain wallet):** the wallet backend's indexed history when the transaction history feature is enabled for the organization: receives and externally submitted transactions included, EVM addresses in EIP-55 form, real Solana signatures in `hash`. Otherwise the activity log, which lists sends only and, on Solana, carries the backend's status id in `hash` (the activity id when the send recorded none). The fallback runs only when the backend refuses the indexed query; a dead session, a transport failure or a page that could not be decoded surfaces as its own error.
- **Throws:** `RainError` if transaction history cannot be retrieved.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `limit` | `Int?` | Optional max number of transactions to return. |
| `offset` | `Int?` | Optional pagination offset. |
| `order` | `RainTransactionOrder?` | Optional sort order: `.ASC` or `.DESC`. |

---

### reset()

Clears this client's own state only. Idempotent. Tokens registered through this client live in the
store the `RainSdk` shares with every resolved client, so they survive `reset()`, and so does the
chain configuration the `RainSdk` owns. One client resetting must not deconfigure the others. Prefer
`RainSdk.close()` to tear down the whole SDK.

- **Suspend:** No

---

## Capabilities

A provider advertises optional behaviours via `Capability`, so hosts can resolve by feature
(`rain.first { Capability.EXPORT in it.capabilities }`) and degrade gracefully instead of assuming
a capability every provider has. `first` returns the earliest registered provider that matches, so
registration order decides between two providers that both advertise a capability. `EXPORT` says
the wallet can be backed up somewhere; the SDK-driven export methods exist on `TurnkeyProvider` and
on the Rain wallet's `RainProvider`, see [Turnkey key export](#turnkey-key-export) and
[Rain wallet provider](#rain-wallet-provider).

| Capability | Meaning |
|------------|---------|
| `EXPORT` | The wallet's key material can be exported / backed up. |
| `RECOVERY` | The wallet supports a recovery ceremony. |
| `MULTI_CHAIN` | The provider holds accounts across multiple chain families (e.g. EVM + Solana). |
| `BIOMETRIC_GATE` | Signing is gated behind a device biometric / passkey prompt. No bundled provider advertises it; it is available to host-supplied providers. |
| `GAS_SPONSORSHIP` | The provider's sends are fee-sponsored (a third party pays the network fee), so core skips the self-paid preflight that would charge the fee to the wallet, the Solana withdrawal dry run on `withdrawCollateral` (`prepareWithdrawal` always runs it, since the host submits and pays); fee estimates are not affected and quote what the wallet would pay itself. Core's operative, per-chain check is `WalletProvider.sponsorsFees(chainId)`, which defaults to this capability. |

Bundled providers: **Portal** → `EXPORT`, `RECOVERY`. **Turnkey** → `EXPORT`, `MULTI_CHAIN`, plus
`GAS_SPONSORSHIP` while `sponsorGas` is on (the default). **Rain wallet** →
the same set as Turnkey, forwarded from the adapter it wraps. **Privy** → `EXPORT`, `RECOVERY`,
`MULTI_CHAIN`.

---

## Wallet-agnostic transaction building

Available directly on `RainSdk`. These methods do **not** require a resolved provider — they can be
used with any wallet or backend, backed only by the configured RPC endpoints.

### getLatestNonce(chainId, proxyAddress)

Reads the collateral's current admin nonce — the value `buildEIP712Message` binds when `nonce` is
omitted.

- **Returns:** `BigInteger` — the current nonce.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID; the RPC endpoint is resolved from it. |
| `proxyAddress` | `String` | The collateral proxy contract address. |

---

### isCollateralAdmin(chainId, proxyAddress, walletAddress)

Whether `walletAddress` is an admin of the collateral.

- **Returns:** `Boolean?` — the contract's answer, or `null` when the check could not run (RPC
  failure, or a collateral exposing no `isAdmin`). Treat `null` as unknown and proceed, never as
  "not authorized".
- **Suspend:** Yes

---

### buildEIP712Message(chainId, walletAddress, addresses, amount, decimals, nonce)

Builds the EIP-712 message the wallet signs to authorize a withdrawal, along with the salt bound
into it.

- **Returns:** `RainEIP712Message` — `message` (typed-data JSON), `salt` (32 raw bytes), and
  `saltHex`. Feed `salt` straight back into `buildWithdrawTransactionData`; a re-generated salt
  would not match the signature.
- **Throws:** `RainError` if message construction fails or inputs are invalid.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `walletAddress` | `String` | User wallet address (used as `user` in EIP-712). |
| `addresses` | `RainWithdrawAddresses` | Proxy, controller, token, recipient addresses. |
| `amount` | `BigDecimal` | Amount in human-readable token units. |
| `decimals` | `Int` | Token decimals. |
| `nonce` | `BigInteger?` | Optional; if `null`, the SDK reads it from the contract. |

---

### buildWithdrawTransactionData(addresses, amount, decimals, executorSignature, walletSalt, walletSignature)

ABI-encodes the `withdrawAsset` call for the collateral controller. Pure encoding — no RPC, so it
needs no chain ID.

Two distinct salt/signature pairs go in, and the contract names them differently from Rain's API:
`executorSignature` (Rain's authorization, fetched by the host from the Rain API) encodes into `_executorPublisherSalt` /
`_executorPublisherSignature`, while the wallet's own pair encodes into `_adminSalts` /
`_adminSignatures` — the wallet is an admin of the collateral.

- **Returns:** `String` — hex-encoded calldata (e.g. `"0x..."`).
- **Throws:** `RainError` if ABI encoding or validation fails.

| Parameter | Type | Description |
|-----------|------|-------------|
| `addresses` | `RainWithdrawAddresses` | Proxy, controller, token, recipient addresses. |
| `amount` | `BigDecimal` | Amount in human-readable token units. |
| `decimals` | `Int` | Token decimals. |
| `executorSignature` | `RainAdminSignature` | Rain's authorization (salt, signature, expiresAt). |
| `walletSalt` | `ByteArray` | `RainEIP712Message.salt`, unchanged (32 bytes). |
| `walletSignature` | `String` | The wallet's hex signature over the EIP-712 message (65 bytes). |

---

### buildTransactionParameters(walletAddress, contractAddress, transactionData)

Composes a wallet-agnostic transaction parameter bag for a contract call. Pure composition — no
wallet provider and no RPC — returning a Rain-owned `RainTransactionParameters` struct with `value`
pre-set to `"0x0"`. Hosts can hand the result to any provider for signing / broadcast.

- **Returns:** `RainTransactionParameters` — `from`, `to`, `value` (`"0x0"`), `data`.
- **Suspend:** No

| Parameter | Type | Description |
|-----------|------|-------------|
| `walletAddress` | `String` | Sender wallet address. |
| `contractAddress` | `String` | Target contract address. |
| `transactionData` | `String` | Hex-encoded calldata. |

---

## Types

| Type | Description |
|------|-------------|
| **`ProviderId`** | Value class wrapping a provider id string. Well-known constants: `PORTAL`, `TURNKEY`, `PRIVY`, `RAIN`. Host apps can ship a custom id. |
| **`Capability`** | Enum: `EXPORT`, `RECOVERY`, `MULTI_CHAIN`, `BIOMETRIC_GATE`, `GAS_SPONSORSHIP`. |
| **`TurnkeyKeyFamily`** | Enum: `ETHEREUM`, `SOLANA`, and possibly more as Turnkey adds curves. Which of a Turnkey wallet's keys `TurnkeyProvider.exportPrivateKey` returns; see [Turnkey key export](#turnkey-key-export). |
| **`RainEIP712Message`** | `message`, `salt`, `saltHex`. Returned by `buildEIP712Message`. |
| **`ProviderDescriptor`** | Registrable provider descriptor: `id`, `capabilities`, and a suspend `create(context)` that materializes the `WalletProvider`. Implemented by `PortalProvider`, `TurnkeyProvider`, `PrivyProvider`, `RainProvider`, and host-supplied providers. |
| **`RainWalletConfig`** | Behaviour of the Rain wallet provider: `sessionPolicy`, `onSessionExpired`, `sponsorGas`, `passkeyDomain`. No address override. See [Rain wallet provider](#rain-wallet-provider). |
| **`RainWalletSessionPolicy`** | Expiry, refresh and retry behaviour of the Rain wallet session; `refreshExpirationSeconds` is a `Long?`. Out-of-range values throw `IllegalArgumentException` at construction. |
| **`RainWalletSessionState`** | Sealed: `Loading`, `Active(expiresAtEpochSeconds: Double)`, `Expired`, `Unauthenticated`. A `when` over it is exhaustive; a new state is a breaking change shipped in a major version. Compared by value. |
| **`RainWalletAuthState`** | Sealed: `Loading`, `Authenticated`, `Unauthenticated`. A `when` over it is exhaustive; a new state is a breaking change shipped in a major version. |
| **`RainWalletContact`** | Sealed: `Email(value)` or `Phone(value)`, the contact a login code goes to and the identity the account is keyed on. `toString()` hides the value. |
| **`RainWalletKeyAccount`** | Enum: `ETHEREUM`, `SOLANA`. Which of the Rain wallet's keys `RainProvider.exportPrivateKey` returns. |
| **`ExperimentalRainApi`** | Opt-in marker (`@RequiresOptIn`, error level). On `WalletProvider`, `ProviderDescriptor` and `RainClient` it guards implementing them: Rain may add members to these interfaces in any release. On any other declaration it means the declaration may change in any release, with a CHANGELOG entry. Calling the SDK needs no opt-in. |
| **`WalletProvider`** | The port each adapter implements, in `com.rain.sdk.provider`. Public so hosts can ship their own wallet stack; implementing it needs `@OptIn(ExperimentalRainApi::class)`, because Rain may add members in any release. |
| **`TokenInfo`** | A token the SDK reads balances for and scales amounts by: `chainId` (numeric; EIP-155 for EVM chains, 900 to 902 for the Solana clusters), `address` (the ERC-20 contract, or the SPL mint on Solana), `symbol: String?`, `decimals: Int`, `name: String?`. Returned by `tokenMetadata`, accepted by the three `registerTokens` methods. |
| **`RainWithdrawAddresses`** | `proxyAddress`, `controllerAddress`, `tokenAddress`, `recipientAddress`. `validated()` returns them in EIP-55 checksum form and refuses a malformed address or a mixed-case one whose checksum doesn't match; see [Errors](#errors). |
| **`RainAdminSignature`** | Rain's authorization for one withdrawal, passed through unchanged: `salt` (base64, 32 bytes on every chain), `signature` (EVM: 0x-hex, 65 bytes; Solana: base64, 64 bytes), `expiresAt` (unix seconds, or an ISO-8601 instant with Z or a numeric offset). |
| **`RainPreparedWithdrawal`** | Sealed: `Evm(parameters: RainTransactionParameters)` or `Solana(transfer: UnsignedSolanaTransfer)`. Has `evmParameters` / `solanaTransfer` accessors. |
| **`UnsignedSolanaTransfer`** | The Solana half of a prepared withdrawal: `transaction` (the serialized unsigned transaction, a defensive copy), `transactionHex` (the same bytes as hex without `0x`), `recentBlockhash` (base58; see the blockhash note under `prepareWithdrawal`), `createsRecipientAccount` (true when the transfer creates the recipient's token account, whose rent the sender pays even when the fee is sponsored). |
| **`RainTokenTransferResult`** | `transactionHash` (String). Returned by `sendNative` and `sendToken`. |
| **`RainTokenApprovalResult`** | `transactionHash` (String): hash of the ERC-20 `approve` call. Returned by `approveTokenAllowance`. |
| **`RainTokenAllowance`** | Exact allowance value type; see [RainTokenAllowance value type](#raintokenallowance-value-type). |
| **`RainAuthPullConfig`** | Trusted Auth Pull targets: `operatorAddress` plus a `chainId → token contract` map. `sandbox(...)` and `production(...)` bind the canonical USDC contracts and chains; `custom(...)` names both explicitly and may draw on either environment's chains. Passed to `RainSdk.Builder.authPullConfig(...)`. |
| **`RainAuthPullChains`** | The Auth Pull chain sets by environment: `SANDBOX` (Base Sepolia, Arbitrum Sepolia), `PRODUCTION` (Base, Arbitrum); gate UI on [authPullChainIds](#authpullchainids). |
| **`NetworkConfig`** | `chainId`, `rpcUrl`, `networkName?`; `eip155ChainId` renders the CAIP-2 id, `eip155:<chainId>` for an EVM chain or `solana:<genesis>` for a Solana sentinel; `NetworkConfig.fromEip155(...)` parses the `eip155:` form. Accepted by `Builder.rpcEndpoints(List<NetworkConfig>)`. |
| **`RainTransactionParameters`** | `from`, `to`, `value` (hex wei), `data` (hex calldata). Wallet-agnostic transaction parameter bag returned by `RainSdk.buildTransactionParameters`. |
| **`RainTransaction`** | Transaction record: `hash`, `uniqueId`, `blockNumber`, `timestamp`, `from`, `to`, `value`, `asset`, `tokenAddress`, `rawValue`, `decimals`, `category`, `chainId`, `metadata`. |
| **`RainTransactionCategory`** | Extensible constant: `External`, `Token`, `Erc20`, `Erc721`, `Erc1155`, `ContractInternal`. |
| **`EthereumConverter`** | Public helpers in `com.rain.sdk.utils` for wei and hex values. `convertWeiToEthDecimal(wei)` and `convertWeiHexToDecimal(hex)` return an exact 18-decimal `BigDecimal`; `convertHexToDecimal(hex, decimals)` scales by any decimals. Both hex converters read an empty payload (`"0x"`) as zero and throw `InternalError` on non-hex input. `convertEthToWeiHex(amount, decimals)` goes the other way and throws `InvalidAmount` on a negative amount, more decimal places than `decimals`, a value above uint256, or `decimals` outside 0..77. `decimalStringToBigInteger(value, decimals)` rounds down and returns zero on unparseable input. `parseHexToBigIntegerStrict` and `parseHexToIntStrict` throw `InternalError` on malformed hex; `parseHexToString` decodes an ABI-encoded string or returns `null`; `normalizedHexString` returns `"0x0"` for `null`, a value without the `0x` prefix or a bare `"0x"`, and any other value unchanged, without checking its digits. |
| **`RainTransactionOrder`** | Enum: `.ASC`, `.DESC`. Used in `getTransactions(..., order:)`. |
| **`RainChain`** | Constants: `AVALANCHE_MAINNET` (43114), `AVALANCHE_TESTNET` (43113), `BASE_MAINNET` (8453), `BASE_SEPOLIA` (84532), `ARBITRUM_MAINNET` (42161), `ARBITRUM_SEPOLIA` (421614), plus the Solana sentinels. |

---

## Errors

All methods can throw `com.rain.sdk.error.RainError` (sealed class). Each error carries a `code` string (`RAIN_xxx`) for programmatic handling and a typed `errorCode`.

Format: `"RainSDK Error [CODE]: message"`

| Code | Class | Meaning |
|------|-------|---------|
| `RAIN_101` | `RainError.SdkNotInitialized` | A `RainSdk` used after `close()`, or `PortalProvider.refreshSession()` / `updateSessionToken()` called before the provider was resolved. |
| `RAIN_102` | `RainError.InvalidConfig` / `RainError.ProviderNotRegistered` / `RainError.TokenNotFound` / `RainError.InvalidRecipient` | Invalid RPC URL, chain ID, or address format; an EVM address written in mixed case whose EIP-55 checksum doesn't match, refused before anything is signed (a send's recipient as `InvalidRecipient`; a send's token contract, a withdrawal address, or an Auth Pull operator or token contract as `InvalidConfig`), which 5.0.0-beta.1 doesn't check on sends, withdrawals, the wallet-agnostic builder methods or the Auth Pull config (see [Known issues](../CHANGELOG.md#known-issues)); a token whose decimals could not be established when a money path needed them (`TokenNotFound`); a recipient that cannot receive the transfer (`InvalidRecipient`); a malformed withdrawal salt, signature or expiry handed to a withdrawal method; a token registration with a malformed address or mint or `decimals` outside `0..77`; a `tokenMetadata` lookup with a malformed address or a chain the SDK has no RPC endpoint for; a blank email or a phone number outside E.164 handed to `sendLoginCode` or `sendContactVerificationCode`; a passkey method called without a `passkeyDomain`, or a `passkeyDomain` with a scheme, port, path or a single label at construction; a passkey request the device refused because the domain's association file does not list this build's package name and signing certificate, or carries the app statement without the site's own statement; `signUpWithPasskey` while a live session is selected on the device; `confirmContactVerification` before a code was requested; for a Turnkey key export, the cases under [Key export errors](TURNKEY_SUPPORT.md#key-export-errors); a closed `TurnkeyProvider` or `RainProvider` asked to authenticate or export; the Rain wallet provider and the Turnkey provider registered on one `RainSdk` (`build()` refuses the pair); no provider registered for the requested id; or no provider matched a capability. |
| `RAIN_103` | `RainError.InvalidRpcUrl` | RPC URL could not be parsed as a valid URL. |
| `RAIN_104` | `RainError.ChainNotSupported` | The active wallet provider cannot broadcast transactions on this chain (e.g. Turnkey-managed sends do not cover Avalanche); carries `chainId`. Thrown before any network or wallet work on every send, withdrawals and approvals included (core asks the provider first, and the provider's broadcast funnel checks again). Reads (balances, history, estimates) and `prepareWithdrawal` are never gated. |
| `RAIN_201` | `RainError.TokenExpired` | Provider session token expired or invalid; `addPasskey`, `sendContactVerificationCode` or `confirmContactVerification` without a live session. |
| `RAIN_202` | `RainError.Unauthorized` | Wallet backends: a request refused with HTTP 403, such as a feature the organization lacks, the registration behind `addPasskey` or the contact update behind `confirmContactVerification` included; a contact another account already signs in with, on `confirmContactVerification`; or an empty Portal session token. |
| `RAIN_203` | `RainError.InvalidLoginCode` | The one-time login code was refused (mistyped, expired, or already used) — the Rain wallet's and Turnkey's managed login only. Ask the user to re-enter it or request a new one; the existing session, if any, is untouched. A wrong code on `confirmContactVerification` arrives the same way, with the challenge kept. A rejection the auth proxy wraps in an HTTP 500 cannot be classified, because Turnkey's Kotlin SDK drops the response body that carries the real status, so the same wrong code arrives as `RAIN_501`; treat `RAIN_501` from `confirmLoginCode` as retryable. The challenge is kept, so the same remedies apply. An expired code (5 minutes by default) or one locked after 3 wrong attempts arrives the same way; only `sendLoginCode` again gets the user past those. |
| `RAIN_301` | `RainError.NetworkError` | Network/connectivity failure. |
| `RAIN_302` | `RainError.TransactionPending` | Submitted, not yet confirmed. `statusId` is what to resume from (status id, UserOperation hash, or transaction hash). On a Turnkey or Rain wallet Solana send whose activity the backend accepted without a readable status id it is the backend's activity id, which no call takes and which matches `uniqueId` on the activity-log history row for the send. Do not resend. |
| `RAIN_401` | `RainError.UserRejected` | User cancelled the signing request in the wallet, or ended the passkey sheet without a passkey (dismissed it, or the device holds none for the domain). |
| `RAIN_402` | `RainError.InsufficientFunds` / `RainError.InsufficientTokenBalance` / `RainError.TokenAccountNotFound` | Balance too low for the requested amount or gas; a token balance below the requested amount (`InsufficientTokenBalance`, with `requested` and `available` as `BigDecimal` in the token's units); a Solana sender with no token account for the mint (`TokenAccountNotFound`). `InsufficientFunds` carries `required` and `available` as `BigDecimal?` in the native currency's human units: the Solana preflight fills them (fee plus token-account rent, or rent alone when the fee is sponsored), and every EVM path, which maps vendor prose without amounts, leaves them null. `currency` names the unit when the amounts are known (`SOL`). A Solana send the wallet backend refused for a fee or rent shortfall arrives here too, with `required`, `available` and `currency` null. |
| `RAIN_403` | `RainError.TransactionSimulationFailed` | Preflight `eth_call` simulation failed (e.g. contract revert, insufficient funds), or the wallet backend's send status carried decoded revert details (an EVM revert chain, whether the transaction failed before inclusion or was included and reverted, or a Solana `InstructionError` or the runtime's `Program <id> failed` log line), with `transactionId` set once the transaction was included; a failed status without them is `ProviderError`, and a Solana fee or rent shortfall is `InsufficientFunds`. |
| `RAIN_404` | `RainError.WalletUnavailable` | The backing provider returned no usable wallet address (e.g. Turnkey context has no Ethereum account), or, for a Turnkey or Rain wallet key export, the cases under [Key export errors](TURNKEY_SUPPORT.md#key-export-errors). |
| `RAIN_405` | `RainError.WithdrawalRevertedByNetwork` | Withdrawal reverted on-chain (e.g. duplicate withdrawal, already-used signature). A fee-sponsored withdrawal skips the dry run; a send status carrying decoded revert details maps here too, with the transaction hash on `transactionId` (and in the cause's message) once the transaction was included, and one without them is `RAIN_501`. |
| `RAIN_406` | `RainError.InvalidAmount` | The amount is invalid for the token — negative, more decimal places than the token supports, or past `uint256` max. |
| `RAIN_407` | `RainError.WalletNotAuthorized` | The wallet is not an admin of the collateral contract; checked before a withdrawal is signed. |
| `RAIN_501` | `RainError.ProviderError` | Portal, Turnkey, or other provider error; for a Turnkey or Rain wallet key export, the cases under [Key export errors](TURNKEY_SUPPORT.md#key-export-errors); a passkey ceremony, login or sign-up the device or the backend refused for another reason, an HTTP status inside a passkey login or sign-up included (no session exists yet, so it is never `RAIN_201` or `RAIN_202`); a contact code request, the account lookup behind `confirmContactVerification` (no session stamps it, so an HTTP failure inside it is `RAIN_501`, never `RAIN_201` or `RAIN_202`) or a contact update the backend refused. |
| `RAIN_502` | `RainError.InternalError` | EIP-712 encoding, ABI encoding, or internal processing error; for a Turnkey or Rain wallet key export, the case under [Key export errors](TURNKEY_SUPPORT.md#key-export-errors); a passkey login or sign-up the backend answered with an unusable response (no session token, an occupied session key). |

The code strings are a published contract hosts switch on; `RainErrorCodeParityTest` pins every value.

### Error handling example

```kotlin
try {
    val client = rain.provider(ProviderId.PORTAL)
    val result = client.withdrawCollateral(...)
} catch (e: RainError) {
    when (e) {
        is RainError.SdkNotInitialized -> { /* SDK not built */ }
        is RainError.InvalidConfig -> { /* Bad config / unknown provider: ${e.message} */ }
        is RainError.InsufficientFunds -> { /* Not enough balance */ }
        is RainError.NetworkError -> { /* Network issue: ${e.cause} */ }
        else -> { /* Other error: ${e.code} — ${e.message} */ }
    }
}
```
