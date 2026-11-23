# Rain SDK sample app (Android)

A Jetpack Compose sample that exercises the public Rain SDK surface with a real wallet provider:
connect a wallet, read balances, send tokens, withdraw collateral, and list transaction history.

---

## Requirements

- Android Studio with its bundled JBR
- An emulator or device on API 28+. The passkey buttons on the Rain Wallet card also need a screen
  lock and a credential provider with a signed-in account; [Platform
  notes](../rain-wallet-android/README.md#platform-notes) has the per-version rule.
- The SDK modules this sample uses (`:rain-core-android`, `:rain-wallet-android`, `:rain-turnkey-android`,
  `:rain-portal-android`, `:rain-privy-android`)

## How to run

1. Open the repo root in Android Studio.
2. Select the **app** run configuration and a device.
3. Run.

Every screen has `@Preview` composables, so each state can be inspected in Android Studio without a
provider login.

---

## Screens

| Screen | What it exercises |
|---|---|
| **Home** | Provider choice (Rain Wallet / Turnkey / Portal MPC / Privy), Rain API credentials, auth, `RainSdk` build, session card, active-wallet dropdown, feature grid; for a signed-in Rain Wallet session the *Export keys* card (`exportRecoveryPhrase`, `exportPrivateKey`) |
| **Wallet & QR** | `getWalletAddress(chainId)` and the collateral deposit address from the demo's `RainApiClient` (a host's backend call), each with a QR bitmap from `generateAddressQRCode(address)` |
| **Balances** | Collateral balances from the demo's `RainApiClient` (names and decimals via `RainSdk.tokenMetadata`), and the wallet's native and token balances (`getBalance`, `getTokenBalances`) |
| **Send tokens** | `sendNative` and `sendToken` (ERC-20 on EVM, SPL on Solana) |
| **Withdraw collateral** | The withdrawal signature from the demo's `RainApiClient` + `withdrawCollateral`, with the `prepareWithdrawal` dry run on EVM and Solana collateral and the `estimateWithdrawalFee` overloads on EVM only |
| **Auth pull** | The collateral contract from the demo's `RainApiClient` as the spender, so the screen needs the Rain API credentials; `getTokenAllowance`, `estimateApprovalFee`, `approveTokenAllowance` + `confirmTokenAllowance`, and revocation |
| **History** | `getTransactions(chainId, limit, offset, order)`, newest-first |

Every feature screen reads the chain picked in the **Active wallet** dropdown on Home. Switching
networks needs no re-initialization, because the SDK is built with every chain's RPC endpoint at
once (`WalletChain.rpcEndpoints`; Portal gets the EVM subset, `evmRpcEndpoints`).

## Networks

`WalletChain` defines six networks, with each one's RPC URL, native symbol, explorer links, default
token and recipient, and address validation. A sandbox build offers Avalanche Fuji, Base Sepolia,
Arbitrum Sepolia and Solana devnet, hides the mainnet Auth Pull chains, and adds an Ethereum Sepolia
endpoint for collateral reads and withdrawals. The Portal adapter uses no Solana account, so selecting
Portal restricts the dropdown to the EVM chains.

## Providers

Portal, Turnkey and Privy authentication is the host app's job, and the Turnkey and Privy drivers
are reference code a host writes itself. The Rain wallet owns its authentication;
[rain-wallet-android/README.md](../rain-wallet-android/README.md) has the flows.

