# Rain SDK Sample App (Android)

A Jetpack Compose sample that exercises the public Rain SDK surface with a real wallet provider:
connect a wallet, read balances, send tokens, withdraw collateral, and list transaction history.

---

## Requirements

- Android Studio with its bundled JBR
- An emulator or device on API 28+. The passkey buttons on the Rain Wallet card also need Google
  Play services with a Google account signed in and a screen lock set.
- The SDK modules this sample uses (`:rain-core-android`, `:rain-wallet-android`, `:rain-turnkey-android`,
  `:rain-portal-android`, `:rain-privy-android`)

## How to run

1. Open the repo root in Android Studio.
2. Select the **app** run configuration and a device.
3. Run.

Every screen also carries `@Preview` composables, so each state can be inspected in Android
Studio's Split or Design view without a wallet provider or Rain API credentials. The feature
screens otherwise need a real provider login to reach.

---

## Screens

| Screen | What it exercises |
|---|---|
| **Home** | Provider choice (Rain Wallet / Turnkey / Portal MPC / Privy), Rain API credentials, auth, `RainSdk` build, session card, active-wallet dropdown, feature grid, and for a signed-in Rain Wallet session the *Export keys* card (`exportRecoveryPhrase`, `exportPrivateKey`) |
| **Wallet & QR** | `getWalletAddress(chainId)` and the collateral deposit address from the demo's own `RainApiClient` (a call a shipped app makes from its backend), each with a QR bitmap from `generateAddressQRCode(address)`. The deposit card shows a contract on the selected chain only, and otherwise says which chain holds the collateral |
| **Balances** | Collateral balances from the demo's `RainApiClient`, with token names and decimals from `RainSdk.tokenMetadata`, plus the wallet's own native and token balances (`getBalance`, `getTokenBalances`). Once a collateral contract has loaded, its resolved tokens are registered with the SDK (`registerTokens`), so the wallet card lists them too when the contract sits on the selected chain, except with Portal, which lists only its own service's tokens |
| **Send tokens** | `sendNative` and `sendToken` (ERC-20 on EVM, SPL on Solana) |
| **Withdraw collateral** | The withdrawal signature from the demo's `RainApiClient` + `withdrawCollateral`, with the `estimateWithdrawalFee` and `prepareWithdrawal` dry runs and an *Estimate fee of prepared* button that quotes the held preparation with no new signature, on both EVM and Solana collateral (both fee estimates are EVM-only). The fee card says when a sponsor pays, and a token whose decimals the SDK cannot resolve stays listed with its money actions disabled |
| **Auth pull** | `getTokenAllowance`, `estimateApprovalFee`, `approveTokenAllowance` + `confirmTokenAllowance`, and revocation |
| **History** | `getTransactions(chainId, limit, offset, order)`, newest-first |

Every feature screen reads the chain picked in the **Active wallet** dropdown on Home, so switching
networks needs no re-initialization: the SDK is built with all chains' RPC endpoints at once (see
`WalletChain.rpcEndpoints`; Portal gets the EVM subset, `evmRpcEndpoints`).

## Networks

`WalletChain` defines six networks (a sandbox build offers Avalanche Fuji, Base Sepolia, Arbitrum Sepolia and
Solana devnet, hides the mainnet Auth Pull chains, and adds an Ethereum Sepolia endpoint for collateral reads and withdrawals),
along with each one's RPC URL, native symbol, explorer links, default token / recipient, and address
validation. Portal holds no Solana account, so selecting Portal restricts the dropdown to the EVM
chains.

Rain may host a user's collateral on a chain the picker does not offer, so each screen picks its
contract with `CollateralContractMatch` (its KDoc in `WalletChain.kt` has the reasons). Withdraw and
Balances take a contract on any EVM chain when an EVM chain is selected, and a Solana cluster takes
only its own. Wallet & QR takes the selected chain's contract only, and when there is none it says
which chain holds this account's collateral.

## Providers

