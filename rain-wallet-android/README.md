# rain-wallet-android

## What it is

The Rain wallet: the provider a partner registers to give users a wallet under Rain's own name.
The SDK owns authentication, wallet provisioning, sessions and key export. Authentication is a
one-time code by email or SMS, or a passkey bound to a domain you control; provisioning creates one
wallet holding an Ethereum and a Solana account on first login. Its public surface names no wallet
vendor.

The wallet backend's identity is embedded per environment. `RainWalletEnvironment.Production`
(`api.rain.xyz`, the default) and `Sandbox` (`api-dev.rain.xyz`) each fix both the Rain API host
and the wallet organization the accounts live under, so a sandbox account never signs into
production. The sandbox is the one environment with a wallet backend behind it today, so pass
`Sandbox` until Rain turns production on: a call against `Production` today fails at the first Rain
call as `RainError.ProviderError` (`RAIN_501`). The embedded identity is public identifiers, not
secrets: every call to Rain's endpoints needs the wallet auth token your backend mints, and abuse is
bounded by Rain's rate limits.

## Add the dependency

```kotlin
dependencies {
    // Pulls rain-core-android transitively; the wallet backend ships with it.
    implementation("xyz.rain:rain-wallet-android:5.0.0-beta.2")
}
```

This pulls in `rain-core-android` and, as an `implementation` dependency, the wallet backend's
adapter; no other wallet vendor enters the graph, and no vendor type is on the surface you compile
against.

## Quickstart

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.wallet.RainProvider
import com.rain.sdk.wallet.RainWalletConfig
import com.rain.sdk.wallet.RainWalletContact
import com.rain.sdk.wallet.RainWalletEnvironment
import com.rain.sdk.wallet.RainWalletLoginOutcome

// authTokenProvider: the token your backend mints, see Minting the wallet auth token
val wallet = RainProvider(
    application,
    RainWalletConfig(
        authTokenProvider = { contact -> myBackend.mintWalletAuthToken(contact) },
        environment = RainWalletEnvironment.Sandbox,
        passkeyDomain = "passkeys.example.com",
    ),
)

wallet.awaitSessionRestore()
if (!wallet.hasActiveSession()) {
    wallet.sendLoginCode(RainWalletContact.Email("user@example.com"))
    // or RainWalletContact.Phone("+15551234567") for a code by SMS
    when (val outcome = wallet.confirmLoginCode(code)) { // sign-up or login, plus wallet provisioning
        RainWalletLoginOutcome.SignedIn -> Unit
        is RainWalletLoginOutcome.SecondCodeRequired -> { // the account needs two codes
            wallet.sendSecondLoginCode() // Rain sends it to the account's other contact, outcome.contact.masked
            wallet.confirmSecondLoginCode(secondCode)
        }
    }
    // or a passkey through the system sheet, presented from the foreground Activity:
    // wallet.loginWithPasskey(activity), or wallet.signUpWithPasskey(activity) for a new account
}

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(8453 to "https://mainnet.base.org"))
    .register(wallet)
    .build()
