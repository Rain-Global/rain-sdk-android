# rain-wallet-android

The Rain wallet: the provider a partner registers to give users a wallet under Rain's own name.
The SDK owns authentication (a one-time code by email or SMS, or a passkey bound to a domain you
control), wallet provisioning (one wallet holding an Ethereum and a Solana account, created on
first login), sessions and key export, and
its public surface names no wallet vendor. The wallet backend's identity is embedded per
environment; an app supplies the wallet auth token its backend mints and tunes behaviour.

> **Known issues in `5.0.0-beta.1`.** Solana `sendNative`, `sendToken` and `withdrawCollateral`
> fail with `RAIN_501` and broadcast nothing. Attaching a contact through
> `sendContactVerificationCode` and `confirmContactVerification` replaces an existing email
> address or phone number instead of adding a second one, and succeeds for an email address that
> already logs in to another account. `getTokenBalances` and `getAllBalances` can leave out a
> token the wallet holds. See the [CHANGELOG](../CHANGELOG.md).

```kotlin
dependencies {
    // Pulls rain-core-android transitively; the wallet backend ships with it.
    implementation("xyz.rain:rain-wallet-android:5.0.0-beta.1")
}
```

The snippets below are for the next release; `5.0.0-beta.1` has no `authTokenProvider` or `environment`.

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.wallet.RainProvider
import com.rain.sdk.wallet.RainWalletConfig
import com.rain.sdk.wallet.RainWalletContact
import com.rain.sdk.wallet.RainWalletEnvironment

// authTokenProvider is required: your backend mints the token with its Api-Key,
// POST /v1/issuing/embedded-wallets/auth-tokens, for the contact the SDK hands you.
// environment defaults to Production; pass Sandbox for test users and testnets.
// passkeyDomain is optional; without it the passkey methods throw RainError.InvalidConfig
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
    wallet.confirmLoginCode(code) // sign-up or login, plus wallet provisioning
    // or a passkey through the system sheet, presented from the foreground Activity:
    // wallet.loginWithPasskey(activity), or wallet.signUpWithPasskey(activity) for a new account
}

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(8453 to "https://mainnet.base.org"))
    .register(wallet)
    .build()