Portal, Turnkey and Privy auth are the host app's responsibility; the SDK only wants an
authenticated provider handle, and the Turnkey and Privy drivers are reference code you would write
yourself. The Rain wallet owns its authentication, so the sample calls the provider's own auth
methods:

- **Rain Wallet** (`RainSession.prepareRainWallet`) — a contact: an email address, or a phone number
  when the *Send code by* switch is on Phone. The wallet backend's identity is embedded in the SDK,
  so there is nothing else to enter. A phone number needs its country code (`+15551234567`); a number typed without one is
  converted with the device's region before it reaches the SDK, which requires E.164 and removes
  spaces, dots, hyphens and parentheses. The SDK sends and confirms the one-time code, signs up
  (creating one wallet with the Ethereum and Solana accounts) or logs in, and backfills a missing
  account, then initializes Rain on its own, as it does for a resumed session. A session restored
  from an earlier run is reused only after the backend confirms it is still live. One revoked by a
  login on another device falls back to the code. A rejected code keeps
  the challenge for a retry, and *Resend code* requests a new one (the
  channel and the contact stay locked); the code field takes letters on both channels
  because the backend's code format is one shared setting and may be alphanumeric. If the login
  itself succeeded but a later step failed, the sample carries on signed in (Rain's initialization
  finishes the wallet setup); any other failure restarts from *Send code*. An email login and a phone
  login by the same person are two different accounts. SMS needs SMS one-time codes enabled on the
  backend configuration; in the sandbox the test number `+1 999-999-9999` with the code `000000`
  works once the code format is numeric and 6 characters. Two more buttons sit under the code step
  while signed out: *Sign in with passkey* opens the system passkey sheet for an existing account,
  and *Create wallet with passkey* makes a new account whose only login is the passkey, with the
  same wallet setup as a code sign-up; a returning user who taps it ends up with a second, empty
  wallet, and accounts are never merged. Both need the domain in `RainSession.prepareRainWallet`
  (`passkeys.uptop.xyz`) to vouch for this build's signing key, see *Demo keystore* under Notes.
  Signed in, *Add a passkey to this account* registers a passkey for a code-created account, and
  the *Add a login contact* step sends a verification code to an email or phone and attaches it on
  *Verify and attach*, after which that contact signs in to this account by code; a contact another
  account signs in with is refused (`RAIN_202`) and the card shows the reason. A closed
  passkey sheet shows `RAIN_401`; every other failure shows its error code only.
- **Export keys** — once the Rain Wallet session is active, an *Export keys* card under the Rain Wallet card reveals the recovery phrase,
  the Ethereum private key or the Solana private key, one at a time, through `exportRecoveryPhrase`
  and `exportPrivateKey`, on a restored or reused session before *Initialize Rain*, and after it. While a value shows, the window
  carries `FLAG_SECURE`, so screenshots, screen recordings and the recents thumbnail are blank,
  though `FLAG_SECURE` does not hide the text from accessibility services. *Copy* puts the value on
  the clipboard flagged
  sensitive, so Android 13 and later mask the system preview, and the clipboard clears itself after
  60 seconds whatever it holds by then, so a copied value survives the switch to another wallet app
  for a paste. *Hide* and *Clear session* clear it at once. The value is hidden when you leave the
  screen, background the app, the session expires or *Clear session* runs, it is never written to
  saved state, and the log records the kind, the error code and the error class only. The revealed
  text carries password semantics, so a screen reader masks it unless the user chose to hear
  passwords in the accessibility settings. To check an export, restore
  the phrase in MetaMask and in Phantom and compare the addresses with the Wallet screen, then import
  the Ethereum key in MetaMask and the Solana key in Phantom and compare again.