val client = rain.provider(ProviderId.RAIN)
```

Resolving before a session is live throws `RainError.TokenExpired` (`RAIN_201`).

## Capabilities and chains

Advertised capabilities: `EXPORT`, `MULTI_CHAIN`, plus `GAS_SPONSORSHIP` while `sponsorGas` is
on; never `BIOMETRIC_GATE`, because no biometric prompt gates signing, so gate export yourself.
`RainSdk` copies the set onto the resolved client. No capability advertises passkeys;
`hasActiveSession()` and `authState` answer the same for a code or a passkey login. Sends run on
the chains under [Which chains can send](../docs/TURNKEY_SUPPORT.md#which-chains-can-send) in
docs/TURNKEY_SUPPORT.md, which also has the `RAIN_104` gate; reads work on every chain with an RPC
endpoint.

Rent for a first-time recipient's Solana token account is left to Rain's organization. While
`sponsorGas` is on, `sendToken` and `withdrawCollateral` do not demand it from the sender before
signing; with `sponsorGas` off, and always on `prepareWithdrawal`, the SDK checks it against the
cluster's rent-exempt minimum. While Rain's rent sponsorship is off, such a send from a wallet
holding less SOL than the rent is signed and fails at the backend after submission. It reaches the
host as `RAIN_402` when the failure detail carries the runtime's log, otherwise as the revert the
status names (`RAIN_403`; `RAIN_405` on a withdrawal; `RAIN_501` when it names neither). There is
no parameter for it: whether the rent is funded is Rain's setting, which a partner cannot see, and
a change to it reaches installed apps without a release.

## Session expiry and retry

`RainWalletSessionPolicy` sets the guard every wallet call runs under:

```kotlin
RainProvider(
    application,
    RainWalletConfig(
        authTokenProvider = { contact -> myBackend.mintWalletAuthToken(contact) },
        sessionPolicy = RainWalletSessionPolicy(
            refreshBufferSeconds = 60,        // refresh when < 60s of lifetime remain
            autoRefresh = true,               // let the SDK refresh the session itself
            refreshExpirationSeconds = null,  // seconds for refreshed sessions, > 0 (null = the backend's 900)
            maxTransientRetries = 2,          // backoff retries for 5xx/429/408/network on reads and key export
            initialRetryDelayMs = 500,
            maxRetryDelayMs = 4_000,
        ),
        onSessionExpired = {
            // Re-auth hook: the session died and could not be refreshed. Fired once per
            // session death, on the calling coroutine's thread or the watcher's; hop to the
            // main thread before touching UI. Route the user back to login.
        },
    ),
)
```

Out-of-range policy values throw `IllegalArgumentException` at construction.

Observable states: `sessionState` (a `Flow<RainWalletSessionState>`, snapshot via
`currentSessionState()`) is `Loading`, `Active(expiresAtEpochSeconds)`, `Expired` or
`Unauthenticated`. It emits on every authentication or session change and when an active session
passes its expiry. `authState` (`Flow<RainWalletAuthState>`, snapshot via `currentAuthState()`) is
a view over it: `Authenticated` while a session is live, with `Expired` collapsed into
`Unauthenticated`. Both read `Loading` until the first authentication call has configured the
backend and its restore has settled, so call `awaitSessionRestore()` before reading either.

The mechanism, `autoRefresh = false` included, is under
[Session expiry, refresh, and retry](../docs/TURNKEY_SUPPORT.md#session-expiry-refresh-and-retry)
in docs/TURNKEY_SUPPORT.md. So are what a refused refresh clears and what an offline or 5xx failure
leaves; the `hasActiveSession` row under [Members](#members) says why a session revoked elsewhere
reads as active until a refresh asks. A passkey login
elsewhere revokes it, and the signed-out device's `onSessionExpired` fires at its next call; a code
login does not, so a lost phone stays signed in until its session lapses. The endings of an
accepted send are under
[After a send is accepted](../docs/TURNKEY_SUPPORT.md#after-a-send-is-accepted). A login flow that
skips the code on `hasActiveSession()` calls `refreshSession()` first and sends a code when it
throws: `RAIN_201` means signed out, and `RAIN_301` or `RAIN_501` means the backend gave no answer,
with the session still stored.

## Minting the wallet auth token

Your backend mints the wallet auth token with an Api-Key that holds `embeddedWallets:write`:
`POST /v1/issuing/embedded-wallets/auth-tokens`, bound to your tenant and to the contact. A token
lives ten minutes, so mint on demand; it is the bearer of every call to Rain's endpoints;
[What leaves the device](#what-leaves-the-device) says what each call carries. Keep the Api-Key off the device, and have
the mint endpoint authenticate the app user and throttle.

Your tenant needs Rain's embedded-wallets flag on and API access. Wallets are per tenant: a user
known to another tenant signs up afresh in yours. A missing flag, an Api-Key without the scope, or
an undeliverable number is not detected at construction; the first Rain call fails instead, under
[Errors](#errors).

Rain's limits, which the SDK cannot see and your UI must design around: a code is six digits
and valid for five minutes. A wrong, expired or locked code is the same `RAIN_203` as a typo. The
device routes allow 30 requests per five minutes per IP (`RAIN_501` beyond it). Codes are
numeric today, but the format is Rain's, so do not force a numeric keyboard. Restricting
destination countries, and throttling per user, stay your app's job: a code is SMS spend an
attacker can drive.

## Two-code login

When a tenant turns the setting on, an account with both an email and a phone confirms a code from
each. `confirmLoginCode` returns `RainWalletLoginOutcome.SecondCodeRequired` with the first code
spent and nothing logged in; `sendSecondLoginCode()` (call it again to resend) and
`confirmSecondLoginCode(code)` finish the login. The outcome carries the account's other contact as
Rain masks it, a `RainWalletMaskedContact`: `type` is a `RainWalletContactType`, `EMAIL` or `PHONE`, and `masked` is the form
Rain sent (`j•••@example.com`, `+•••• 4567`), shown as it is. One wallet auth token carries the
attempt, so `authTokenProvider` is not called again; Rain allows five second codes per attempt, and
the attempt lives as long as that token, ten minutes from its mint. A new `sendLoginCode` for any
contact, `logout()`, `close()` and a passkey login or sign-up abandon an attempt, its key deleted.

## Passkeys and login contacts

A `RainWalletContact` is where the code goes and the identity the account is keyed on: a first
login signs the user up under it, a later login finds the account by it. Accounts never merge: the
same person arriving through the other channel, or by a code once and with `signUpWithPasskey`
once, gets a second account with a second wallet. The exception is a login method attached to the
first account while signed in: `addPasskey`, or the contact-attach methods. `toString()` hides the
value.

An email is trimmed and lowercased before any call: `RainWalletContact.Email(" Jo@Example.com ")`
hands `jo@example.com` to the token provider. That string is the account's identity, the one your
backend's token is bound to; a second spelling would be a second account. An email without `@` is
refused with `RAIN_102` before any call. A phone number is
trimmed, stripped of spaces, dots, hyphens and parentheses, and must then be E.164 (`+`, country
code and number, at most 15 digits): `RainWalletContact.Phone("+1 (555) 123-4567")` sends
`+15551234567`. A national number without its country code is refused with `RAIN_102` before any
call, and so is a parenthesised trunk zero such as `+44 (0) 20 ...`, which stripping would turn
into a different number. Convert national formats in your app first.

With `passkeyDomain` set, `signUpWithPasskey(activity)` creates a new account whose only login is a
passkey, with the same wallet a code sign-up gets. `loginWithPasskey(activity)` signs an existing
one in, and `addPasskey(activity)` registers a passkey on a code-created account. All three take
the foreground `Activity` the sheet is presented from, hold it only for the call, and suspend until
the sheet closes. [signUpWithPasskey](#signupwithpasskey) under Reference has the device-key legs,
and its row the codes. The passkey requests ask the credential provider for user verification as
preferred, not required, so gate `addPasskey` as you gate export. A passkey-only account is meant
to get an email or phone as a second login through the attach methods under
[Attaching a contact](#attaching-a-contact); `exportRecoveryPhrase()` is its backup path either way.

The domain is one you control, at least two labels of letters, digits and hyphens, such as
`passkeys.example.com`, serving `https://<domain>/.well-known/assetlinks.json` as
`application/json` with no redirect, in the shape below. The app statement lists the SHA-256
fingerprint of every certificate that signs the app (debug, upload and Play App Signing). Google
Play services fetches the file at every ceremony and judges it itself. A file carrying the app
statement alone is refused with `RAIN_102`, even when Google's asset-links API reports the link as
valid. The sample's file at `https://passkeys.uptop.xyz/.well-known/assetlinks.json` has that
shape:

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