- **Rain Wallet** (`RainSession.prepareRainWallet`, `HomeRainWalletCard.kt`): the
  [Quickstart](../rain-wallet-android/README.md#quickstart) flow by email or phone, the
  [two-code](../rain-wallet-android/README.md#two-code-login) step, passkey sign-in, sign-up and
  add, and [attaching a login contact](../rain-wallet-android/README.md#attaching-a-contact). A
  phone number typed without a country code, here or on the Turnkey tab, is converted to E.164 with
  the device's region first, through `PhoneNumberUtils.formatNumberToE164`. The demo mints the
  wallet auth token on the device with the sandbox Api-Key and user ID from the Home card, where a
  host mints it on its backend ([Minting the wallet auth
  token](../rain-wallet-android/README.md#minting-the-wallet-auth-token)). The passkey buttons need
  this build's signing key, see *Demo keystore* under Notes. A failed step shows the error code;
  [Errors](../rain-wallet-android/README.md#errors) has the codes.
- **Export keys**: for a signed-in Rain Wallet session, a card reveals the recovery phrase or one
  private key through `exportRecoveryPhrase` and `exportPrivateKey`, with no biometric gate. While a
  value shows the window carries `FLAG_SECURE`, and the text carries password semantics, because
  accessibility services read through `FLAG_SECURE`. *Copy* puts it on the clipboard, flagged
  sensitive on Android 13 and later, and the clipboard clears itself after 60 seconds. *Hide*,
  *Clear session*, leaving the screen and session expiry hide it, and it is never written to saved
  state. The host's duties are under *Host duties* in
  [Key export](../docs/TURNKEY_SUPPORT.md#key-export) in docs/TURNKEY_SUPPORT.md.
- **Turnkey** (bring-your-own, `TurnkeyAuthSample` + `RainSession.initializeTurnkey`): parent
  organization ID, auth proxy config ID and an email or phone contact. The sample drives the Turnkey
  Kotlin SDK itself and hands the authenticated `TurnkeyContext` to `TurnkeyConfig(turnkey = ...)`;
  SMS codes need the auth-proxy setup under [Bring-your-own
  mode](../docs/TURNKEY_SUPPORT.md#bring-your-own-mode-the-public-turnkey-integration) in
  docs/TURNKEY_SUPPORT.md. This tab and the Rain Wallet card share one Turnkey singleton per launch,
  so the second to start shows a notice until a relaunch.
- **Portal MPC**: paste a Portal session token on Home and tap *Initialize SDK*.
- **Privy** (`PrivyAuthSample`): app ID + app client ID + email OTP; embedded Ethereum and Solana
  wallets are created on first sign-in.

Rain API credentials (program `Api-Key` + Rain `userId`) feed the demo's own `RainApiClient`
(contracts, withdrawal signatures, the token mint) from their own card on Home. A shipped app keeps
the key on its backend; section 7 of
[README.md](../README.md#7-rain-api-collateral-contracts-and-admin-signature-host-responsibility)
has the rule. The last working values live in an encrypted store (`SessionStore`) and resume the
session on the next launch; *Clear session* wipes them. `RainSession` registers each chain's default
token (`registerTokens`) and enables Auth Pull (`authPullConfig`) for every provider.

## Notes

- **Portal wallet recovery**: the sample has no Portal backup or recovery UI.
- **Solana history** rows carry the wallet backend's status id (the activity id when the send
  recorded none) rather than a resolvable signature, so those rows are not linked to an explorer.
- **Demo keystore.** `app/demo-debug.keystore` is committed on purpose and signs the debug and the
  release build. The passkeys are bound to its SHA-256 fingerprint through
  `https://passkeys.uptop.xyz/.well-known/assetlinks.json`, so a build signed with any other key,
  Android Studio's own debug key included, gets `RAIN_102` at the passkey sheet. The alias and
  passwords are the standard debug ones; never list this fingerprint on a domain that holds anything
  of value. To print the fingerprint:

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
├── SampleEnvironment.kt     # Sandbox vs production: Rain API host, Auth pull config
├── SampleLog.kt             # Logging helper
├── PrivyAuthSample.kt       # Privy email-OTP + embedded wallets
├── TurnkeyAuthSample.kt     # Turnkey bring-your-own: init, one-time code by email or SMS, one wallet with both accounts
├── ui/                      # Rain design system port; RainType.kt has the typeface swap
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

`RainSession` builds the SDK with one builder shape for every provider;
[Quick start](../README.md#quick-start) in README.md shows it.

For the SDK methods the screens call and their full parameter lists, see
[Method reference](../docs/METHODS.md).