- **Turnkey** (bring-your-own, `TurnkeyAuthSample` + `RainSession.initializeTurnkey`) — parent
  organization ID + auth proxy config ID + a contact: an email address, or a phone number when the
  *Send code by* switch is on Phone, converted to E.164 the same way as on the Rain Wallet card (SMS
  needs SMS one-time codes enabled on the auth proxy configuration). The sample initializes the
  Turnkey Kotlin SDK itself, sends and verifies the one-time code through it, provisions one wallet holding an
  Ethereum and a Solana account on first sign-in, and hands the authenticated `TurnkeyContext` to
  the public `TurnkeyConfig(turnkey = …)`. The Rain wallet and this tab share one process-wide
  Turnkey singleton, so after one of them has configured it in a launch the other refuses to start
  until the app is relaunched: the Rain wallet with `RainError.InvalidConfig`, this tab with an
  error from `TurnkeyAuthSample.init`, which probes the singleton before configuring it. Both tabs
  stay selectable; the one that did not configure the backend shows a notice saying so.
- **Portal MPC** — paste a Portal session token on Home and tap *Initialize SDK*.
- **Privy** (`PrivyAuthSample`) — app ID + app client ID + email OTP; embedded Ethereum and Solana
  wallets are created on first sign-in.

Rain API credentials (program `Api-Key` + Rain `userId`) are separate from the wallet provider: they
feed the demo's own `RainApiClient` (contracts and withdrawal signatures) and are entered in their own
card on Home. The on-device Api-Key is a demo shortcut. A shipped app fetches contracts and signatures
from its own backend, and the program key never leaves it.
The last working values — provider choice, Rain API credentials, and each provider's ids and contact
(email, or phone and channel for the Rain wallet, or a passkey-owner flag when the Rain wallet session
was established with a passkey) — are kept in an encrypted store (`SessionStore`) so the next launch
pre-fills them and resumes the session; *Clear session* wipes them.

`RainSession` also registers each selectable chain's default token (`WalletChain.defaultTokenInfo`) via
`registerTokens` on the builder and enables Auth Pull with `authPullConfig`, identically for all four providers. That is not a workaround the
SDK needs in production — it is the same mechanism a host app uses when a token cannot be
discovered on chain. An SPL mint carries no on-chain symbol, and the built-in token registry has no entry for
Fuji USDC or the devnet mint, so naming them keeps the balance screen readable.

## Notes

- **Portal wallet recovery**: the sample has no Portal backup or recovery UI.
- **Solana history** rows carry the wallet backend's status id (the activity id when the send recorded none) rather than a resolvable signature, so those
  rows are not linked to an explorer.
