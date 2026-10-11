# Turnkey Support

Rain SDK for Android supports [Turnkey](https://turnkey.com) as a wallet provider, alongside the Portal MPC and Privy adapters. Turnkey ships as the `TurnkeyProvider` adapter in its own `rain-turnkey-android` module (package `com.rain.sdk.turnkey`). Authentication has two modes. **Bring-your-own** (public): the host app uses the official [Turnkey Kotlin SDK](https://docs.turnkey.com/sdks/kotlin/getting-started) to authenticate (passkeys, OAuth, OTP, auth proxy) and hands the live `TurnkeyContext` to Rain via `TurnkeyConfig(turnkey)`. **Managed** (internal API, `@InternalRainTurnkeyApi`): construct `TurnkeyConfig(application, organizationId, authProxyConfigId, passkeyDomain = ...)` and the SDK owns the one-time-code flow, email or SMS, and the passkey flows through Turnkey's auth proxy, with `sendLoginCode` / `confirmLoginCode`, `loginWithPasskey` / `signUpWithPasskey` / `addPasskey`, `sendContactVerificationCode` / `confirmContactVerification`, `logout` and `authState` on `TurnkeyProvider`, and Ethereum + Solana account provisioning on first login. It compiles only with the opt-in and ships to hosts as the Rain wallet provider, `RainProvider` in `rain-wallet-android`, which exposes the same flow under Rain's names; see [Managed mode](#managed-mode-internal-api) and [rain-wallet-android/README.md](../rain-wallet-android/README.md). In both modes the provider also exports the wallet's recovery phrase and private keys, decrypted on the device. See [Key export](#key-export).

## Requirements

- `minSdk = 28` (matches Turnkey's requirement).
- Managed mode: nothing to initialize — the SDK configures Turnkey itself; the passkey flows also need a relying-party domain, see [Passkeys](#passkeys). Bring-your-own mode: the Turnkey Kotlin SDK initialized in your `Application.onCreate()`, with the passkey/auth-proxy/OAuth/OTP flow completed by the host app.
- Of Turnkey's artifacts, `com.turnkey:encoding` ships Java 24 class files; a JVM unit test in your app that loads its classes needs a JDK 24 launcher. Android builds are unaffected: R8/D8 dexes the bytecode regardless of the host JVM.

## Adding the dependency

```kotlin
dependencies {
    // Pulls rain-core-android and the Turnkey artifacts transitively.
    implementation("xyz.rain:rain-turnkey-android:5.0.0-beta.1")
}
```

An app that does not register Turnkey should not depend on this module: the Turnkey artifacts come with it and with `rain-wallet-android`, which wraps this module; no other Rain module pulls them. Internally the module pulls in:

```
com.turnkey:sdk-kotlin:2.0.2
com.turnkey:http:2.1.1
com.turnkey:types:2.1.0
com.turnkey:crypto:1.0.1
com.turnkey:encoding:1.0.0
com.turnkey:passkey:1.0.3
com.turnkey:stamper:1.0.3
androidx.credentials:credentials:1.5.0
androidx.credentials:credentials-play-services-auth:1.5.0
```

The module declares `androidx.credentials:credentials` itself, because its error mapping names the Credential Manager exception types by class; the other `androidx.credentials` artifact is the runtime passkey provider and arrives through Turnkey's passkey package, which puts the same version of both on every consumer's runtime classpath. No consumer sees a version change.

## Two modes

| Mode | Who authenticates | Config | Auth surface |
|---|---|---|---|
| **Bring-your-own** (public) | Your app, against Turnkey's Kotlin SDK | `TurnkeyConfig(turnkey = TurnkeyContext)` | None — the managed members (`sendLoginCode` / `confirmLoginCode`, the passkey and contact-attach methods, `logout`) throw `RainError.InvalidConfig`; `awaitSessionRestore` is a no-op, `hasActiveSession()` is `false` and `authState` is `Unauthenticated` |
| **Managed** (internal API, `@InternalRainTurnkeyApi`) | Rain SDK, through Turnkey's auth proxy | `TurnkeyConfig(application, organizationId, authProxyConfigId, passkeyDomain = ...)` | `TurnkeyProvider.sendLoginCode` (a `LoginContact.Email` or `LoginContact.Phone`) / `confirmLoginCode` / `loginWithPasskey` / `signUpWithPasskey` / `addPasskey` / `sendContactVerificationCode` / `confirmContactVerification` / `logout` / `authState` / `awaitSessionRestore` / `hasActiveSession` — compile only with the opt-in |

In both modes the hand-off is the same boundary: register the `TurnkeyProvider` with the `RainSdk` builder, then resolve `rain.provider(ProviderId.TURNKEY)`. Everything after that — `getWalletAddress()`, balances, sends, `withdrawCollateral()` — is identical. Key export (`exportRecoveryPhrase`, `exportPrivateKey`) works in both modes as well. See [Key export](#key-export).

## Bring-your-own mode (the public Turnkey integration)

If your app already drives Turnkey's Kotlin SDK — passkeys, OAuth, your own OTP UI — keep doing that and hand the authenticated context to Rain. Initialize Turnkey from your `Application.onCreate()`:

```kotlin
import com.rain.sdk.RainSdk
import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.TurnkeyConfig  // NOTE: Turnkey's config, not Rain's TurnkeyConfig
import com.turnkey.core.models.AuthConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1) Initialize Turnkey first (host-app responsibility).
        //    For OTP and auth-proxy flows, only organizationId + authProxyConfigId are required:
        TurnkeyContext.init(
            app = this,
            config = TurnkeyConfig(
                organizationId = "<your-parent-organization-id>",
                authProxyConfigId = "<your-auth-proxy-config-id>"
            )
        )

        // For passkey / OAuth flows you must also supply an AuthConfig with the relying-party id
        // (passkeys) and/or appScheme (OAuth deep-links):
        //
        // TurnkeyContext.init(
        //     app = this,
        //     config = TurnkeyConfig(
        //         organizationId = "<your-parent-organization-id>",
        //         authProxyConfigId = "<your-auth-proxy-config-id>",
        //         authConfig = AuthConfig(rpId = "<your-rp-id>"),
        //         appScheme = "<your-app-scheme>"
        //     )
        // )

        // Prefer `TurnkeyContext.initSuspend(app, cfg)` inside a coroutine if you need to await
        // session restoration before driving auth.

        // 2) Drive your auth flow (passkey / OTP / OAuth) somewhere in the app.
    }
}
```

Once the user is authenticated and `TurnkeyContext.session` is populated, hand it to Rain by
registering a `TurnkeyProvider`:

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyProvider

val rain = RainSdk.builder()
    .rpcEndpoints(
        mapOf(
            8453 to "https://mainnet.base.org",
            84532 to "https://sepolia.base.org"
        )
    )
    .register(
        TurnkeyProvider(
            TurnkeyConfig(
                turnkey = TurnkeyContext,
                walletAddress = null // omit to use the first Ethereum account from TurnkeyContext.wallets
                // sponsorGas defaults to true: sends are gas-sponsored, which needs sponsorship enabled
                // on the Turnkey organization. Pass sponsorGas = false to have users pay their own gas.
            )
        )
    )
    .build()

val client = rain.provider(ProviderId.TURNKEY)
```

`rain.provider(...)` is a `suspend` function — resolving the Turnkey provider probes the Turnkey
wallet list and throws `RainError.WalletUnavailable` if no usable Ethereum account is available
(in managed mode, resolving before a session is live throws `RainError.TokenExpired`).
You can register other adapters (e.g. `PortalProvider`) on the same builder and resolve each
independently; each provider resolves on its own.

## Managed mode (internal API)

Managed mode is not a host-facing mode. Every member of it — the `TurnkeyConfig(application, organizationId, authProxyConfigId, passkeyDomain = ...)` constructor, `sendLoginCode` / `confirmLoginCode` / `loginWithPasskey` / `signUpWithPasskey` / `addPasskey` / `sendContactVerificationCode` / `confirmContactVerification` / `logout` / `awaitSessionRestore` / `hasActiveSession` / `authState` / `currentAuthState`, the `managedOrganizationId`, `managedAuthProxyConfigId` and `managedPasskeyDomain` accessors, `LoginContact` and `TurnkeyAuthState` — is marked `@InternalRainTurnkeyApi`, a `@RequiresOptIn` annotation at error level: a host app that calls any of them gets a compile error naming the reason. It is the building block of the Rain wallet provider (`RainProvider` in `rain-wallet-android`), which exposes the same flow in Rain's own terms with no Turnkey types; that module opts in module-wide with `-opt-in=com.rain.sdk.turnkey.InternalRainTurnkeyApi`. The rest of this section documents the flow for that caller.

Before the first run, in the Turnkey dashboard: enable the **Auth Proxy** for your parent organization with **Email OTP** turned on, and **SMS OTP** as well when phone numbers are used (each auth method has its own toggle), copy its auth-proxy config id (it identifies the configuration and is safe to ship in the app; the Turnkey SDK sends it as the `X-Auth-Proxy-Config-ID` header on every proxy call), and set the code format (6–9 characters, numeric or alphanumeric; one setting shared by email and SMS) and the session lifetime (900 seconds by default) there. The SDK reads none of these settings; it obeys them. SMS authentication is a Turnkey Enterprise feature that Turnkey enables on request, off by default on top-level organizations. Leave the dashboard captcha off while this SDK pins Turnkey's Kotlin SDK 2.0.2: that SDK sends no captcha token, so an enabled captcha refuses every code request on both channels.

For passkeys, turn **Passkey** on in the same auth-proxy configuration (Rain's own configuration lists email, SMS and passkey) and pass `passkeyDomain` to the constructor: a registrable domain of at least two labels you control, such as `passkeys.example.com`, that serves `https://<domain>/.well-known/assetlinks.json` as `application/json` with no redirect, in the shape of Google's passkey example (the file under [Passkeys](#passkeys)): a statement for the site itself granting `delegate_permission/common.get_login_creds`, and an `android_app` statement granting both `delegate_permission/common.handle_all_urls` and `delegate_permission/common.get_login_creds` that lists the app's package name and the SHA-256 fingerprint of every certificate that signs it (debug, upload and Play App Signing). Google Play services fetches the file from the device at every ceremony and judges it itself; a file carrying the app statement alone is refused with `RAIN_102`, even when Google's asset-links API reports the link as valid, so start from that shape. The domain is permanent, because every passkey created against it stops working when it changes, and a passkey made for one domain does not work in an app on another. Null or blank turns the passkey methods off (they throw `RainError.InvalidConfig` before any Turnkey call); a scheme, port, path or a single label such as `localhost` throws `RAIN_102` from the constructor. On the device the flows need Android 9 or later; on Android 9 to 13 Google Play services (the passkey provider the SDK's dependencies bring), on Android 14 and later any installed credential provider; a screen lock and a provider with a signed-in account. An emulator needs a Play image with a Google account added. When Turnkey has enabled the `WEBAUTHN_ORIGINS` feature on your parent organization, its allowed origins must include the Android origin `android:apk-key-hash:<base64url SHA-256 of the signing certificate>`, one per certificate.

The SDK configures Turnkey against your parent organization and auth-proxy configuration, runs the one-time-code flow, email or SMS, and the passkey flows on the provider itself, and provisions one wallet holding an Ethereum and a Solana account on first login:

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.LoginContact
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyProvider

val provider = TurnkeyProvider(
    TurnkeyConfig(
        application = application,
        organizationId = "<your-parent-organization-id>",
        authProxyConfigId = "<your-auth-proxy-config-id>",
        passkeyDomain = "passkeys.example.com", // optional; omit it to run codes only
    )
)

// Reuse a restored session, or run the code flow:
provider.awaitSessionRestore()
if (!provider.hasActiveSession()) {
    provider.sendLoginCode(LoginContact.Email("user@example.com"))
    // or by SMS, with the phone number in E.164 form:
    // provider.sendLoginCode(LoginContact.Phone("+15551234567"))
    provider.confirmLoginCode(code) // sign-up or login + Ethereum/Solana account provisioning
    // or, with passkeyDomain set, a passkey through the system sheet, presented from the
    // foreground Activity: an existing account
    // provider.loginWithPasskey(activity)
    // or a new account whose only login is the passkey
    // provider.signUpWithPasskey(activity)
}

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(84532 to "https://sepolia.base.org"))
    .register(provider)
    .build()
val client = rain.provider(ProviderId.TURNKEY)
```

### Members

Each member, with its contract; all carry `@InternalRainTurnkeyApi`:

| Member | Signature | Notes |
| `sendLoginCode` | `suspend fun sendLoginCode(contact: LoginContact)` | Sends a one-time code by email or by SMS, according to the contact's type. Applies the one-shot Turnkey configuration when it is the first auth call; touches no existing session. Calling it again for the same contact replaces the pending challenge on success and keeps it on failure, which is how a code is resent (Turnkey codes expire after 5 minutes by default, lock after 3 wrong attempts, at most 3 active per user); calling it for another contact or channel retires the pending challenge before the request, so a failed switch leaves nothing confirmable. The contact is canonicalized once and the same string is sent on confirm: an email is trimmed and lowercased, because Turnkey matches it case-sensitively and a second spelling would be a second account; a phone number is trimmed, stripped of spaces, dots, hyphens and parentheses, and must then be E.164 (`+`, country code and number, digits only, at most 15); a parenthesised trunk zero such as `+44 (0) 20 ...` is refused rather than folded into a different number. Throws `RainError.InvalidConfig` for a blank email or a phone number outside E.164. SMS needs SMS OTP enabled on the auth-proxy configuration; a request the proxy refuses arrives with only its HTTP status, because Turnkey's Kotlin SDK drops the response body. |
| `confirmLoginCode` | `suspend fun confirmLoginCode(code: String)` | Confirms the code from `sendLoginCode`, whichever channel it went to. Sign-up or login (Turnkey decides). A sign-up creates one wallet holding an Ethereum and a Solana account inside the signup request; a login backfills a missing account onto the existing wallet. Stores the session under a fresh key and selects it. A rejected code throws `RainError.InvalidLoginCode` (`RAIN_203`) and keeps the challenge for a retry; so does any other failure inside the code check itself (for example a rejection the auth proxy wraps in an HTTP 500, which surfaces as `ProviderError`); the current session is untouched. A failure after the code was accepted drops the challenge; if that failure is the switch to the new session, the device is signed out as well (the login already revoked the previous session server-side) without firing `onSessionExpired`, while a provisioning failure keeps the new session. A successful login revokes the user's other Turnkey sessions on every device (`invalidateExisting`) and clears the previous local session. Throws `RainError.InvalidConfig` when no code was requested or the code is blank. |
| `loginWithPasskey` | `suspend fun loginWithPasskey(activity: Activity)` | Signs an existing user in with a passkey bound to the configured `passkeyDomain`, through the system passkey sheet presented from `activity`; the call suspends until the sheet closes, and cancelling the caller ends it with the cancellation, never a mapped error. The same session handling as `confirmLoginCode`: a fresh key, an explicit select over a live session, the previous session cleared, the user's other sessions revoked (`invalidateExisting`), a missing account backfilled. A dismissed sheet or no passkey for the domain throws `RainError.UserRejected` (`RAIN_401`) and leaves the current session untouched; no `passkeyDomain` throws `RainError.InvalidConfig` (`RAIN_102`) before any vendor call, and so does an association file that does not vouch for this build; any other refusal by the device or the backend, an HTTP status inside the ceremony included (no session exists yet, so it is never `RAIN_201` or `RAIN_202`), is `RainError.ProviderError` (`RAIN_501`); an unusable backend response inside the ceremony is `RainError.InternalError` (`RAIN_502`). After a successful ceremony the session switch and the account backfill can still throw `RAIN_201` or `RAIN_202`; a backfill failure keeps the session, which heals at resolution. Managed mode only. |
| `signUpWithPasskey` | `suspend fun signUpWithPasskey(activity: Activity)` | Creates a new account with a passkey as its only login method and one wallet holding an Ethereum and a Solana account inside the same request, then signs it in as `loginWithPasskey` does. Every call mints a fresh account: returning users sign in or add a passkey, and accounts never merge. Refused with `RainError.InvalidConfig` (`RAIN_102`) while a live session is stored as this device's current one (an expired one is cleared first, with `onSessionExpired` silent, and the sign-up proceeds), so call `logout()` first when `hasActiveSession()` is true: the vendor swaps its process-wide client for the whole ceremony and a wallet call made meanwhile would read the live session as dead. If the sign-up request succeeded but the login that followed failed, the account exists and `loginWithPasskey` reaches it; if the sign-up request itself failed, no account exists and the passkey the sheet created signs into nothing. Both arrive as `RAIN_501`. Codes as for `loginWithPasskey`. Managed mode only. |
| `addPasskey` | `suspend fun addPasskey(activity: Activity)` | Registers a passkey bound to the configured `passkeyDomain` on the signed-in account, so the next sign-in can use it. The session is checked (and refreshed when near its expiry) before the sheet, so `RainError.TokenExpired` (`RAIN_201`) fires before any biometric prompt; a session revoked server-side between that check and the registration still surfaces as `RAIN_201` after the prompt, with `onSessionExpired` firing, and a registration the backend refused leaves a passkey on the device that signs into nothing. The passkey request asks the credential provider for user verification as preferred, not required, so gate the call as you gate export. No new account and no session change; other authentication calls wait while the sheet is open. Each call registers another passkey with the credential provider. A dismissed sheet is `RAIN_401`; the backend refusing the registration is `RainError.Unauthorized` (`RAIN_202`); a ceremony the device refused, or a registration the backend failed (its per-user limit included), is `RAIN_501`. Managed mode only. |
| `sendContactVerificationCode` | `suspend fun sendContactVerificationCode(contact: LoginContact)` | Sends a verification code to a contact the signed-in user wants to attach to this account as a login method; distinct from `sendLoginCode`, which starts a login. Requires a live session (`RAIN_201` before anything is sent). The SDK adds no user-presence check before the attach beyond that session, which can be one restored at launch, so gate the call as you gate export (a biometric prompt or a fresh login) and show the user which contacts sign in to the account; the passkey requests ask for user verification as preferred, not required, so gate `addPasskey` the same way. The contact is canonicalized like a login contact and that string is what the account stores; calling it again for the same contact replaces the pending code, a call for another contact or channel retires it before anything is sent, and a login or a `logout` drops it. Accounts are never merged; what the confirm refuses, with which code, and the known issues of an attach are in the `confirmContactVerification` row below. `RAIN_102` for a blank or malformed contact; `RAIN_501` when the code request is refused. Managed mode only. |
| `confirmContactVerification` | `suspend fun confirmContactVerification(code: String)` | Confirms the code from `sendContactVerificationCode` and attaches the verified contact. The session is checked before the code is spent (`RAIN_201` otherwise). A rejected code throws `RainError.InvalidLoginCode` (`RAIN_203`) and keeps the challenge; a rejection the backend wraps in an HTTP 500 arrives as `RAIN_501` with the challenge kept too. Once the code is accepted, the backend's account lookup names the account the contact signs in to: another account, and the attach is refused with `RAIN_202` and nothing changes; this account or none, and the update runs. A failure after the code was accepted drops the challenge: `RAIN_201` when the session died meanwhile, `RAIN_202` when another account signs in with the contact or the backend refused the update, `RAIN_501` when the lookup or the update failed; request a new code. `RAIN_102` when no code was requested or the code is blank. An attach replaces an existing email address or phone number instead of adding a second one, a known issue. In `5.0.0-beta.1` it also succeeded for a contact that already logs in to another account; see the [CHANGELOG](../CHANGELOG.md). Managed mode only. |
| `logout` | `suspend fun logout()` | Clears the selected session (after waiting for a restore in flight to settle) without firing `onSessionExpired`; cached accounts are evicted and a pending login code and a pending contact verification are dropped. No-op when no session is selected. |
| `awaitSessionRestore` | `suspend fun awaitSessionRestore(timeoutMs: Long = 5_000)` | Waits for the vendor's asynchronous restore of a persisted session; a timeout returns normally and leaves `authState` at `Loading`. As the first auth call of a launch it also runs Turnkey's one-shot initialization first, which `timeoutMs` does not bound. Throws `RainError.InvalidConfig` on a configuration conflict (blank or different ids, a different `passkeyDomain`, or a `TurnkeyContext` initialized outside the SDK) and `RainError.InternalError` when Turnkey's initialization failed. |
| `hasActiveSession` | `fun hasActiveSession(): Boolean` | True when a live session has more than 30 seconds left — the code step can be skipped. The answer comes from the stored session, and a session Turnkey refuses to refresh is cleared with that failure, so this reads false from then on; see [Session expiry, refresh, and retry](#session-expiry-refresh-and-retry). In `5.0.0-beta.1` a session revoked by a login on another device kept reading true until its own expiry passed, see the [CHANGELOG](../CHANGELOG.md). |
| `authState` / `currentAuthState()` | `val authState: Flow<TurnkeyAuthState>` / `fun currentAuthState(): TurnkeyAuthState` | `Loading` / `Authenticated` / `Unauthenticated`; a view over `sessionState` with `Expired` collapsed into `Unauthenticated`. Reads `Loading` until the first auth call has configured Turnkey and its restore has settled. |
| `LoginContact` | `sealed interface LoginContact { val value: String }` with `data class Email(value)` and `data class Phone(value)` | Where the code goes and the identity the account is keyed on: a first login signs the user up under this contact; a later login finds the account by email for `Email` and by phone number for `Phone`. The same person arriving through the other channel is a new account, with its own sub-organization and wallet, unless the contacts were linked outside the SDK. `value` is the string as given; `toString()` hides it. Marked `@InternalRainTurnkeyApi`. |

Resolving a managed provider before a session is live throws `RainError.TokenExpired`; resolution re-checks the account set, so a login whose provisioning failed heals itself. The Turnkey configuration is one-shot per app launch, applied by the first auth call or by resolution: blank ids, ids that differ from the ones this launch was configured with, or a `TurnkeyContext` the app initialized itself make every auth call throw `RainError.InvalidConfig`; a failed Turnkey initialization makes them throw `RainError.InternalError` until the app relaunches.

### Passkeys

`loginWithPasskey(activity)` signs an existing user in through the system passkey sheet; `signUpWithPasskey(activity)` creates a new sub-organization with the passkey as its only root credential and the same wallet a code sign-up gets, then signs it in; `addPasskey(activity)` registers another passkey, bound to `passkeyDomain`, on the signed-in account. All three take the foreground `Activity` the sheet is presented from, suspend until it closes, and end with the caller's cancellation, never a mapped error, when the calling coroutine is cancelled; the SDK keeps the Activity no longer than the call. Every passkey login reuses the code login's session handling: the session lands under a fresh per-attempt key, is selected explicitly over a live session, the superseded session is cleared, the user's other sessions are revoked (`invalidateExisting`), and a missing account is backfilled. That switch and backfill run after the ceremony, so a login that succeeded can still end in `RainError.TokenExpired` or `RainError.Unauthorized`; a backfill failure keeps the session, which heals at resolution, and an unusable backend response inside the ceremony is `RainError.InternalError`. Without a `passkeyDomain` the three throw `RainError.InvalidConfig` before any Turnkey call. `signUpWithPasskey` is refused with `RainError.InvalidConfig` while a live session is stored as this device's current one (an expired one is cleared first, with `onSessionExpired` silent, and the sign-up proceeds), so call `logout()` first when `hasActiveSession()` is true, because Turnkey's Kotlin SDK swaps its process-wide client for a temporary-key client for the whole sign-up ceremony, and a wallet call made meanwhile would read the live session as dead. Every call to it mints a fresh account; returning users sign in or add a passkey, and accounts never merge. If the sign-up request succeeded but the login that followed failed, the account exists and `loginWithPasskey` reaches it; if the sign-up request itself failed, no account exists and the passkey the sheet created signs into nothing. Both arrive as `RAIN_501`. The passkey a sign-up registers is named `passkey-<unix seconds>`, a cross-platform contract shared by Rain's SDKs. `addPasskey` checks the session before the sheet; a session revoked server-side between that check and the registration still surfaces as `RainError.TokenExpired` after the prompt, and a registration the backend refused leaves a passkey on the device that signs into nothing. The passkey requests ask the credential provider for user verification as preferred, not required, so gate `addPasskey` as you gate export.

The association file the device accepts, in the shape of Google's passkey example; the sample's file at `https://passkeys.uptop.xyz/.well-known/assetlinks.json` follows it:

```json
[
  {
    "relation": ["delegate_permission/common.get_login_creds"],
    "target": { "namespace": "web", "site": "https://passkeys.example.com" }
  },
  {
    "relation": [
      "delegate_permission/common.handle_all_urls",
      "delegate_permission/common.get_login_creds"
    ],
    "target": {
      "namespace": "android_app",
      "package_name": "com.example.app",
      "sha256_cert_fingerprints": ["<SHA-256 of each signing certificate, colon-separated hex>"]
    }
  }
]
```

### Attaching a contact

`sendContactVerificationCode(contact)` sends a one-time code to an email or phone the signed-in user wants to attach to this account as a login method, and `confirmContactVerification(code)` verifies it and sets the account's email or phone through Turnkey's update-user activities. The code is verified without logging in, since the SDK asks the auth proxy for a verification token with a throwaway key it deletes afterwards, then submits the update under the live session, so the session is required and checked before the code is spent (`RainError.TokenExpired` otherwise). The pending challenge is separate from the login code's, so neither flow can consume the other's code; a second send for the same contact replaces it and a send for another contact or channel retires it first, so a failed switch leaves nothing confirmable; it is kept on a rejected code (`RainError.InvalidLoginCode`, or `RainError.ProviderError` for a rejection the proxy wraps in an HTTP 500), dropped once the code was accepted, and dropped by a login or a logout. Once the code is accepted, the SDK asks the auth proxy's account lookup (`POST /v1/account`, with the verification token) which sub-organization signs in with the contact, and refuses the attach before anything changes when it is another one, because Turnkey's update-user activity accepts such a contact and would replace this account's contact while the other account kept signing in with it; this one or none, and the update runs. No session stamps the lookup. The codes for the refusal, for a failed lookup and for a failed update, and the known issues of an attach, are in the `confirmContactVerification` row above. An attach is meant to add a login method, after which that contact signs in to this account with `sendLoginCode` / `confirmLoginCode`; wallets never move either way. The SDK adds no user-presence check before the attach beyond the live session, which can be one restored at launch, so gate the two calls as you gate export (a biometric prompt or a fresh login) and show the user which contacts sign in to the account; the passkey requests ask for user verification as preferred, not required, so gate `addPasskey` the same way.

- `authState` (a `Flow<TurnkeyAuthState>`, snapshot via `currentAuthState()`) reports `Loading` / `Authenticated` / `Unauthenticated`. It is a view over the same derivation as `sessionState`, collapsing an expired session into `Unauthenticated` — a login screen only needs to know whether a code is required. Until the first auth call (`awaitSessionRestore`, `sendLoginCode`, …) has configured Turnkey and its restore has settled, `authState` reads `Loading` and `hasActiveSession()` is `false` — call `awaitSessionRestore()` before reading either.
- A rejected code throws `RainError.InvalidLoginCode` (`RAIN_203`) and keeps the challenge, so the user can retype it. Any other failure inside the code check itself also keeps the challenge; a rejection the auth proxy wraps in an HTTP 500 surfaces as `RainError.ProviderError` (`RAIN_501`) and is retryable, see the error table below.
- Turnkey's limits on the code flow, which the SDK cannot see and your UI has to design around: a code is valid for 5 minutes by default (up to 10 in the dashboard), locks after 3 wrong attempts, and at most 3 codes can be active per user — an expired or locked code surfaces as the same `RAIN_203` (or `ProviderError`) as a typo. Request a new code by calling `sendLoginCode` again with the same contact; it replaces the pending challenge on success and keeps it on failure. A call for another contact or channel retires the pending challenge before the request, so a failed switch leaves nothing confirmable. Codes may be alphanumeric, so never force a numeric keyboard, for SMS as much as for email: the code format is one shared setting. Turnkey also limits code requests to 3 per 3 minutes per user identifier, but the auth-proxy request carries no such identifier, so per-user throttling and restricting the destination countries you serve are your app's job. SMS is an Enterprise feature Turnkey's team enables per customer, and its [SMS authentication page](https://docs.turnkey.com/features/authentication/sms) lists the destination countries it delivers to and the price of each; Turnkey's own guidance treats SMS as spend an attacker can drive.
- An email is trimmed and lowercased before any proxy call: `LoginContact.Email(" Jo@Example.com ")` sends `jo@example.com`. Turnkey matches an email case-sensitively, so without this `Jo@Example.com` and `jo@example.com` would sign up as two sub-organizations with two wallets. The lowercased string is the account's identity: it is sent on confirm, used for the account lookup, and stored by a contact attach. An account that signed up with a mixed-case email under 5.0.0-beta.1 is keyed on that exact spelling, so a lowercased login does not find it and signs up a new, empty account. Rain cannot change the stored spelling, since the user is the sub-organization's only root; before upgrading, have such a user sign in on the current build and export the recovery phrase or move the funds out.
- A phone number is trimmed, stripped of spaces, dots, hyphens and parentheses, and must then be E.164 (`+`, country code and number, at most 15 digits): `LoginContact.Phone("+1 (555) 123-4567")` sends `+15551234567`. A national number without its country code is refused with `RainError.InvalidConfig` before any proxy call, and so is a parenthesised trunk zero such as `+44 (0) 20 ...`, which stripping would turn into a different number; convert national formats in your app first (the sample uses `PhoneNumberUtils.formatNumberToE164` with the device's region). The canonical string is the account's identity, so the same string is sent on confirm and used for the account lookup.
- Turnkey's sandbox simulates SMS delivery: the test number `+1 999-999-9999` with the code `000000` works once the proxy's code format is numeric and 6 characters, a setting that changes email codes on the same configuration too.
- A successful login revokes the user's other Turnkey sessions server-side (`invalidateExisting`) and clears the previous local session once the switch to the new one succeeded. The signed-out device learns of it at its next refresh: a wallet call that refreshes (with `autoRefresh` on) or `refreshSession()`. Turnkey refuses to refresh the revoked session (HTTP 401), the SDK clears the stored session, `onSessionExpired` fires once, and `hasActiveSession()`, `currentAuthState()` and `currentSessionState()` read signed out as soon as that call returns; see [Session expiry, refresh, and retry](#session-expiry-refresh-and-retry). Until then the revoked session still reads as active, because the stored session carries only its local expiry. In `5.0.0-beta.1` nothing cleared it, see the [CHANGELOG](../CHANGELOG.md).
- `logout()` clears the selected session — after waiting for a restore in flight to settle — and does **not** fire `onSessionExpired`; cached accounts are still evicted and a pending login code and a pending contact verification are dropped. With no session selected it is a no-op.
- A first sign-up creates its wallet inside the signup request: one wallet named `Wallet`, 12-word mnemonic, Ethereum `m/44'/60'/0'/0/0` (secp256k1) and Solana `m/44'/501'/0'/0'` (ed25519) — a cross-platform contract shared by Rain's SDKs, so a user provisioned on one platform resolves identically on another. Afterwards, missing accounts are added to the wallet Rain resolves (`createWalletAccounts`); only an organization with no wallet at all gets a new one, so the user has a single mnemonic to back up either way.
- A sign-up creates one Turnkey **sub-organization** per end user under your parent organization. The user is its only root user, with the credential the flow used: the verified contact, email address or phone number, for a code sign-up, or the passkey for a passkey sign-up; `addPasskey` adds more later, and the contact-attach methods are meant to (see [Attaching a contact](#attaching-a-contact) for what an attach refuses and what it replaces today). The root quorum is the user alone: the parent organization has read-only visibility and can neither reach the keys nor sign. An account is keyed on the credential it signed up with: the same person logging in by email once and by SMS once, or by a code once and with `signUpWithPasskey` once, gets two sub-organizations, two wallets and two mnemonics, unless the second login method was attached to the first account while signed in (`addPasskey`, or `sendContactVerificationCode` / `confirmContactVerification`). Accounts never merge. Treat one credential as one account, or attach the second login method to the existing account before offering it to existing users. An SMS sign-up gets Turnkey's default names for the root user and the organization, since there is no email to name them after. The wallet above is created inside that same signup request, and every session and wallet call is scoped to the sub-organization. Deleting a sub-organization is a root-user activity that requires its wallets to have been exported first (or `deleteWithoutExport`) and is not exposed by this SDK.
- The Turnkey configuration is one-shot per app launch and is applied by the first auth call (`awaitSessionRestore`, `sendLoginCode`, `confirmLoginCode`, the passkey and contact-attach methods, `logout`) or by provider resolution, whichever comes first. Blank ids, a second managed provider with *different* ids or a different `passkeyDomain`, or a `TurnkeyContext` your app initialized itself make every auth call throw `RainError.InvalidConfig` — relaunch to change ids, or use bring-your-own mode. If Turnkey's own initialization fails or never finishes, every auth call throws `RainError.InternalError` until the app relaunches. Wrong but well-formed ids, SMS OTP not enabled on the proxy configuration, or an undeliverable number are not detected at configuration time: the first auth-proxy call fails instead, and the code request comes back with an HTTP status and no reason (the Kotlin SDK drops the response body), surfaced as `RainError.ProviderError`.
- Resolving the provider before a session is live throws `RainError.TokenExpired`. Resolution also re-checks the account set, so a login whose provisioning failed heals itself without a new code.
- Managed mode needs an `android.app.Application` because Turnkey's Kotlin SDK stores sessions and device keys through it.

The Rain wallet module is this flow's caller; the sample app drives it through `RainSession.prepareRainWallet` / `initializeRainWallet` and `HomeViewModel`. The sample's Turnkey tab is bring-your-own (`TurnkeyAuthSample` plus `RainSession.initializeTurnkey`) and touches none of these members.

## What Rain uses Turnkey for

After the Turnkey-backed `client` is resolved, every wallet operation routes through Turnkey:

| Rain operation | Turnkey API used |
|----------------|------------------|
| `client.getWalletAddress()` | `TurnkeyContext.wallets` (first Ethereum account) |
| `client.getBalance(chainId, Token.Native)` | `TurnkeyClient.getWalletAddressBalances` (CAIP-19 `slip44:` filter) on supported chains, when the organization has the balance feature; RPC `eth_getBalance` otherwise, and when Turnkey refuses the read (HTTP 403 for an organization without the feature, logged once per provider, after which the provider reads the chain without asking again; or any other HTTP status, retried first when transient, logged as a warning each time; after such a refusal a chain with no RPC endpoint surfaces the refusal, not `RAIN_102`). A dead session or a transport failure surfaces as its own error |
| `client.getBalance(chainId, Token.Contract(...))` | RPC `eth_call` (`balanceOf`) |
| `client.getTokenBalances(chainId)` | `TurnkeyClient.getWalletAddressBalances` (CAIP-19) on supported chains, when the organization has the balance feature, plus one Multicall3 / parallel `eth_call` batch per call for the registry and registered tokens, merged in for the tokens Turnkey's answer left out ([getTokenBalances](METHODS.md#gettokenbalanceschainid) says what the chain read costs and when it is skipped); Multicall3 / parallel `eth_call` over the registry and registered tokens alone otherwise, and when Turnkey refuses the read with HTTP 403, the feature gate, handled as the native row says (a chain with no RPC endpoint then fails with `RAIN_102`, the setting to fix). Any other refused status surfaces as its own error, because the chain's token list is not the backend's: the two each list tokens the other lacks. A dead session or a transport failure surfaces as its own error |
| `client.sendNative(...)` / `client.sendToken(...)` | `TurnkeyClient.ethSendTransaction` + `getSendTransactionStatus` polling. Only on Turnkey's managed-broadcast chains — other chains (Avalanche, Celo, ZKsync, Plasma, Ink) are read-only and sends throw `RAIN_104` up front. By default (`sponsorGas = true`), every EVM send (transfers, withdrawals, approvals, raw sends) is sponsored by Turnkey Gas Station (minimal payload carrying Turnkey's gas-station nonce for replay protection; fee estimates quote what the wallet would pay itself), and Solana network fees are sponsored too (a zero-SOL sender skips the fee check and dry run; rent for a new recipient token account is a separate Turnkey toggle and stays with the sender). `TurnkeyConfig(sponsorGas = false)` returns to self-paid sends, and is required on a Turnkey organization without sponsorship enabled. Monad caveat: Turnkey sponsors through EIP-7702 delegation and Monad reverts any delegated-account transaction that would leave the balance under 10 MON, so a sponsored native MON send from a small wallet fails on chain even when the estimate succeeds (token sends are unaffected). |
| `client.withdrawCollateral(...)` | EVM: `TurnkeyContext.signRawPayload` (EIP-712) + `ethSendTransaction`. Solana: core composes the withdrawal, skipping its self-paid dry run while the adapter sponsors the fee, then `solSendTransaction` (see [Solana notes](#solana-notes)). Either chain: a chain outside Turnkey's coverage is refused with `RAIN_104` before anything is read or signed (`prepareWithdrawal` is not refused, because it never broadcasts), and a status carrying decoded revert details (`error.revertChain` or `error.eth.revertChain`, whether the transaction failed before inclusion or was included and reverted, or a Solana `InstructionError` or the runtime's `Program <id> failed` log line) surfaces as `WithdrawalRevertedByNetwork`, the same as a failed dry run, with the transaction hash on `transactionId` (and in the cause's message) once included; a failed status without them (a broadcast, policy or blockhash failure) is `ProviderError`, and a Solana fee or rent shortfall is `InsufficientFunds`. |
| `client.getTransactions(...)` | `TurnkeyClient.listEthTransactionHistory`, the indexed history (receives and externally submitted transactions included, EVM addresses in EIP-55 form), when the transaction history feature is enabled for the organization; otherwise `TurnkeyClient.getActivities` filtered to `ACTIVITY_TYPE_ETH_SEND_TRANSACTION`, sends only. The fallback runs only when Turnkey refuses the indexed query (HTTP 403 for an organization without the feature, logged once per provider); a dead session, a transport failure or a page that could not be decoded surfaces as its own error. |
| `client.estimateGas(...)` | RPC `eth_estimateGas` + `eth_gasPrice` on every chain. On a sponsored chain the quote is what the wallet would pay itself; a sponsor pays instead and its own cost is not quoted |

On Solana chain ids the same methods route to `TurnkeyClient.solSendTransaction` /
`getWalletAddressBalances` and the Solana account instead — see [Solana notes](#solana-notes).

Turnkey enables the balance service, the indexed transaction history and Gas Station per
organization. An organization on Turnkey's Free plan answered HTTP 403 to all three queries
(`get_wallet_address_balances`, `list_eth_transaction_history`, `get_nonces`) in Rain's testing;
Turnkey's own pages state the plan requirement only for gas sponsorship, an Enterprise feature per
[Transaction management](https://docs.turnkey.com/features/transaction-management#gas-sponsorship-aka-gas-abstraction-gasless-transactions-fee-abstraction).
The SDK keeps working without them: balances and history fall back as the rows above say, and sends
need `TurnkeyConfig(sponsorGas = false)`.

> **Known issue in `5.0.0-beta.1`.** The two balance reads have no fallback: on an organization
> without the balance feature, `getBalance(chainId, Token.Native)` and `getTokenBalances` fail with
> `RAIN_202` on the chains the service covers, and `getAllBalances` lists nothing for them. See the
> [CHANGELOG](../CHANGELOG.md).

## Solana notes

> **Known issue in `5.0.0-beta.1`.** Every Solana send through the Turnkey adapter fails with
> `RAIN_501` before anything reaches the chain, because Turnkey rejects the request with HTTP 400.
> That covers `sendNative`, `sendToken` and `withdrawCollateral`. Solana reads and
> `prepareWithdrawal` work. See the [CHANGELOG](../CHANGELOG.md).

The Turnkey adapter is the SDK's multi-chain provider (it advertises `MULTI_CHAIN`): Solana sentinel
chain ids (`RainChain.SOLANA_MAINNET` 900 / `SOLANA_DEVNET` 901 / `SOLANA_TESTNET` 902) route
`getWalletAddress(chainId)`, balances, `sendNative`, `sendToken`, `withdrawCollateral`, and
`getTransactions` to the Turnkey Solana account. Sends broadcast on mainnet and devnet only; testnet (902)
is read-only and a send there throws `RAIN_104`. The broadcast chain list is under
[Rain wallet provider](METHODS.md#rain-wallet-provider).

- **Transfers.** Composition and every preflight live in core's `SolanaTransferComposer` (shared with
  the Privy adapter, so the two cannot drift); the adapter only signs and broadcasts. For SPL that
  covers recipient validation, resolving the mint's decimals and owning token program on chain,
  deriving both associated token accounts, creating the recipient's when missing
  (`CreateIdempotent`, ~0.002 SOL rent paid by the sender), fee checks, and a `simulateTransaction`
  dry run (with `sponsorGas` the fee check and dry run are skipped, the rent check stays, and the
  System Program is carried among the static account keys because Turnkey's sponsored-flow rules
  require it). Failures
  surface as `TokenNotFound`, `TokenAccountNotFound`,
  `InsufficientTokenBalance`, or `InvalidRecipient`.
- **Balances.** From Turnkey's `get-balances` where it indexes the cluster; where it doesn't (devnet
  in particular), `getTokenBalances` discovers holdings from the node via `getTokenAccountsByOwner`
  against both token programs. Solana keeps token metadata off chain, so symbol / name stay null
  unless the mint is registered. These reads fall back to the node on any failure but a dead
  session, a transport failure included, and log no feature gate; the EVM reads in the table above
  fall back on a refusal only.
- **History.** From Turnkey's indexed history (`list_sol_transaction_history`) when the transaction
  history feature is enabled for the organization: receives included, and the row's hash is the real
  signature. Otherwise from the activity log, sends only, whichever of the two activity types Turnkey
  recorded the send under (`ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2`, which the SDK posts, or
  `ACTIVITY_TYPE_SOL_SEND_TRANSACTION`), where the row's hash is the Turnkey status id (the activity
  id when the send recorded none), not an explorer-resolvable signature.
- **The send request.** The broadcast is Turnkey's `sol_send_transaction` activity through the
  vendor client's `solSendTransaction`, posted as `ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2` with
  `signWiths` since `com.turnkey:http` 2.1.1; 2.1.0 posted the V1 type with that body and Turnkey
  refused it with HTTP 400. The module keeps its own catalog entry for `com.turnkey:http`, so an
  http-only fix from Turnkey is a one-line bump ahead of the version `sdk-kotlin` pins. An answer
  below 500 to the submit is a refusal. Nothing was executed, and the send fails as any refused
  request does.
- **After acceptance.** Once Turnkey has accepted the activity the call reports a failure only when
  the send has settled, with one residue described at the end of this note. The vendor's client
  returns the activity only when it completed within its poll (up to five reads over about four
  seconds) and otherwise throws and drops the activity id, whether the activity was still pending, a
  poll read failed, the answer was lost or did not decode. A 5xx answer to the submit itself is
  treated the same way, since Turnkey may have created the activity before a gateway failed. The
  adapter keeps every such failure inside the send (a poll-phase 401 never re-runs the submit) and
  reads the activity back from the activity log by its unsigned transaction: once after the vendor's
  own no-result throw, and twice, one polling interval apart, after any other failure (a 5xx answer to
  the submit, a failed poll read, a lost or undecodable answer). While
  Turnkey is still executing the activity it is read again by id at the polling interval, up to ten
  times. Then it is classified. Completed with a status id continues to the status poll. Failed or
  rejected before broadcasting anything (a policy denial, for example) is a `ProviderError` with
  Turnkey's reason, and nothing moved. Still pending, or completed without a status id this build can
  read, surfaces as `TransactionPending` carrying the activity id (no call takes it; it matches
  `uniqueId` on the activity-log history row). A send status Turnkey reports as failed maps as in the
  table above. The residue is the activity that cannot be read back at all. The send then surfaces as
  a `ProviderError` that says the send may still land and names the vendor's failure. That error is
  not retry-safe; read history before sending again.
- **Encoding.** Turnkey takes `unsignedTransaction` hex-encoded, the full wire format with zeroed
  signature placeholders, so Rain sends hex. Turnkey returns a status id rather than a signature; Rain polls for it, then
  recovers it from `getSignaturesForAddress` (newer than the pre-send baseline only) and verifies
  via `getTransaction` that the candidate is signed by this wallet (the fee payer on a self-paid
  send; on a sponsored send Turnkey rebuilds the transaction with its own sponsor as the fee payer and
  signs it with this wallet and the sponsor, so the check is on signers) with
  `err == null`. If the
  baseline read failed or nothing verifiable lands in time, the send surfaces as
  `TransactionPending` carrying the status id, the same contract as EVM, never the status id
  posing as a signature.
- **Collateral withdrawal.** Authorized differently from EVM: the coordinator executor signs a
  keccak-encoded withdraw message off chain (that is the admin signature the Rain API returns). Core
  composes a two-instruction transaction — a native ed25519 proof that the executor signed that exact
  message, then the program's `withdraw_single_signer_collateral_asset` — reading the collateral
  account, its coordinator's executors, and the mint's token program from chain, and deriving the
  collateral-authority PDA and token accounts locally. It simulates (self-paid only; a fee-sponsored
  provider skips the dry run on `withdrawCollateral`, never on `prepareWithdrawal`), then hands the bytes to the
  adapter, which signs them **as-is**: re-serializing would invalidate the embedded signature.
  `proxyAddress` is the collateral account, `tokenAddress` the SPL mint; single-signer collateral
  only. `prepareWithdrawal` returns the prepared unsigned transaction with its blockhash.

## Signing

EIP-712 signing uses `TurnkeyContext.signRawPayload` with `PAYLOAD_ENCODING_EIP712` + `HASH_FUNCTION_NO_OP`. Rain normalizes the returned `r`, `s`, `v` components into a `0x`-prefixed 65-byte hex signature compatible with `eth_signTypedData_v4` responses (recovery id auto-adjusted to 27/28 range when needed).

## Key export

`TurnkeyProvider` exports the wallet's recovery phrase and one private key per chain family, in bring-your-own and managed mode alike. The methods live on the descriptor, so a host can offer a backup right after login, before the SDK is built.

```kotlin
val provider = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext))

val phrase = provider.exportRecoveryPhrase()                              // space-separated BIP-39 words
val ethereumKey = provider.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM)   // "0x" + 64 lowercase hex characters
val solanaKey = provider.exportPrivateKey(TurnkeyKeyFamily.SOLANA)       // plain Base58 of the 64-byte seed || public key
```

**Formats**, a cross-platform contract shared by Rain's SDKs:

- Recovery phrase: the wallet's BIP-39 phrase as the wallet was created. Managed wallets have 12 words. A bring-your-own wallet has the length it was created with.
- Ethereum: the 32-byte secp256k1 key as `0x` plus 64 lowercase hex characters, the form MetaMask's *Import account* accepts.
- Solana: the 64-byte keypair, the seed followed by the ed25519 public key, in plain Base58 with no checksum, the form Phantom's *Import private key* accepts.

**Which wallet and account.** Keys follow the accounts the SDK signs with. The Ethereum key is the account behind `getWalletAddress()`, including a `walletAddress` override, and the Solana key is the account behind the Solana address. The phrase is the wallet holding that Ethereum account, else the first wallet, so the phrase and the Ethereum key always derive the same address. It restores every account derived from that wallet's seed and nothing else. A managed wallet keeps both accounts on one seed, so one phrase covers both. A bring-your-own organization with several wallets gets one wallet's phrase, and a key may come from another wallet. Before returning anything the SDK checks that the key is 32 bytes and that the address it derives, on either curve, is the account's. A `walletAddress` that names no Ethereum account of the organization fails every export before any export call, the Solana key included, because the provider it configures cannot sign either.

**Session.** Export is a Turnkey submit activity, so it needs a session that can submit; the one-time-code login produces one. Any live session suffices, including one the vendor restored from disk at launch: the SDK performs no re-authentication of its own, so a host that wants a fresh login or a biometric prompt before export adds it. A missing session, or one Turnkey refuses to submit with, a read-only bring-your-own session for example, fails the call; [Key export errors](#key-export-errors) has the mapping. In managed mode the first export of a launch runs the one-shot configuration first, like every auth call. Export is the backup path for a user who signs in with a passkey only.

**Timing and retries.** The vendor polls the export activity for up to about four seconds; an activity still pending after that fails the call ([Key export errors](#key-export-errors)). A transient failure (408, 429, 5xx, a dropped connection) is retried with a new request, so Turnkey's audit log may show more than one export activity for one call. Turnkey marks the wallet as exported after a phrase export, and its delete-wallet and delete-sub-organization activities key on that flag, so a phrase export changes what an organization can later delete without a force option.

**Host duties.** The SDK decrypts on the device and hands the value back once. It never logs, caches or persists it. After the return, gate the call, for example behind biometrics, show the value where screenshots and screen recording are blocked, and keep it off the clipboard or clear it. The sample app's *Export keys* card shows the screenshot block and the clipboard handling; it adds no biometric gate. See [app/README.md](../app/README.md). An exported phrase or key lives outside Turnkey's enclave from then on, and neither Rain nor the SDK can rotate it for the user. Once the user has imported it into another wallet, or recovered with it, encourage them to move the funds to a key the export never touched and retire the exported one.

**What leaves the device.** The wallet id or the account address, the organization id and a fresh P-256 public key go to Turnkey's export endpoints; an enclave-signed bundle encrypted to that key comes back and is decrypted on the device. Nothing goes to Rain.

**Capability resolution.** `TurnkeyProvider` advertises `Capability.EXPORT`. `rain.first { Capability.EXPORT in it.capabilities }` returns the earliest registered match, so a host that registered Turnkey before Portal or Privy gets the Turnkey client. That client has no export method; the methods live on the `TurnkeyProvider` the host registered.

**Checking an import.** Restore the phrase in MetaMask: the first Ethereum account equals `getWalletAddress()`. Restore it in Phantom: the Solana account equals the Solana address. Import the Ethereum key in MetaMask and the Solana key in Phantom: the same two addresses.

**Minified builds.** Export makes the vendor's `decryptExportBundle` reachable. Its Solana branch, which Rain never takes because it builds the Solana keypair itself, calls a helper in `com.turnkey:encoding` that links `org.bitcoinj.core.Base58`, a class bitcoinj 0.17.1 (the project's security floor) does not ship. The adapter's published consumer rules carry `-dontwarn org.bitcoinj.core.Base58`, so a minified host build does not fail on the dangling reference. The branch is dead code on this classpath. The rule reaches the host's whole R8 configuration, so host code that references `org.bitcoinj.core.Base58` itself, or calls the vendor's Solana export format directly, is silenced too and throws `NoClassDefFoundError` at runtime; move such code to `org.bitcoinj.base.Base58` or the SDK's export.

## Accessing the Turnkey instance

Rain exposes no vendor getters (core references no concrete vendor type). In bring-your-own mode you already own the `TurnkeyContext`
you authenticated and passed to `TurnkeyConfig`. In managed mode the SDK configured that same
process-wide `TurnkeyContext` object; it is reachable because it is a public vendor type, but treat
it as read-only — creating, selecting or clearing sessions behind Rain's back is unsupported, and
the vendor configuration itself is one-shot per launch. In both modes Rain clears the selected
session when Turnkey refuses to refresh it, see
[Session expiry, refresh, and retry](#session-expiry-refresh-and-retry).

## Error handling

Turnkey-specific errors are mapped into the standard `RainError` hierarchy. Whichever vendor wrapper carries a Turnkey HTTP failure and whichever row below produces the error, a `ProviderError` or `InternalError` reaches the host with the call and the status only, as `HTTP error from <path or activity>: <status>`; the response body never does.

| Turnkey error | Mapped to |
|---------------|-----------|
| `TurnkeyKotlinError.InvalidSession` | `RainError.TokenExpired` |
| `TurnkeyKotlinError.FailedToVerifyOtp` carrying an auth-proxy HTTP 400 / 401 / 403 (managed mode) | `RainError.InvalidLoginCode` (`RAIN_203`) — the code was refused; 408 / 429 / 5xx are not a refused code and surface as `RainError.ProviderError` (`RAIN_501`); the auth path does not retry them. A rejection the proxy wraps in an HTTP 500 (real status only in the response body) is `RAIN_501`, because the Kotlin SDK drops the body before Rain sees it; the challenge is still kept for a retry |
| `TurnkeyKotlinError.FailedToInitOtp`, any auth-proxy HTTP status (managed mode) | `RainError.ProviderError` (`RAIN_501`): the code request failed. No session exists while a code is requested, so a 401 or 403 here is not `TokenExpired` or `Unauthorized`. The reason (the channel not enabled on the proxy configuration, an undeliverable number, a rate limit) is in the response body the Kotlin SDK drops, so only the status reaches the message. Request the code again or check the configuration |
| The auth-proxy account lookup inside `confirmContactVerification`, any HTTP status (managed mode) | `RainError.ProviderError` (`RAIN_501`): no session stamps the lookup, so a 401 or 403 there is not `TokenExpired` or `Unauthorized`. The lookup's answer, another account signs in with the contact, is `RainError.Unauthorized` (`RAIN_202`) and comes from the response, not from an HTTP status |
| `TurnkeyKotlinError.FailedToLoginWithPasskey` / `FailedToSignUpWithPasskey`, `TurnkeyPasskeyError`, `TurnkeyStamperError` (managed passkey flows) | By the decisive cause in the chain. A Credential Manager cancellation, a `NoCredentialException` or a `NotAllowedError` DOM error whose message says cancelled: `RainError.UserRejected` (`RAIN_401`), the current session untouched. A `SecurityError` or `DataError` DOM error: `RainError.InvalidConfig` (`RAIN_102`), the association file, the fingerprint or the domain does not match this build, or the file lacks the site's own statement (see [Passkeys](#passkeys)); current Play services builds report that check as `DataError` (their code 50152), older ones as `SecurityError`. Any auth-proxy or Turnkey API HTTP status inside a passkey login or sign-up: `RainError.ProviderError` (`RAIN_501`), because no session exists yet, so it is never `RAIN_201` or `RAIN_202`; the body is discarded as for the code flow. A missing passkey provider (`*ProviderConfigurationException`) or any other Credential Manager exception: `RAIN_501`. A nested `KeyAlreadyExists` or `InvalidResponse`: `RainError.InternalError` (`RAIN_502`) |
| Turnkey API HTTP 401 | `RainError.TokenExpired` |
| Turnkey API HTTP 403 | `RainError.Unauthorized` |
| `TurnkeyKotlinError.FailedToExportWallet`, and the checks the adapter runs around an export | [Key export errors](#key-export-errors) below: one table, in the order the checks run |
| Config / setup errors (`MissingRpId`, which managed mode cannot raise, since the SDK hands the domain to the backend configuration up front and refuses the flow with `RAIN_102` when none is configured; `MissingConfigParam`, `ClientNotInitialized`, `InvalidParameter`, `InvalidResponse`, `InvalidMessage`, `InvalidRefreshTTL`, `OAuthStateMismatch`, `KeyAlreadyExists`, `KeyNotFound`) | `RainError.InternalError` |
| Wrapper errors whose underlying cause is a user cancellation | `RainError.UserRejected` |
| A failed `getSendTransactionStatus` after a send | `RainError.WithdrawalRevertedByNetwork` (withdrawals) or `RainError.TransactionSimulationFailed` (transfers) with decoded revert details, `RainError.InsufficientFunds` for a Solana fee or rent shortfall, `RainError.ProviderError` otherwise; see the `withdrawCollateral` row in the mapping table above |
| Anything else | `RainError.ProviderError` |

The Turnkey Kotlin SDK throws a plain `RuntimeException` for HTTP failures and carries the status
only inside the message, so the adapter parses the status out of the message.

Network errors raised during direct RPC calls (balances, fee estimation) surface as `RainError.NetworkError`.

### Key export errors

The one statement of the export error contract. The KDoc of `exportRecoveryPhrase` and `exportPrivateKey` and their rows in [METHODS.md](METHODS.md#turnkey-key-export) point here instead of repeating it. Conditions are listed in the order the checks run, so the first that holds is the error a call gets; the session is checked when the wallet list has to be fetched and again at the export call itself.

| Condition | Mapped to |
|-----------|-----------|
| The provider was closed | `RainError.InvalidConfig` (`RAIN_102`), before anything else |
| No live session when the wallet list is fetched, or at the export call | `RainError.TokenExpired` (`RAIN_201`) |
| `walletAddress` names no Ethereum account of the organization: the phrase and both keys, before any export call | `RainError.InvalidConfig` (`RAIN_102`) |
| No wallet to export a phrase from, or no account of the requested chain family, before any export call | `RainError.WalletUnavailable` (`RAIN_404`). In managed mode, logging in again provisions the account |
| The account of the requested family sits on another curve than the family's, before any export call | `RainError.WalletUnavailable` (`RAIN_404`). Inconsistent vendor data; logging in again does not repair it |
| Turnkey answers the export with HTTP 401 | `RainError.TokenExpired` (`RAIN_201`) after one refresh attempt |
| Turnkey answers the export with HTTP 403, a read-only bring-your-own session for example | `RainError.Unauthorized` (`RAIN_202`) |
| Any other status, or a transient failure (408, 429, 5xx, a dropped connection) that still fails after the retries | `RainError.ProviderError` (`RAIN_501`): the same cause inspection as every other `FailedToExportWallet` wrapper |
| The export activity is still pending after the vendor's polling of about four seconds | `RainError.ProviderError` (`RAIN_501`) |
| The enclave's bundle names another account than the one requested (keys only) | `RainError.ProviderError` (`RAIN_501`) with a fixed message, before anything is decrypted |
| The export bundle is rejected on the device (signature, organization mismatch, malformed) | `RainError.ProviderError` (`RAIN_501`) naming the rejection kind only. The vendor's own message can carry the ephemeral decryption key, so the adapter drops it before anything is logged |
| The exported key fails a check: not 32 bytes, outside the curve order, or it does not derive the account's address (keys only) | `RainError.InternalError` (`RAIN_502`) with a fixed message. Nothing is returned |

## Session expiry, refresh, and retry

Turnkey sessions are short-lived JWTs (15 minutes by default) that die silently once expired.
Rain hardens every Turnkey-backed call against this, controlled by `TurnkeySessionPolicy`:

```kotlin
TurnkeyProvider(
    TurnkeyConfig(
        turnkey = turnkeyContext,
        sessionPolicy = TurnkeySessionPolicy(
            refreshBufferSeconds = 60,        // refresh when < 60s of lifetime remain
            autoRefresh = true,               // let Rain call Turnkey's refreshSession itself
            refreshExpirationSeconds = null,  // seconds for refreshed sessions, > 0 (null = Turnkey's 900)
            maxTransientRetries = 2,          // backoff retries for 5xx/429/network on reads
            initialRetryDelayMs = 500,
            maxRetryDelayMs = 4_000,
        ),
        onSessionExpired = {
            // Re-auth hook: the session died and could not be refreshed. Fired once per
            // session death, on the calling coroutine's thread or the watcher's — hop to the
            // main thread before touching UI. Route the user back to login.
        },
    )
)
```

`refreshExpirationSeconds` is a `Long` count of seconds and must be positive; `null` keeps Turnkey's
default of 900 seconds. An out-of-range policy value throws `IllegalArgumentException` when the
policy is constructed.

What every wallet call does:

1. **Expiry check** — the session's JWT expiry is checked before the request. An
   already-expired session throws `RainError.TokenExpired` (or is refreshed first, see below)
   instead of burning a round-trip on a guaranteed 401.
2. **Proactive refresh** — with `autoRefresh` on (the default), a session expired or inside
   `refreshBufferSeconds` of expiry is refreshed through Turnkey's `refreshSession` before the
   call. Refreshes are single-flighted: concurrent calls share one refresh. Turnkey's refresh
   rotates the key but leaves its public `session` flow on the old session, so Rain re-selects the
   session afterwards. That reload also refreshes Turnkey's user and wallet state (its
   `autoRefreshManagedStates`, on by default) and fires the `onSessionSelected` hook of the
   `TurnkeyConfig` a bring-your-own host passed to `TurnkeyContext.init`. A reload that fails is
   logged and is not a session death.
3. **Refresh-on-401** — a call rejected with HTTP 401 / `InvalidSession` is refreshed and
   retried exactly once. A 401 on the request itself means Turnkey rejected it before executing
   anything, so this is safe for sends too. A 401 inside the wallet backend's own poll after a Solana
   send was accepted is kept inside that send (see [Solana notes](#solana-notes)); on an EVM send it
   would re-run the submit, a known gap. A second 401 surfaces as `RainError.TokenExpired`.
4. **Transient backoff** — idempotent reads (balances, history, transaction-status polls) and
   key export retry HTTP 5xx/429/408 and network I/O failures with exponential backoff; a retried
   export is a new activity with a fresh ephemeral key. Sends and signing are never retried on
   transient failures.
5. **Re-auth hook** — when the session dies for good (refresh failed, or Turnkey's own expiry
   timer cleared it while the app was idle), `onSessionExpired` fires once — even with no Rain
   call in flight, via a passive watcher over Turnkey's auth state. A refresh Turnkey refused
   with HTTP 401 (the session was revoked, which a login on another device does) also clears the
   stored session before the call throws `RainError.TokenExpired`, so `currentSessionState()`,
   `currentAuthState()` and `hasActiveSession()` read signed out when it returns and
   `sessionState` emits it. A refresh that failed for another reason (offline, a 5xx) throws the
   same error but leaves the session stored, so a later call can refresh it. This holds in both
   modes: in bring-your-own mode the session is removed from the `TurnkeyContext` the host owns,
   and the vendor's expiry timer for it is cancelled with it. A refresh whose session a login
   replaced while it was on the network proceeds with the new session instead.

With `autoRefresh = false` Rain refreshes nothing on its own: expired sessions and 401s surface
as `RainError.TokenExpired` immediately, the stored session stays, and refresh/re-auth is entirely
the host's job. The one exception is a `refreshSession()` call the host makes itself: a refusal
clears the stored session as described above.

### Observing session state

`TurnkeyProvider` exposes the session as seen at the Rain boundary:

```kotlin
val provider = TurnkeyProvider(TurnkeyConfig(turnkeyContext))

provider.currentSessionState()  // Loading | Active(expiresAtEpochSeconds) | Expired | Unauthenticated

scope.launch {
    provider.sessionState.collect { state ->
        if (state is TurnkeySessionState.Expired || state is TurnkeySessionState.Unauthenticated) {
            // show re-login UI
        }
    }
}

provider.refreshSession()  // manual refresh; throws RainError.TokenExpired when it fails, and clears
                           // the stored session when Turnkey refused the refresh
```

`sessionState` emits on every Turnkey auth/session change and additionally re-checks when an
active session passes its expiry instant, so a silent death is observable without polling.

Resolving the provider always starts a passive watcher over the process-wide Turnkey singleton —
it evicts cached accounts when the session dies and fires `onSessionExpired` when one is set. A
host that rebuilds the SDK per login should call
`provider.close()` on the provider it is discarding so a stale watcher cannot fire.

Reference: the sample app's `RainSession.kt`, `WalletSessionStatus.kt` and the Home screen's
session card (`HomeScreen.kt`, `SessionCard`).

## Registering alongside Portal

The one pair that cannot share a builder is `TurnkeyProvider` and the Rain wallet's `RainProvider`
(`rain-wallet-android`): both drive the same process-wide `TurnkeyContext`, so `build()` throws
`RainError.InvalidConfig` when both are registered, and an app that configured one of them in a
launch cannot switch to the other without a relaunch.

Turnkey and Portal can share one builder. Register both adapters on the same builder and
resolve each to its own `RainClient` — one SDK instance, two independent provider-bound clients:

```kotlin
val rain = RainSdk.builder()
    .rpcEndpoints(endpoints)
    .register(PortalProvider(PortalConfig(portalSessionToken)))
    .register(TurnkeyProvider(TurnkeyConfig(turnkeyContext)))
    .build()

val portalClient = rain.provider(ProviderId.PORTAL)
val turnkeyClient = rain.provider(ProviderId.TURNKEY)
```

Each client is bound to its provider for its lifetime; there is no "active provider" to swap.

## Bouncy Castle dependency conflict (downstream consumers)

Turnkey (via `com.turnkey:crypto` and `com.turnkey:encoding`) depends on **`org.bouncycastle:bcprov-jdk15to18`**, and so does `rain-core-android`, which needs Bouncy Castle for web3j's Keccak hashing. Web3j 4.10 itself depends on **`org.bouncycastle:bcprov-jdk18on:1.73`**. Both artifacts publish overlapping `org.bouncycastle.*` class names, so dex-ing them together fails with errors like:

```
Duplicate class org.bouncycastle.asn1.pkcs.EncryptionScheme found in modules
  bcprov-jdk15to18-1.82.jar -> bcprov-jdk15to18-1.82 (org.bouncycastle:bcprov-jdk15to18:1.82)
  bcprov-jdk18on-1.73.jar  -> bcprov-jdk18on-1.73  (org.bouncycastle:bcprov-jdk18on:1.73)
```

The two artifacts are parallel builds of the same library for different JDK targets — their class APIs are interchangeable. Rain SDK standardizes on `bcprov-jdk15to18`, floored at the catalog's `bouncycastle` version (1.86) by a published constraint, and publishes the `bcprov-jdk18on` exclusion on every dependency edge that would otherwise pull it: web3j in core, Portal and Privy, and `privy-core` in the Privy module. The Turnkey adapter declares `bcprov-jdk15to18` itself as well, for the ed25519 derivation behind the exported Solana keypair. It is the same artifact at the same floor, so nothing new reaches a consumer's classpath, and the `bcprov-jdk18on` exclusion is unchanged.

**Gradle consumers** (resolve via Module Metadata): no action required as long as you reach the vendor SDKs only through Rain's modules. The exclusions above are part of the published metadata, and Gradle inherits them along each edge that declares them.

**If your build still hits the duplicate-class error** (older Gradle, Maven POM-only resolution, or you declare web3j, `io.privy:privy-core` or another dependency that pulls `bcprov-jdk18on` yourself), add the exclusion in your own module:

```kotlin
// build.gradle.kts
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}
```

Or, if you'd rather scope it to a specific dependency:

```kotlin
implementation("org.web3j:core:4.10.1") {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}
```

Groovy DSL equivalent:

```groovy
configurations.all {
    exclude group: 'org.bouncycastle', module: 'bcprov-jdk18on'
}
```

If you have a legitimate need for `bcprov-jdk18on` (e.g. another library you control), swap the exclusion the other way and ensure all consumers compile against the same single BC artifact.