The domain is permanent, because every passkey created against it stops working when it changes,
and a passkey made for one domain does not work in an app on another. Partners host their own file;
Rain runs no shared domain. The device requirements are under [Platform notes](#platform-notes).

## Attaching a contact

`sendContactVerificationCode(contact)` sends a one-time code to an email or phone the signed-in user
wants to attach to this account as a login method. `confirmContactVerification(code)` verifies it
and sets the account's email or phone, so that contact then signs in to this account; wallets
never move either way. It is distinct from `sendLoginCode`, which starts a login, and the code is
verified without logging in; the two rows under [Members](#members) have the challenge's lifecycle
and the codes.

Once the code is accepted, Rain's verify names the wallet this tenant saved for the contact, when
one exists. A contact another wallet signs in with is refused with `RAIN_202` before anything
changes, the code spent. A contact whose account Rain protects with two codes gets the same
refusal: Rain's verify asks for a second code instead of answering a token, and an attach never
sends one. A contact two saved wallets already share fails the verify with a 5xx (`RAIN_501`). A
contact no wallet signs in with attaches. This wallet's own contact is refused with `RAIN_102`,
since it is already attached, the code spent; on an account protected with two codes it is refused
with `RAIN_202` like another wallet's. An attach replaces an existing contact of the same kind; it
does not add a second one.

The SDK adds no user-presence check before the attach beyond the live session, which can be one
restored at launch. Gate the two calls as you gate export (a biometric prompt or a fresh login) and
show the user which contacts sign in to the account.

## Key export

`exportRecoveryPhrase()` returns the wallet's 12-word BIP-39 phrase and `exportPrivateKey(account)`
one private key (`RainWalletKeyAccount.ETHEREUM` or `SOLANA`), both decrypted on the device and
returned once. One 12-word seed covers both accounts, Ethereum at `m/44'/60'/0'/0/0` and Solana at
`m/44'/501'/0'/0'`, so one phrase restores both in any wallet deriving those paths.

```kotlin
val phrase = wallet.exportRecoveryPhrase()
val ethereumKey = wallet.exportPrivateKey(RainWalletKeyAccount.ETHEREUM)
val solanaKey = wallet.exportPrivateKey(RainWalletKeyAccount.SOLANA)
```

Both need a live session (`RAIN_201`). The formats, a cross-platform contract shared by Rain's
SDKs, the checks before a key is returned, your duties after the return, timing, retries and an
import check are under [Key export](../docs/TURNKEY_SUPPORT.md#key-export) in
docs/TURNKEY_SUPPORT.md. The codes are in the
[Key export errors](../docs/TURNKEY_SUPPORT.md#key-export-errors) table there.

## What leaves the device

The canonical email address or phone number passed to `sendLoginCode` or
`sendContactVerificationCode` goes to your `authTokenProvider`. The wallet auth token it returns,
bound to that contact, goes to Rain as the bearer of the code request, the code verify (the typed
code, encrypted), the sign-up and the login. Rain delivers the code and keys the account on the
contact the token names. The contact also reaches Rain inside the verification token the code
verify answers with, which the sign-up and the login send back, and, on a first login, inside the
sign-up message the device signs.

A second code travels under the same token: the request with no body, the verify with the code
encrypted to the attempt's device key. That verify also carries the key's signature over the login
message Rain issued, which names the verification Rain kept and the key, not the contact. Rain
names the other contact only in masked form. On confirm of an attach, the verification token and
the contact go to the wallet backend's user update.

A passkey sign-up sends the passkey's challenge and attestation, and the public keys of two key
pairs the device makes, to Rain and the wallet backend. A sign-in sends the assertion. A resend
sends the kept challenge and attestation again, with fresh keys, then the stamped login or, when
Rain registered no key, the assertion. `addPasskey` sends a new attestation and its name. The
passkey's name, `passkey-<unix seconds>`, goes to the device's credential provider, and Rain names
the authenticator it registers itself.

The session stays on the device in storage the backend SDK owns; wallet keys live in the wallet
backend and reach the device only through the export methods. This module sends nothing else.

## Errors

The rules every row assumes, so a row lists only what differs. A wallet auth token Rain refuses, or
reports as spent by another call, is fetched once more from `authTokenProvider` and the leg
retried; the second-code legs share the attempt's token and renew nothing. A first-code or attach
verify that never answered keeps its challenge, and a row says when a later failure drops it; a
second-code verify that never answered ends its attempt, as the `confirmSecondLoginCode` row says. A closed
provider, a configuration conflict, or a missing precondition (no code requested, a blank code, a
blank or malformed contact, a blank token) is `RAIN_102`. So is a token provider that threw, unless
it threw a `RainError`, which passes through, or could not reach your backend (`RAIN_301`). A
backend initialization that failed for this launch is `RAIN_502`. A call that needs a live session
and has none is `RAIN_201`.

A code span such as `RAIN_202` names the error code; the [Errors table](../docs/METHODS.md#errors)
in docs/METHODS.md has the meanings. A `ProviderError` carries the path, the status and, on a 500,
Rain's correlation id; an `InternalError` names the path; neither carries the response body. Send,
withdrawal and fee-estimate failures map as [Error
handling](../docs/TURNKEY_SUPPORT.md#error-handling) in docs/TURNKEY_SUPPORT.md says; this module
adds no mapping of its own on those paths. What Rain's embedded-wallet endpoints answer, and the
code each becomes:

| Rain's answer | Code |
|---------------|------|
| `POST /otp/verify` answering 400 "Invalid or expired code" | `RainError.InvalidLoginCode` (`RAIN_203`): wrong, expired or locked; the challenge is kept, so a mistyped code can be retyped. Only a new `sendLoginCode`, or `sendSecondLoginCode` for a second code, gets the user past an expired or locked one |
| `POST /otp/init` or `POST /otp/verify` answering a 400 whose message says "start again" (a two-code attempt is over on Rain's side) | `RainError.ProviderError` (`RAIN_501`) whose message asks for a new login code; the attempt, its code and its key are dropped |
| A 401 for the wallet auth token (expired, revoked, unknown), or a 409 "already been used" | `RainError.Unauthorized` (`RAIN_202`), after the one renewal above |
| A 403 (the tenant lacks the embedded-wallets flag), or a 401 "Invalid tenant" (no API access) | `RainError.Unauthorized` (`RAIN_202`) at once, no renewal: a new token fails the same way |
| Any other 400 ("Verification failed", a contact that cannot receive a code), a 404 off the login leg, any other 409, 429, 5xx, any other status | `RainError.ProviderError` (`RAIN_501`). The passkey sign-up's two 409s, "already has a wallet" and "already in progress", are under [signUpWithPasskey](#signupwithpasskey). A 404 carrying Rain's own body on the login leg is not an error: on a code login the SDK signs the contact up |
| A transport failure | `RainError.NetworkError` (`RAIN_301`) |
| A 2xx answer that could not be read | `RainError.InternalError` (`RAIN_502`) |

The passkey ceremonies map by the decisive cause:

- The sheet dismissed, no passkey for the domain, or a cancelled ceremony: `RainError.UserRejected`
  (`RAIN_401`), the current session untouched.
- The association file, the fingerprint or the domain does not match this build, or the file lacks
  the site's own statement: `RainError.InvalidConfig` (`RAIN_102`). Current Play services builds
  report that check as `DataError` (their code 50152), older ones as `SecurityError`.
- An HTTP status from the wallet backend's API inside the ceremony: `RAIN_501`, the body discarded
  as for the code flow. The exception is a 401 or 403 while the new session is stored and
  reloaded, which map as the `confirmLoginCode` row says (`RAIN_201`, `RAIN_202`). A
  refusal from Rain's passkey sign-up endpoint maps as the table above (`RAIN_202` for a refused
  token or tenant), except its two 409s. Those, "already has a wallet" (`RAIN_102` on a first send)
  and "already in progress" (`RAIN_301`), are under [signUpWithPasskey](#signupwithpasskey). A
  missing passkey provider, or any other Credential Manager failure: `RAIN_501`.
- A registration the ceremony produced that the backend cannot use, or a session store that found
  the session key occupied: `RainError.InternalError` (`RAIN_502`); retry the call.
- The sign-up's login stamped with the device key: a 4xx, or an answer without a session, is no
  error, and the sheet signs the account in. A 5xx is `RAIN_501` and a transport failure
  `RAIN_301`; a session store the backend failed maps as the `confirmLoginCode` row says.

## Platform notes

- Minified builds inherit a `-dontwarn org.bitcoinj.core.Base58` rule from the wallet backend's
  adapter; the "Minified builds" paragraph under
  [Key export](../docs/TURNKEY_SUPPORT.md#key-export) in docs/TURNKEY_SUPPORT.md says what it
  silences.
- The passkey flows need Android 9 or later, a screen lock and a credential provider with a
  signed-in account. On Android 9 to 13 that is Google Play services, which the SDK's dependencies
  bring; on Android 14 and later any installed credential provider. An emulator needs a Play image
  with a Google account added.
- JVM unit tests that reach the wallet backend's code need JDK 24; see
  [Requirements](../README.md#requirements).

## Known issues

`5.0.0-beta.2` deviates from this contract in the entries under
[Known issues](../CHANGELOG.md#known-issues) in CHANGELOG.md, which state each effect. Accounts created on
`5.0.0-beta.1`, or signed up with a mixed-case email, are not found by the code login of `5.0.0-beta.2`; the
migration notes under [5.0.0-beta.2](../CHANGELOG.md#500-beta2---2026-11-23) say what to do before upgrading.

## Notes

- The Rain wallet cannot share a builder with the provider from `rain-turnkey-android`; the
  `build()` row under [RainSdk.Builder](../docs/METHODS.md#rainsdkbuilder) in docs/METHODS.md has
  the rule and its limits.
- The user is the account's only root user: Rain has read-only visibility and can neither reach the
  keys nor sign. The SDK exposes no way to delete an account.

## Reference

### Configuration

`RainWalletConfig`, the descriptor's second constructor argument. The wallet backend's
configuration is one-shot per app launch: the first authentication call, key export or provider
resolution, whichever comes first, applies it, `environment` and `passkeyDomain` included. A second
provider with a different deployment or domain in the same launch, or a backend the app configured
itself, makes every authentication call throw `RAIN_102` until the app relaunches. There is no
address override: the SDK signs with, and reads for, the one Ethereum account it provisions.

| Parameter | Rules |
|---|---|
| `authTokenProvider: suspend (RainWalletContact?) -> String` | Called with the canonical contact of a code request or attach, or with null for a passkey sign-up. Returns the token your backend minted for that contact, under [Minting the wallet auth token](#minting-the-wallet-auth-token). Throw a `RainError` to pass it through. A backend the function could not reach (an `IOException` in its cause chain) arrives as `RAIN_301` and any other exception as `RAIN_102`, each naming its class and nothing else. It runs on the calling coroutine, under the provider's authentication lock, and is kept for the provider's life. So suspend rather than block, carry your own timeout, never call back into the provider from it, and capture no Activity or ViewModel. |
| `environment: RainWalletEnvironment = Production` | `Production` by default, so a build that passes nothing never runs against the sandbox by accident; the pairing, and what a `Production` call does today, are under [What it is](#what-it-is). |
| `sessionPolicy: RainWalletSessionPolicy = RainWalletSessionPolicy()` | The expiry, refresh and retry behaviour under [Session expiry and retry](#session-expiry-and-retry), whose snippet lists the parameters and defaults. |
| `onSessionExpired: (() -> Unit)? = null` | Fires once per session death that cannot be refreshed, on any thread; a deliberate `logout()` never fires it. |
| `sponsorGas: Boolean = true` | With it on, every send on a supported chain is fee-sponsored and `GAS_SPONSORSHIP` is advertised; [Gas sponsorship](../docs/TURNKEY_SUPPORT.md#gas-sponsorship) in docs/TURNKEY_SUPPORT.md has the rules (fee estimates, no revert preflight), and [Capabilities and chains](#capabilities-and-chains) the Solana rent. |
| `passkeyDomain: String? = null` | The domain under [Passkeys and login contacts](#passkeys-and-login-contacts), which describes the file it serves. Null or blank turns `loginWithPasskey`, `signUpWithPasskey` and `addPasskey` off (`RAIN_102` when called); a scheme, port, path or a single label such as `localhost` throws `RAIN_102` from the `RainProvider` constructor. |

### Members

The `RainWallet*` types follow the conventions under [Types](../docs/METHODS.md#types) in
docs/METHODS.md: which `when`s are exhaustive, and which take an `else` branch.

| Member | Signature | Notes |
|--------|-----------|-------|
| `id` / `capabilities` | `ProviderId.RAIN` / `Set<Capability>` | The set under [Capabilities and chains](#capabilities-and-chains). |
| `awaitSessionRestore` | `suspend fun awaitSessionRestore(timeoutMs: Long = 5_000)` | Waits for the asynchronous restore of a persisted session; a timeout returns normally with `authState` at `Loading`. As the first authentication call of a launch it also runs the backend's one-shot initialization, which `timeoutMs` does not bound. |
| `hasActiveSession` | `fun hasActiveSession(): Boolean` | True when a live session has more than 30 seconds left, so the code step can be skipped; a session revoked elsewhere reads true until a refresh asks. False until the first authentication call has configured the backend. |
| `authState` / `currentAuthState()` | `val authState: Flow<RainWalletAuthState>` / `fun currentAuthState(): RainWalletAuthState` | `Loading`, `Authenticated` or `Unauthenticated`; what each means, and when `Loading` ends, is under [Session expiry and retry](#session-expiry-and-retry). |
| `sendLoginCode` | `suspend fun sendLoginCode(contact: RainWalletContact)` | Asks `authTokenProvider` for a token bound to the canonical contact, then has Rain send a one-time code to a `RainWalletContact.Email` or `.Phone`; touches no existing session. Calling it again for the same contact replaces the pending code on success and keeps it on failure, which is how a code is resent. Another contact or channel retires the pending code first, so a failed switch leaves nothing confirmable. Canonicalization and the refused forms are under [Passkeys and login contacts](#passkeys-and-login-contacts). `RAIN_501` when Rain refuses the code request. |
| `confirmLoginCode` | `suspend fun confirmLoginCode(code: String): RainWalletLoginOutcome` | Logs the contact's wallet in, or signs the contact up when this tenant has none (the wallet is provisioned inside the sign-up), and returns `SignedIn`; a returning login backfills a missing account. For an account Rain protects with two codes it returns `SecondCodeRequired` instead, the device key kept for the attempt and no session touched. A failed verify keeps the challenge for a retry, the current session untouched: `RAIN_203` for a rejected code (wrong, expired or locked), `RAIN_301`, `RAIN_501` or `RAIN_202` otherwise. Two verify answers drop it: an unreadable one (`RAIN_502`), since Rain accepted the code, and a 400 "start again" (`RAIN_501`). A failure after the code was accepted drops it too, since the verification token cannot be reused, so request a new code. Those are `RAIN_501` for a sign-up already in progress, `RAIN_202` for a token another call spent, `RAIN_301` offline and `RAIN_502` for an answer that could not be read. `RAIN_502` also for a second-code answer whose login message does not name the device key. A failed switch to the new session throws as its cause maps. Those are `RAIN_201` for a 401 (`RAIN_202` for a 403) on the backend's reload of the new session, `RAIN_502` for a session it cannot find and `RAIN_501` otherwise. The previous session stays selected unless the backend persisted the new one before failing, which then stays; a provisioning failure keeps the new session and heals at resolution. |
| `sendSecondLoginCode` | `suspend fun sendSecondLoginCode()` | Sends, or resends, the second code to the contact `SecondCodeRequired` names; Rain picks the destination, and the attempt's token is reused, so `authTokenProvider` is not called. The limits are under [Two-code login](#two-code-login). `RAIN_102` when no login is waiting for a second code. Every refusal by Rain ends the attempt and deletes its key, so start again with `sendLoginCode`. Those are `RAIN_501` (a "start again" message once the five codes are used up) and `RAIN_202` for a rejected token. `RAIN_301` and `RAIN_502` leave the attempt for a retry. |
| `confirmSecondLoginCode` | `suspend fun confirmSecondLoginCode(code: String)` | Confirms the second code and completes the login as `confirmLoginCode` does (store, select, clear the previous session, backfill). `RAIN_203` for a rejected code keeps the attempt, so the user retypes. Every other ending, `RAIN_301` included, ends it and deletes its key, so start again with `sendLoginCode`: Rain forgets the attempt before it finishes the login, so a request that may have reached it has nothing to retry against. Those are `RAIN_501` (a 5xx included), `RAIN_202` for a rejected token and `RAIN_502` for an unreadable answer (the session was issued and lost). `RAIN_102` when no second code was sent. Once the session was issued, `RAIN_201` and `RAIN_202` as for `confirmLoginCode`. |
| `RainWalletLoginOutcome` | `sealed class RainWalletLoginOutcome { data object SignedIn; class SecondCodeRequired(val contact: RainWalletMaskedContact) }` | What `confirmLoginCode` led to, under [Two-code login](#two-code-login). |
| `logout` | `suspend fun logout()` | Clears the stored session, after a restore in flight settles, without firing `onSessionExpired`. Cached accounts are evicted, and a pending login code, a pending contact verification and a two-code login between its codes (its key deleted) are dropped. `hasActiveSession()` and `currentAuthState()` read unauthenticated as soon as it returns. With no session selected it still drops them, and the readiness checks of every authentication call still run. |
| `loginWithPasskey` | `suspend fun loginWithPasskey(activity: Activity)` | Signs an existing user in with a passkey through the system sheet presented from `activity`. A passkey login revokes the user's other sessions and selects the new session over a live one. So a failed switch signs the device out, with `onSessionExpired` silent, where a failed switch after a code login keeps the previous session; a missing account is backfilled. A two-code attempt is abandoned before the sheet opens, whether or not the sheet completes; a pending first code survives. The ceremony's own codes are under [Errors](#errors). After a successful ceremony the session switch and the account backfill can still throw `RAIN_201` or `RAIN_202`; a backfill failure keeps the session, which heals at resolution. |
| `signUpWithPasskey` | `suspend fun signUpWithPasskey(activity: Activity)` | Creates a new account with a passkey as its only login method and one wallet, and signs it in with one passkey sheet; each new passkey is a new account. Refused with `RAIN_102` while a live session is stored, so call `logout()` first unless `currentAuthState()` reads `Unauthenticated` (`hasActiveSession()` reads false in a session's last 30 seconds, which still refuses). An expired session is cleared first, with `onSessionExpired` silent. The legs, when the sheet opens a second time and which answers keep the registration for a resend are under [signUpWithPasskey](#signupwithpasskey) below. A dismissed sheet (`RAIN_401`) sends nothing to Rain. A refusal from Rain's sign-up (a 4xx the kept list omits) is `RAIN_202` for a refused token or tenant, `RAIN_102` naming `loginWithPasskey` for "already has a wallet" on a first send, and `RAIN_501` otherwise. On a first send that refusal drops the registration, since no account exists. Once Rain answered that the account exists, `loginWithPasskey` reaches it and another `signUpWithPasskey` mints another account. After that answer, the stamped login's codes are under [Errors](#errors). Then as for `loginWithPasskey`. |
| `addPasskey` | `suspend fun addPasskey(activity: Activity)` | Registers a passkey bound to `passkeyDomain` on the signed-in account, so the next sign-in can use it. The session is checked, and refreshed when near expiry, before the sheet, so `RAIN_201` fires before any biometric prompt. A session revoked between that check and the registration still surfaces as `RAIN_201` after it, with `onSessionExpired` firing. A registration the backend refused leaves a passkey on the device that signs into nothing. No new account and no session change; other authentication calls wait while the sheet is open. One passkey per device is enough; each call registers another. Gate it as under [Passkeys and login contacts](#passkeys-and-login-contacts). The backend refusing the registration is `RAIN_202`; a registration the backend failed (its per-user limit included) is `RAIN_501`; the ceremony's own codes are under [Errors](#errors). |
| `sendContactVerificationCode` | `suspend fun sendContactVerificationCode(contact: RainWalletContact)` | Sends a verification code to a contact the signed-in user wants to attach, under a live session (`RAIN_201` before anything is sent) and a token bound to the canonical contact. The contact is canonicalized like a login contact, and that string is stored. Its pending challenge is separate from the login code's, so neither flow can consume the other's code. Calling it again for the same contact replaces the pending code, a call for another contact or channel retires it before anything is sent, and a login or a `logout` drops it. `RAIN_501` when the code request is refused. The rest is under [Attaching a contact](#attaching-a-contact). |
| `confirmContactVerification` | `suspend fun confirmContactVerification(code: String)` | Confirms the code from `sendContactVerificationCode` and attaches the verified contact; the session is checked before the code is spent (`RAIN_201` otherwise). A failed verify keeps the challenge (`RAIN_203` for a rejected code, `RAIN_301`, `RAIN_501` or `RAIN_202` otherwise), except an unreadable answer (`RAIN_502`), which drops it, since Rain accepted the code. Once the code is accepted, the update sets the contact; which contacts are refused then, and with which code, is under [Attaching a contact](#attaching-a-contact). A failure or a cancellation after the code was accepted drops the challenge, so request a new code: `RAIN_201` when the session died meanwhile, `RAIN_202` when the backend refused the update, `RAIN_501` when it failed. |
| `sessionState` / `currentSessionState()` | `val sessionState: Flow<RainWalletSessionState>` / `fun currentSessionState(): RainWalletSessionState` | `Loading`, `Active(expiresAtEpochSeconds: Double)`, `Expired` or `Unauthenticated`, compared by value; when it emits is under [Session expiry and retry](#session-expiry-and-retry). |
| `refreshSession` | `suspend fun refreshSession()` | Forces a refresh regardless of remaining lifetime, the call that tells a revoked session apart from a stored one; `RAIN_201` when the backend refuses it, so re-authenticate. What a refused refresh clears, and what a refresh with no answer throws and leaves, is under [Session expiry, refresh, and retry](../docs/TURNKEY_SUPPORT.md#session-expiry-refresh-and-retry) in docs/TURNKEY_SUPPORT.md. |
| `exportRecoveryPhrase` | `suspend fun exportRecoveryPhrase(): String` | Under [Key export](#key-export). |
| `exportPrivateKey` | `suspend fun exportPrivateKey(account: RainWalletKeyAccount): String` | Under [Key export](#key-export). |
| `create` / `close` | `override suspend fun create(context: ProviderContext): WalletProvider` / `override fun close()` | `create`, the registry's hook, re-checks the account set, probes the Ethereum account and throws `RAIN_404` when the account has no Ethereum wallet. `close` stops the passive session watcher and makes the provider inert: authentication and export calls throw `RAIN_102`, `currentAuthState()` reads `Unauthenticated`, `hasActiveSession()` is false. The two session reads still report the process-wide backend session afterwards, so cancel the scope that collects `sessionState` when you discard the provider. A login verify still in flight completes the login and ends in `RAIN_102` at provisioning, the session stored and selected. A verify that answers a second-code outcome, or a second-code verify, ends in `RAIN_102` with no attempt published and no session stored, its key deleted, when `close()` lands before Rain's answer; after it the call ends as the one-code path. A contact verify in flight completes the attach. |

### signUpWithPasskey

Rain registers a device key the sign-up carries on the new account for a minute; the device signs
in through a login stamped with it, then the session switch and backfill of `loginWithPasskey` run.

The sheet opens a second time, and the passkey signs the account in, when the stamped login is
refused or answers without a session (the cases under [Errors](#errors)). So it does on a resend
answered "already has a wallet". A refused stamp means the account holds no key for this device:
Rain adopted an earlier attempt's account, or the minute passed.

A sign-up whose outcome Rain left in doubt keeps its registration across a relaunch until a settled
answer clears it; a `logout()`, a code login or `close()` leaves it. The answers that keep it: one
lost or unreadable (`RAIN_301`, `RAIN_502`), a 5xx or a 429
(`RAIN_501`), and "already in progress" (`RAIN_301`, call again in a moment). A call cancelled, or a
process killed, while Rain's answer was outstanding keeps it too. On a resend, every refusal but a
400 and "already has a wallet" keeps it.

The next call resends it under a fresh token, with no new passkey and for whichever app user the
host minted that token, so Rain records the first call's account instead of a second one. It then
signs in through the device key or, when Rain registered none, the sheet. A call cancelled after
Rain's answer leaves the account the same way.