- **Demo keystore.** `app/demo-debug.keystore` is committed on purpose and signs both the debug and
  the release build. The Rain Wallet card's passkeys are bound to this certificate's SHA-256
  fingerprint through `https://passkeys.uptop.xyz/.well-known/assetlinks.json`, in the two-statement
  shape of Google's passkey example (the reference copy the wallet README points partners at), so a
  build signed with any other key (Android Studio's own debug key included) gets `RAIN_102` at the
  passkey sheet. The alias and both
  passwords are the standard debug ones, `androiddebugkey` and `android`; nothing else may live in
  this file. Because the private key is public, an APK anyone signs with it installs over this
  sample as an update and inherits its data, and the association file trusts it at the passkey
  sheet; the demo domain holds nothing of value, and this fingerprint must never be listed on a
  domain that does. To print the fingerprint:

  ```bash
  /usr/bin/keytool -list -v -keystore app/demo-debug.keystore -alias androiddebugkey -storepass android -keypass android | grep 'SHA256:'
  ```

## Project structure

```
app/src/main/java/com/rain/sdk/sample/
├── MainActivity.kt          # App entry + Compose navigation host (wrapped in RainTheme)
├── RainSampleApp.kt         # Application: session store + vendor init at launch
├── CollateralMetadata.kt    # Fills collateral tokens' name, symbol and decimals from RainSdk.tokenMetadata
├── Screen.kt                # Route definitions for the seven screens
├── RainSession.kt           # Holds the built RainSdk + resolved RainClient; prepares the Rain wallet provider, builds Turnkey
├── RainApiClient.kt         # The demo's own Rain API client (contracts, withdrawal signature): host reference code
├── SessionStore.kt          # Encrypted store of the last working ids and credentials
├── ContactChannel.kt        # Email or phone: the per-channel copy for the tabs that offer both
├── WalletSessionStatus.kt   # Provider session state as the Home screen shows it
├── WalletChain.kt           # Demo networks, explorer links, address validation
├── SampleEnvironment.kt     # Sandbox vs production: Rain API host, Auth pull operator
├── SampleLog.kt             # Logging helper
├── PrivyAuthSample.kt       # Privy email-OTP + embedded wallets
├── TurnkeyAuthSample.kt     # Turnkey bring-your-own: init, email OTP, one wallet with both accounts
├── ui/                      # Rain design system port (see Design below)
│   ├── theme/               # RainColors, RainType, RainRadius, RainTheme
│   └── Rain*.kt             # Buttons, inputs, surfaces, text, scaffold, defaults
└── screens/                 # One Screen + ViewModel pair per feature, plus shared helpers
    ├── Common.kt            # Address/hash/money formatting, TransactionResultCard
    ├── SecretHandling.kt    # Sensitive clipboard copy with a timed clear, FLAG_SECURE while a secret shows
    ├── HomeProviderCards.kt # Turnkey / Portal / Privy connection cards and the shared contact and code steps
    ├── HomeRainWalletCard.kt # Rain Wallet card: code or passkey sign-in, add a passkey, attach a login contact
    ├── HomeExportKeysCard.kt # Export keys card for a signed-in Rain Wallet session
    ├── HomeScreen / HomeViewModel
    ├── WalletInfoScreen / WalletInfoViewModel
    ├── BalancesScreen / BalancesViewModel
    ├── SendTokensScreen / SendTokensViewModel
    ├── CollateralWithdrawScreen / CollateralWithdrawViewModel
    ├── AuthPullScreen / AuthPullViewModel
    └── TransactionHistoryScreen / TransactionHistoryViewModel
app/src/main/res/drawable/   # Rain wordmark and Phosphor line icons as vector drawables
```

## Design

The screens follow the Rain brand system rather than stock Material: a white canvas, one typeface
in two weights (Light for body, Semibold for headings and labels), two type sizes (16 and 32, plus
12 for badges), hairline neutral borders on all four sides, 20dp cards, 4dp inputs, pill buttons
that settle to pink while pressed, sentence case throughout, and no emoji. Pink is reserved for the
wordmark, the icon tiles, and interaction states. The tokens live in `ui/theme` and the components
in the `ui/Rain*.kt` files.

Two things differ from the brand assets:

- **Typeface.** Rain's Antique Legacy is licensed and not checked in, so text renders on the
  platform sans at the same two weights. `RainType.fontFamily` documents the one-line swap for a build
  that has the OTFs in `app/src/main/res/font/`.
- **Icon tiles.** The home grid's wallet / coin / transaction / bank / secure / time glyphs are
  hand-drawn line icons in the Phosphor idiom inside the brand's pink container
  (`RainIconTile`). The design canvas uses the brand's "settle" (handshake) tile for Withdraw; a
  legible handshake needs the real asset, so the port uses the set's "bank" glyph; the `ic_tile_*`
  drawables are the swap point for the brand's PNGs or Phosphor's SVGs. Chrome icons (back, caret, copy, external
  link, check) are Phosphor's own paths.

## Key code

### Building the SDK

From `RainSession` — every provider follows the same builder shape, differing only in the registered
provider and the `ProviderId` resolved:

```kotlin
val sdk = RainSdk.builder()
    .rpcEndpoints(rpcEndpoints)                                   // Map<Int, String>
    .register(rainWalletProvider)                                 // prepared + authenticated first
    .registerTokens(WalletChain.selectable.map { it.defaultTokenInfo })
    .authPullConfig(SampleEnvironment.authPullConfig)
    .build()
val client = sdk.provider(ProviderId.RAIN)
```

For the SDK methods the screens call and their full parameter lists, see
[Method overview](../docs/METHODS.md).