val client = rain.provider(ProviderId.RAIN)
```

`RainWalletConfig` carries `authTokenProvider`, required: the SDK calls it with the canonical
contact of a code request or attach (an email trimmed and lowercased, a phone number in E.164), or
with null for a passkey sign-up, and it returns the `token` your backend minted for that contact
with an Api-Key holding `embeddedWallets:write` (`POST /v1/issuing/embedded-wallets/auth-tokens`).
Keep the Api-Key off the device, and have the mint endpoint authenticate the app user and throttle
before minting: Rain limits by IP only, and a code is SMS spend. It also carries `environment`
(`RainWalletEnvironment.Production` by default, or `Sandbox`; each fixes both the Rain API host and
the wallet organization the accounts live under) and tunes behaviour: the session policy (`RainWalletSessionPolicy`), the expiry hook
`onSessionExpired`, whether sends are fee-sponsored (`sponsorGas`, on by default; the rent of a first-time
recipient's Solana token account is not demanded from the sender while it is on, see the Rain wallet
section of [docs/METHODS.md](../docs/METHODS.md#rain-wallet-provider)) and the passkey domain
(`passkeyDomain`, off by default). The rules of each parameter are in the `RainWalletConfig`
row of [docs/METHODS.md](../docs/METHODS.md#rain-wallet-provider). The session is observable through
`sessionState` and `currentSessionState()` (`RainWalletSessionState`) and through `authState` and
`currentAuthState()` (`RainWalletAuthState`); `refreshSession()` forces a refresh, `logout()` clears
the stored session, and `close()` stops the passive session watcher when a provider is discarded.
`exportRecoveryPhrase()` returns the wallet's 12-word phrase and `exportPrivateKey(account)` one
private key (`RainWalletKeyAccount.ETHEREUM` or `SOLANA`), both decrypted on the device; the
formats are a cross-platform contract shared by Rain's SDKs. Every member, its error codes and the
host's duties after an export are in [docs/METHODS.md](../docs/METHODS.md#rain-wallet-provider).

Passkeys. With `passkeyDomain` set, `signUpWithPasskey(activity)` creates a new account whose only
login is a passkey, with the same wallet a code sign-up gets, `loginWithPasskey(activity)` signs an
existing one in, and `addPasskey(activity)` registers a passkey on a code-created account. The
domain is one you control, at least two labels of letters, digits and hyphens, serving
`https://<domain>/.well-known/assetlinks.json` in the shape of Google's passkey example: a
statement for the site itself with `get_login_creds`, and an app statement with both
`handle_all_urls` and `get_login_creds` that lists the package name and the SHA-256 fingerprint of
every certificate that signs it (debug, upload and Play App Signing). The device refuses a file that
carries the app statement alone; the sample's file at `passkeys.uptop.xyz` is a working copy, and
[docs/TURNKEY_SUPPORT.md](../docs/TURNKEY_SUPPORT.md#passkeys) shows the shape. It is permanent,
because every passkey created against it stops working when it changes, and a passkey made for one
domain does not work in an app on another. Partners host their own file; Rain runs no shared domain.
Every call to `signUpWithPasskey` creates a new account, so returning users sign in or add a
passkey, and accounts are never merged. A sign-up calls `authTokenProvider` with no contact and
opens the sheet once: the sign-up carries a device key Rain registers on the new account for a
minute, and the device signs in with a login stamped by that key. When the account holds no key for
the device, the sheet opens a second time and the passkey signs in. A sign-up whose answer Rain left
in doubt keeps the passkey on the device, across a relaunch, and the next call resends it under a
fresh token without opening the sheet, so Rain records that account instead of a second one; the
`signUpWithPasskey` row of [docs/METHODS.md](../docs/METHODS.md#rain-wallet-provider) has the rest.
A passkey-only account is meant to get an email or phone as a second login through
`sendContactVerificationCode(contact)` and `confirmContactVerification(code)`, which attach the
verified contact to this account and never move wallets (what an attach refuses and what it replaces
today are in the `confirmContactVerification` row of
[docs/METHODS.md](../docs/METHODS.md#rain-wallet-provider) and the CHANGELOG's known issues);
`exportRecoveryPhrase()` is its backup path either way.

Notes:

- The wallet backend's configuration is one-shot per app launch; the first authentication call
  applies it, `environment` and `passkeyDomain` included, so a second provider with a different
  deployment (another base URL) or domain in the same launch makes every authentication call throw
  `RainError.InvalidConfig` until the app relaunches.
- The embedded backend identity follows `RainWalletConfig.environment`; the sandbox is the one
  environment with a wallet backend behind it today, so pass `Sandbox` until Rain turns production,
  the default, on.
- What leaves the device: the canonical email address or phone number passed to `sendLoginCode` or
  `sendContactVerificationCode` goes to your `authTokenProvider`. The wallet auth token it returns,
  bound to that contact, goes to Rain as the bearer of the code request, the code verify (the typed
  code, encrypted), the sign-up and the login; Rain delivers the code and keys the account on the
  contact the token names. The contact also reaches Rain inside the verification token the code
  verify answers with, which the sign-up and the login send back, and, on a first login, inside the
  sign-up message the device signs. On confirm of an attach, the verification token and the contact
  go to the wallet backend's user update. On a passkey sign-up the passkey's challenge and
  attestation and the public key of a key pair the device makes for the attempt go to Rain's passkey
  sign-up; that public key then goes to the wallet backend inside the login request the device signs
  with the key, with a second public key the session is bound to, and when the backend refuses that
  request, as it does when it holds no key for the device, the passkey's assertion goes to the
  wallet backend for the login as well; a resend that found the account already saved skips the
  key-signed request and sends the wallet backend the assertion alone; the passkey's name,
  `passkey-<unix seconds>`, goes to the device's credential provider, and Rain names the
  authenticator it registers itself. On a passkey sign-in the passkey's assertion; on `addPasskey` a
  new passkey's attestation and that name. The session stays on the device in storage the backend
  SDK owns; wallet keys live in the wallet backend and reach the device only through the export
  methods. This module sends nothing else.
- A code login leaves the user's sessions on other devices alone, so a code login on a new phone
  leaves a lost one signed in until its session lapses; a passkey login revokes them, so the
  signed-out device's `onSessionExpired` fires at its next call.
- The Rain wallet cannot be registered together with the provider from `rain-turnkey-android` in
  one `RainSdk`: `build()` refuses the pair, because both drive one process-wide wallet backend.
- Capabilities: `EXPORT`, `MULTI_CHAIN`, plus `GAS_SPONSORSHIP` while `sponsorGas` is on. Signing is
  not gated behind a biometric prompt, so gate export yourself. No capability advertises passkeys;
  `hasActiveSession()` and `authState` answer the same for a code or a passkey login.
- Attaching a contact changes which contacts sign in to the account, with no user-presence check
  beyond a live session, which can be one restored at launch, so gate `sendContactVerificationCode`
  and `confirmContactVerification` as you gate export, and show the user which contacts sign in to
  the account. The passkey requests ask the credential provider for user verification as preferred,
  not required, so gate `addPasskey` the same way.
- Minified builds inherit one rule from the wallet backend's adapter: `-dontwarn
  org.bitcoinj.core.Base58`, for a class the backend references on its export path and never loads.
  It lands in your whole R8 configuration, so a reference of your own to that class stops warning.
- Sends work on the chains the wallet backend broadcasts to; other configured chains are read-only,
  and a send there throws `RainError.ChainNotSupported` (`RAIN_104`) before any network work.
- `RainWalletSessionState` and `RainWalletAuthState` are exhaustive: a `when` over them needs no
  `else` branch, and a new state is a breaking change shipped in a major version.
  `RainWalletEnvironment` is the other way round: include an `else` branch in a `when` over it, so a
  deployment can be added without one.
- Every public signature of this module stays free of wallet-backend types, and the backend adapter
  is an `implementation` dependency, so your compile classpath never sees it.
