# Turnkey support

Rain SDK for Android supports [Turnkey](https://turnkey.com) as a wallet provider, as the `TurnkeyProvider` adapter in its own `rain-turnkey-android` module. Authentication has two modes. **Bring-your-own** (public): the host app authenticates with the official [Turnkey Kotlin SDK](https://docs.turnkey.com/sdks/kotlin/getting-started) and hands the live `TurnkeyContext` to Rain via `TurnkeyConfig(turnkey)`. **Managed** (internal API, `@InternalRainTurnkeyApi`): the SDK owns authentication and ships to hosts as the Rain wallet provider, `RainProvider` in `rain-wallet-android`. In both modes the adapter's hand-off is the same boundary: register the `TurnkeyProvider` with the `RainSdk` builder, then resolve `rain.provider(ProviderId.TURNKEY)`. A Rain wallet host registers `RainProvider` and resolves `ProviderId.RAIN` instead; [rain-wallet-android/README.md](../rain-wallet-android/README.md) shows it.

This guide states the contract; `5.0.0-beta.2` deviates from it in the entries under [Known issues](../CHANGELOG.md#known-issues) in CHANGELOG.md, which state each effect.

## Requirements

- `minSdk = 28` (matches Turnkey's requirement).
- Bring-your-own mode: the Turnkey Kotlin SDK initialized and authenticated by your app. Managed mode: nothing to initialize; the passkey flows need `passkeyDomain`, and [Passkeys and login contacts](../rain-wallet-android/README.md#passkeys-and-login-contacts) in rain-wallet-android/README.md has the rules.
- Of Turnkey's artifacts, `com.turnkey:encoding` ships Java 24 class files; a JVM unit test in your app that loads its classes needs a JDK 24 launcher. Android builds are unaffected: R8/D8 dexes the bytecode regardless of the host JVM.

## Adding the dependency

```kotlin
dependencies {
    // Pulls rain-core-android and the Turnkey artifacts transitively.
    implementation("xyz.rain:rain-turnkey-android:5.0.0-beta.2")
}
```

An app that does not register Turnkey should not depend on this module: the Turnkey artifacts come with it and with `rain-wallet-android`, which wraps this module; no other Rain module pulls them. The module pins Turnkey's `sdk-kotlin`, `http`, `types`, `crypto`, `passkey` and `stamper` artifacts (`encoding` arrives transitively); [gradle/libs.versions.toml](../gradle/libs.versions.toml) has the versions.

## Bring-your-own mode (the public Turnkey integration)

Initialize and authenticate Turnkey's Kotlin SDK as its [getting-started guide](https://docs.turnkey.com/sdks/kotlin/getting-started) describes; the sample's [`TurnkeyAuthSample.kt`](../app/src/main/java/com/rain/sdk/sample/TurnkeyAuthSample.kt) is reference code. Turnkey's `com.turnkey.core.models.TurnkeyConfig` is a different class from Rain's `TurnkeyConfig`.

A one-time code by SMS needs SMS one-time codes enabled on the auth proxy configuration.

Once the user is authenticated and `TurnkeyContext.session` is populated, hand it to Rain by
registering a `TurnkeyProvider`:

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyProvider
import com.turnkey.core.TurnkeyContext

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
                // sponsorGas defaults to true; the rules are under "Gas sponsorship" below.
                // sponsorRent defaults to false; the rules are under "Rent" in "Solana notes" below.
            )
        )
    )
    .build()

val client = rain.provider(ProviderId.TURNKEY)
```

Resolving the provider throws `RainError.WalletUnavailable` (`RAIN_404`) when the Turnkey wallet list has
no usable Ethereum account.

## Managed mode (internal API)

Managed mode is `TurnkeyConfig(application, organizationId, rainApiBaseUrl, authTokenProvider, passkeyDomain = ...)`: the SDK owns authentication, wallet provisioning and sessions, and the host supplies the wallet auth token its backend mints. It is the building block of the Rain wallet provider, `RainProvider` in `rain-wallet-android`, which opts in and exposes the same flow under Rain's names. [rain-wallet-android/README.md](../rain-wallet-android/README.md) owns the flow in wallet terms; the legs of each flow are in the KDocs of `TurnkeyManagedAuthController`. Every managed member carries `@InternalRainTurnkeyApi`, a `@RequiresOptIn` annotation at error level, so a host that calls one gets a compile error naming the reason. The members are that constructor and the `managedOrganizationId`, `managedRainApiBaseUrl`, `managedAuthTokenProvider` and `managedPasskeyDomain` accessors. The authentication members are `sendLoginCode` (a `LoginContact.Email` or `LoginContact.Phone`), `confirmLoginCode`, `sendSecondLoginCode`, `confirmSecondLoginCode`, `loginWithPasskey`, `signUpWithPasskey`, `addPasskey`, `sendContactVerificationCode`, `confirmContactVerification`, `logout`, `authState`, `awaitSessionRestore`, `hasActiveSession` and `currentAuthState`. The types are `LoginContact`, `LoginContactType`, `TurnkeyLoginOutcome`, `TurnkeyMaskedContact` and `TurnkeyAuthState`. In bring-your-own mode the authentication members throw `RainError.InvalidConfig`, except that `awaitSessionRestore` is a no-op, `hasActiveSession()` is `false` and `authState` and `currentAuthState()` read `Unauthenticated`.

## What Rain uses Turnkey for

After the Turnkey-backed `client` is resolved, each wallet operation routes as follows:

| Rain operation | Turnkey API used |
|----------------|------------------|
| `client.getWalletAddress()` | `TurnkeyContext.wallets` (first Ethereum account) |
| `client.getBalance(chainId, Token.Native)` | `TurnkeyClient.getWalletAddressBalances` (CAIP-19 `slip44:` filter) on supported chains when the organization has the balance feature; RPC `eth_getBalance` otherwise and on a refused read, as the paragraph below says |
| `client.getBalance(chainId, Token.Contract(...))` | RPC `eth_call` (`balanceOf`) |
| `client.getTokenBalances(chainId)` | `TurnkeyClient.getWalletAddressBalances` (CAIP-19) on supported chains when the organization has the balance feature, plus the chain read for the registry and registered tokens that [getTokenBalances](METHODS.md#gettokenbalanceschainid) describes, cost and skip rule included. Otherwise, and on the feature refusal (the paragraph below), Multicall3 / parallel `eth_call` over the registry and registered tokens alone |
| `client.sendNative(...)` / `client.sendToken(...)` | `TurnkeyClient.ethSendTransaction`, the activity rules under [After a send is accepted](#after-a-send-is-accepted), then `getSendTransactionStatus` polling. The chains a send runs on are under [Which chains can send](#which-chains-can-send); who pays the fee, and the Monad rule, under [Gas sponsorship](#gas-sponsorship). |
| `client.withdrawCollateral(...)` | EVM: `TurnkeyContext.signRawPayload` (EIP-712) + `ethSendTransaction`. Solana: core composes the withdrawal, then `solSendTransaction` (see [Solana notes](#solana-notes)). The chain gate is under [Which chains can send](#which-chains-can-send), the skipped dry run under [Gas sponsorship](#gas-sponsorship), the skipped rent check under *Rent* in [Solana notes](#solana-notes), and a failed status maps under [Error handling](#error-handling). |
| `client.getTransactions(...)` | `TurnkeyClient.listEthTransactionHistory`, the indexed history, when the organization has the transaction history feature: receives and externally submitted transactions included, EVM addresses in EIP-55 form. Otherwise `TurnkeyClient.getActivities` filtered to `ACTIVITY_TYPE_ETH_SEND_TRANSACTION`: sends only, with the activities Turnkey failed or rejected left out, since nothing was broadcast. The fallback runs only when Turnkey refuses the indexed query. That is HTTP 403 for an organization without the feature, logged once per provider, or any other refused status, retried first when transient and logged as a warning each time. A dead session, a transport failure or a page that could not be decoded surfaces as its own error. |
| `client.estimateGas(...)` | RPC `eth_estimateGas` + `eth_gasPrice` on every EVM chain (a Solana chain id is refused, `RAIN_102`); what the quote means on a sponsored chain is under [Gas sponsorship](#gas-sponsorship) |

Turnkey enables the balance service and the indexed transaction history per organization; an
organization on Turnkey's Free plan answered HTTP 403 to both queries
(`get_wallet_address_balances`, `list_eth_transaction_history`) in Rain's testing. That 403 is the
feature refusal: it is logged once per provider, and balance reads then go to the chain without
asking Turnkey again. The native balance read also falls back on any other refused status, retried
first when transient and logged as a warning each time. The token list falls back on 403 alone; any
other refused status surfaces as its own error, because the chain's token list is not the backend's
and each lists tokens the other lacks. A dead session or a transport failure surfaces as its own
error on every read, under [Error handling](#error-handling). After the feature refusal, a chain with no RPC endpoint surfaces `RAIN_102`,
the setting to fix, from both reads; after any other refusal `getBalance` surfaces that refusal
instead.

## Which chains can send

A send through this adapter, in either mode, runs only on a chain Turnkey's managed transaction
path broadcasts on. `TurnkeyBroadcastChains` holds the list, a cross-platform contract shared by
Rain's SDKs; Turnkey's [transaction management](https://docs.turnkey.com/features/transaction-management)
page is its source. EVM: Ethereum, Optimism, BNB Smart Chain, Polygon, Monad, Tempo, Robinhood
Chain, Base and Arbitrum One, each with its test network. The test networks are Sepolia, Optimism
Sepolia, BNB testnet, Polygon Amoy, Monad testnet, Tempo Moderato, Robinhood Chain testnet, Base
Sepolia and Arbitrum Sepolia. Solana: mainnet (900) and devnet (901); the testnet cluster (902) is
read-only. A send on any other configured chain throws `RainError.ChainNotSupported` (`RAIN_104`)
before anything is read or signed. The one read that can precede it is a `sendToken`'s on-chain
`decimals()` lookup for a token outside the registry. That covers `sendNative`, `sendToken`,
`withdrawCollateral`, the Auth Pull approvals and raw sends alike, because every EVM broadcast
funnels through the same gate.
Rain chains outside the list (Avalanche, Celo, ZKsync, Plasma, Ink) are read-only through this
provider. Reads (balances, history, fee estimates) are not gated; they need an RPC endpoint registered for
the chain unless Turnkey answers them (balances on its indexed chains, indexed history). `prepareWithdrawal` is not gated either: it signs or composes but never
broadcasts, and its result is what the host submits itself. The list is the vendor's capability, not
Rain's product offering; Rain's supported chains and tokens are at [docs.rain.xyz](https://docs.rain.xyz).
A send works only on a chain in both lists; a self-paid EVM send and every Solana send also need
an RPC endpoint registered.

## Gas sponsorship

`TurnkeyConfig.sponsorGas`, on by default, is one decision for the whole provider, in either mode;
the Rain wallet's `RainWalletConfig.sponsorGas` is the same flag. With it on, every EVM send on a
chain under [Which chains can send](#which-chains-can-send) is sponsored by Turnkey's Gas Station:
transfers, collateral withdrawals, Auth Pull approvals and raw `sendTransaction` calls alike. The
provider then advertises `Capability.GAS_SPONSORSHIP`, which tells core to skip the self-paid
preflights that would charge the fee to the wallet. A sponsored EVM send is a minimal payload:
Turnkey's Gas Station builds and pays the outer EIP-7702 transaction, so the wallet's own account
nonce and fee fields are left out. The request carries Turnkey's gas-station nonce for replay
protection, fetched with one Turnkey call rather than a chain RPC, so it costs the wallet nothing.
Solana sends are sponsored too, the network fee only; what the preflight then skips, and the rent
of a recipient's token account, a separate switch (`sponsorRent`), are under [Solana notes](#solana-notes). Sponsorship cost passes through to
the partner that turns the flag on. Fee estimates are not affected: `estimateGas`,
`estimateWithdrawalFee` and `estimateApprovalFee` quote what the wallet would pay to send the
transaction itself, `eth_estimateGas` times `eth_gasPrice` over the chain's RPC. On a sponsored
chain a sponsor pays instead, through an outer transaction whose cost is not quoted, so the number
shows what sponsorship saves the user. A sponsored send runs no client-side revert preflight, so a
failure surfaces through Turnkey's decoded failed status after broadcast, under
[After a send is accepted](#after-a-send-is-accepted). Turnkey enables Gas Station per
organization, a plan-gated feature per
[Transaction management](https://docs.turnkey.com/features/transaction-management#gas-sponsorship-aka-gas-abstraction-gasless-transactions-fee-abstraction);
an organization on the Free plan answered HTTP 403 to `get_nonces` in Rain's testing. On an
organization without it Turnkey rejects sponsored sends, so pass `TurnkeyConfig(sponsorGas = false)`
there and users pay their own gas. On Monad (`143`, `10143`) Turnkey sponsors through EIP-7702
delegation, and Monad reverts any delegated-account transaction that would leave the balance under
10 MON. A sponsored native MON send from a wallet below that fails on chain even when the estimate
succeeds; a token send does not.

## After a send is accepted

The EVM and Solana sends follow one rule once Turnkey has accepted the activity: the call reports a
failure only when the send has settled. A host that retries a reported failure would send the money
twice. The vendor's client polls the activity for about four seconds, its `ActivityPollerConfig`,
1 s and 3 retries by default. A bring-your-own host can set the `activityPoller` field of the vendor
`TurnkeyConfig` it initializes Turnkey with; the Rain wallet keeps the default. When the activity is
not completed by then the client throws `TurnkeyHttpError.ActivityNotCompleted` carrying it. The
adapter takes the activity from that error, reads it again by id at the polling interval while
Turnkey is still executing it (up to ten reads, under the session rules below), then classifies it.
Completed with a status id continues to the status poll: on EVM `getSendTransactionStatus` is read
once a second, 30 reads at most. A hash ends the send; a failed status maps under
[Error handling](#error-handling); a status read that failed after its retries, or 30 reads
without a hash, ends the send as `TransactionPending` carrying the status id. Failed or rejected
before broadcasting anything (a policy denial, for example) is a `ProviderError` with Turnkey's
reason, and nothing moved. Still pending, or completed without a status id this build can read, is
`TransactionPending` carrying the activity id; no call takes that id, and it matches `uniqueId` on
the activity-log history row for the send. An answer below 500 to the submit is a refusal: nothing
was executed, the send fails as any refused request does, and a 401 there is refreshed and retried
once. Any other failure after the submit (a 5xx answer, a poll read that failed, a lost or
undecodable answer) leaves the activity with Turnkey and its id unknown to the adapter. The send
then surfaces as `TransactionOutcomeUnknown` (`RAIN_305`), which says the send may still land and
names the vendor's failure. That error is not retry-safe; read history or the balance before
sending again.

## Solana notes

The Turnkey adapter advertises `MULTI_CHAIN`: it holds an Ethereum and a Solana account. Solana
sentinel chain ids (`RainChain.SOLANA_MAINNET` 900 / `SOLANA_DEVNET` 901 / `SOLANA_TESTNET` 902)
route `getWalletAddress(chainId)`, balances, `sendNative`, `sendToken`, `withdrawCollateral`, and
`getTransactions` to the Turnkey Solana account. Which clusters a send runs on is under
[Which chains can send](#which-chains-can-send).

- **Transfers.** Core composes the transfer and, for an SPL transfer, runs every preflight; the
  adapter signs and broadcasts. A missing recipient token account is created in the same
  transaction (rent under *Fees and rent* below). Typed failures include `TokenNotFound`,
  `TokenAccountNotFound`, `InsufficientTokenBalance`, `InvalidRecipient`, `InvalidAmount` and
  `InvalidConfig` (no RPC endpoint, or a malformed address); the dry run's revert is
  `TransactionSimulationFailed`.
- **Fees and rent.** The sender pays the network fee and, when the transfer creates the recipient's
  token account, its rent. That rent is the cluster's rent-exempt minimum for the account, read
  with `getMinimumBalanceForRentExemption`: 1,488,440 lamports, about 0.0015 SOL, on mainnet and
  devnet in October 2026. An SPL transfer checks both before anything is signed and throws
  `InsufficientFunds` carrying `required` and `available` in SOL. With the fee sponsored only the
  rent is checked, and with rent sponsored too (*Rent* below) nothing is; the rent read happens
  only when the send creates the account and the rent is not sponsored. A sponsored SPL transfer
  also skips the `simulateTransaction` dry run, which would charge the fee to a sender who pays
  none. A native SOL transfer checks the amount, plus the fee unless sponsored, before anything is
  signed, and runs no dry run. It must also leave the wallet empty or holding the rent-exempt
  minimum for a wallet (650,240 lamports on mainnet and devnet in November 2026), because the
  runtime fails a transfer that leaves less; the shortfall is the same `InsufficientFunds`. Any
  other failure surfaces after the submit through the failed status, under
  [Error handling](#error-handling).
  The collateral withdrawal shares the preflight: a recipient without a token account costs the
  owner rent there too, checked up front unless rent is sponsored, and always on
  `prepareWithdrawal`; its fee counts the two signatures, the owner's and the ed25519 verify's. A
  self-paid dry run the runtime refuses for a fee or rent shortfall is `InsufficientFunds` as well,
  with the lamports the System Program's `Transfer: insufficient lamports` line names.
- **Rent.** Fee sponsorship covers the network fee only; rent sponsorship is Turnkey's separate
  "Sponsor Solana Rent" switch
  ([Solana rent sponsorship](https://docs.turnkey.com/features/networks/solana-rent-refunds)): an
  Enterprise feature, off by default, billed against the same spend limits. With it on, Turnkey
  pre-funds the signer for the rent-exempt amount when an instruction creates an account that
  signer pays for. That signer is the sender in both of the SDK's composers, so the transaction is
  unchanged. `TurnkeyConfig(sponsorRent = true)` tells the SDK the switch is on, so it stops
  demanding that rent before signing. It is read with `sponsorGas` only, since the pre-funding is
  part of a sponsored send, and `prepareWithdrawal` keeps the self-paid check. Passed true while
  the switch is off, a send from a wallet holding no SOL is signed and fails at the backend after
  submission. It reaches the host as `RAIN_402` when the failure detail carries the runtime's log
  line, otherwise as the revert the status names. The Rain wallet passes it always;
  [Capabilities and chains](../rain-wallet-android/README.md#capabilities-and-chains) in
  rain-wallet-android/README.md says what that means for a partner. The SDK's Solana sends never
  emit `CloseAccount`, so Turnkey's "deny sponsored Solana `CloseAccount`" policy is compatible
  with them and "deny sponsored Solana account lifecycle ops" refuses every first-time recipient.
  A recipient can close a token account the sponsor funded and keep the deposit; the guardrails
  for that live on the Turnkey organization, not in the SDK.
- **Balances.** From Turnkey's `get-balances` where it indexes the cluster; where it doesn't (devnet
  in particular), `getTokenBalances` discovers holdings from the node via `getTokenAccountsByOwner`
  against both token programs. Solana keeps token metadata off chain, so symbol / name stay null
  unless the mint is registered. These reads fall back to the node on any failure but a dead
  session, a transport failure included, and log no feature gate. The EVM reads in the table above
  fall back on a refusal only.
- **History.** The `getTransactions` row above applies: the indexed query is
  `list_sol_transaction_history`, and the fallback reads the activity log under either Solana send
  activity type (`ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2`, which the SDK posts, or
  `ACTIVITY_TYPE_SOL_SEND_TRANSACTION`). An indexed row's hash is the real signature; an activity-log
  row's hash is the Turnkey status id (the activity id when the send recorded none), not an
  explorer-resolvable signature.
- **Signature.** Turnkey answers a send with a status id, not a signature; `sendNative` and
  `sendToken` return the signature Turnkey's status reports once the send is included, else the one
  Rain recovers from the chain. When the
  pre-send baseline read failed, or nothing verifiable lands in time, the send surfaces as
  `TransactionPending` carrying the status id, the same contract as EVM, never the status id posing
  as a signature.
- **Collateral withdrawal.** Authorized differently from EVM: the coordinator executor signs a
  keccak-encoded withdraw message off chain, the admin signature the Rain API returns. Core
  composes a two-instruction transaction: a native ed25519 proof that the executor signed that
  exact message, then the program's `withdraw_single_signer_collateral_asset`. It reads the
  collateral account, its coordinator's executors and the mint's token program from chain, and
  derives the collateral-authority PDA and token accounts locally. It demands the owner's rent for
  a recipient token account it creates, unless rent is sponsored, and always on
  `prepareWithdrawal`. It simulates, self-paid only (a fee-sponsored provider skips the dry run on
  `withdrawCollateral`, never on `prepareWithdrawal`), then hands the bytes to the adapter, which
  signs them as-is: re-serializing would invalidate the embedded signature. Single-signer collateral only; the
  [withdrawCollateral](METHODS.md#withdrawcollateralchainid-addresses-amount-decimals-adminsignature-nonce)
  parameter table in METHODS.md says what each address is on Solana.

## Key export

`TurnkeyProvider` exports the wallet's recovery phrase and one private key per chain family, in bring-your-own and managed mode alike. The methods live on the descriptor, so a host can offer a backup right after login, before the SDK is built.

```kotlin
val provider = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext))

val phrase = provider.exportRecoveryPhrase()                              // space-separated BIP-39 words
val ethereumKey = provider.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM)   // "0x" + 64 lowercase hex characters
val solanaKey = provider.exportPrivateKey(TurnkeyKeyFamily.SOLANA)       // plain Base58 of the 64-byte seed || public key
```

**Formats**, a cross-platform contract shared by Rain's SDKs:

- Recovery phrase: the wallet's BIP-39 phrase, 12 words for a managed wallet and the length it was created with for a bring-your-own one.
- Ethereum: the 32-byte secp256k1 key as `0x` plus 64 lowercase hex characters, the form MetaMask's *Import account* accepts.
- Solana: the 64-byte keypair, the seed followed by the ed25519 public key, in plain Base58 with no checksum, the form Phantom's *Import private key* accepts.

**Which wallet and account.** Keys follow the accounts the SDK signs with. The Ethereum key is the account behind `getWalletAddress()`, including a `walletAddress` override, and the Solana key is the account behind the Solana address. The phrase is the wallet holding that Ethereum account, else the first wallet, so the phrase and the Ethereum key always derive the same address. It restores every account derived from that wallet's seed and nothing else. A managed wallet keeps both accounts on one seed, so one phrase covers both. A bring-your-own organization with several wallets gets one wallet's phrase, and a key may come from another wallet. The checks a key passes before it is returned are the last rows of [Key export errors](#key-export-errors).

**Session.** Export is a Turnkey submit activity, so it needs a session that can submit; the one-time-code login produces one. Any live session suffices, including one the vendor restored from disk at launch. The SDK performs no re-authentication of its own, so a host that wants a fresh login or a biometric prompt before export adds it. A missing session, or one Turnkey refuses to submit with, fails the call; [Key export errors](#key-export-errors) has the mapping. In managed mode the first export of a launch runs the one-shot configuration first, like every auth call.

**Timing and retries.** The retries under [Session expiry, refresh, and retry](#session-expiry-refresh-and-retry) mean Turnkey's audit log may show more than one export activity for one call.

**Host duties.** The SDK decrypts on the device and hands the value back once. It never logs, caches or persists it. After the return, gate the call, for example behind biometrics, show the value where screenshots and screen recording are blocked, and keep it off the clipboard or clear it. The sample's *Export keys* card shows the screenshot block and the clipboard handling, with no biometric gate; see [app/README.md](../app/README.md). An exported phrase or key lives outside Turnkey's enclave from then on, and neither Rain nor the SDK can rotate it for the user. Once the user has imported it into another wallet, or recovered with it, encourage them to move the funds to a key the export never touched and retire the exported one.

**What leaves the device.** The wallet id or the account address, the organization id and a fresh P-256 public key go to Turnkey's export endpoints. An enclave-signed bundle encrypted to that key comes back and is decrypted on the device. Nothing goes to Rain.

**Capability resolution.** `TurnkeyProvider` advertises `Capability.EXPORT`, so `rain.first { Capability.EXPORT in it.capabilities }` can resolve its client. That client has no export method; the methods live on the `TurnkeyProvider` the host registered.

**Checking an import.** Restore the phrase in MetaMask: the first Ethereum account equals `getWalletAddress()`. Restore it in Phantom: the Solana account equals the Solana address. Import the Ethereum key in MetaMask and the Solana key in Phantom: the same two addresses.

**Minified builds.** The vendor's export code references `org.bitcoinj.core.Base58`, a class the bitcoinj version the SDK depends on does not ship, so the adapter's published consumer rules carry `-dontwarn org.bitcoinj.core.Base58`. The rule reaches the host's whole R8 configuration, so host code that references that class itself, or calls the vendor's Solana export format directly, is silenced too and throws `NoClassDefFoundError` at runtime. Move such code to `org.bitcoinj.base.Base58` or the SDK's export.

## Accessing the Turnkey instance

Rain exposes no vendor getters. In bring-your-own mode you already own the `TurnkeyContext` you
authenticated and passed to `TurnkeyConfig`. In managed mode the SDK configured that same
process-wide `TurnkeyContext` object; treat it as read-only: creating, selecting or clearing
sessions behind Rain's back is unsupported, and the vendor configuration itself is one-shot per
launch. Rain clears the selected session when Turnkey refuses to refresh it, see
[Session expiry, refresh, and retry](#session-expiry-refresh-and-retry).

## Error handling

Whichever vendor wrapper carries a Turnkey HTTP failure and whichever row below produces the error, a `ProviderError` or `InternalError` reaches the host with the call and the status only. The message is `HTTP error from <path or activity>: <status>`; the response body never reaches the host.

| Turnkey error | Mapped to |
|---------------|-----------|
| `TurnkeyKotlinError.InvalidSession` | `RainError.TokenExpired` |
| Managed mode: Rain's embedded-wallet endpoints, the passkey ceremonies (`FailedToLoginWithPasskey` / `FailedToSignUpWithPasskey`, `TurnkeyPasskeyError`, `TurnkeyStamperError`) and the sign-up's stamped login | By [Errors](../rain-wallet-android/README.md#errors) in rain-wallet-android/README.md |
| Turnkey API HTTP 401, outside a send's own poll, the passkey ceremonies and the passkey sign-up's stamped login | `RainError.TokenExpired` |
| Turnkey API HTTP 403, outside the passkey ceremonies and the passkey sign-up's stamped login | `RainError.Unauthorized` |
| `TurnkeyKotlinError.FailedToExportWallet`, and the checks the adapter runs around an export | [Key export errors](#key-export-errors) below: one table, in the order the checks run |
| Turnkey's configuration and setup errors: `MissingConfigParam`, `ClientNotInitialized`, `MissingRpId`, `InvalidParameter`, `InvalidResponse`, `InvalidMessage`, `InvalidRefreshTTL`, `OAuthStateMismatch`, `KeyAlreadyExists`, `KeyNotFound` | `RainError.InternalError` |
| Wrapper errors whose underlying cause is a user cancellation | `RainError.UserRejected` |
| A failed `getSendTransactionStatus` with decoded revert details, after a withdrawal | `RainError.WithdrawalRevertedByNetwork`, as for a failed dry run, with the transaction hash on `transactionId` and in the cause's message once included |
| A failed status with decoded revert details, after a transfer | `RainError.TransactionSimulationFailed`, with the transaction hash on `transactionId` once included |
| A failed status without decoded details: a broadcast, policy or blockhash failure | `RainError.ProviderError` |
| A failed status for a Solana fee or rent shortfall, checked before the two rows above: the runtime's `InsufficientFundsFor*` error, or the System Program's `Transfer: insufficient lamports` log line, then with the lamports it names | `RainError.InsufficientFunds` |
| A send whose answer was lost after the submit: a 5xx answer, a failed poll read (a 401 there included), a transport failure, an answer that did not decode | `RainError.TransactionOutcomeUnknown` (`RAIN_305`), never a re-submit. [After a send is accepted](#after-a-send-is-accepted) has the rule |
| `TurnkeyHttpError.ActivityNotCompleted` on any other submit (the contact attach, `addPasskey`, account provisioning): the activity still pending after the client's poll, needing consensus, failed or rejected | `RainError.ProviderError` (`RAIN_501`) carrying the activity's id, type, status and Turnkey's reason, never the activity. The key export has its own table |
| A request to Turnkey's API that got no answer, or a transient status (408, 429, 5xx) that still fails after the retries under [Session expiry, refresh, and retry](#session-expiry-refresh-and-retry), outside a send's submit and a session refresh | `RainError.ProviderError` (`RAIN_501`), never `RAIN_301`; a chain RPC read that got no answer is `RAIN_301` |
| A `refreshSession()` call that got no answer | `RainError.NetworkError` (`RAIN_301`) for a transport failure, `RainError.ProviderError` (`RAIN_501`) for a 408, 429 or 5xx; the session stays stored |
| The Rain wallet's attach of the contact the account already has: an HTTP 400 reading "is already set to" | `RainError.InvalidConfig` (`RAIN_102`) |
| Anything else, outside a send's submit | `RainError.ProviderError` |

Decoded revert details are `error.revertChain` or `error.eth.revertChain`, whether the transaction failed before inclusion or was included and reverted, or a Solana `InstructionError` or the runtime's `Program <id> failed` log line.

### Key export errors

Conditions are listed in the order the checks run, so the first that holds is the error a call gets. The session is checked when the wallet list has to be fetched and again at the export call itself.

| Condition | Mapped to |
|-----------|-----------|
| The provider was closed | `RainError.InvalidConfig` (`RAIN_102`), before anything else |
| No live session when the wallet list is fetched, or at the export call | `RainError.TokenExpired` (`RAIN_201`) |
| `walletAddress` names no Ethereum account of the organization: the phrase and both keys, before any export call, because the provider it configures cannot sign either | `RainError.InvalidConfig` (`RAIN_102`) |
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
            // session death, on the calling coroutine's thread or the watcher's: hop to the
            // main thread before touching UI. Route the user back to login.
        },
    )
)
```

What every wallet call does:

1. **Expiry check**: the session's JWT expiry is checked before the request. An
   already-expired session throws `RainError.TokenExpired` (or is refreshed first, see below)
   instead of burning a round-trip on a guaranteed 401.
2. **Proactive refresh**: with `autoRefresh` on (the default), a session expired or inside
   `refreshBufferSeconds` of expiry is refreshed through Turnkey's `refreshSession` before the
   call. Refreshes are single-flighted: concurrent calls share one refresh. A refresh re-selects
   the rotated session and reloads Turnkey's user and wallet state, so a bring-your-own host's
   `onSessionSelected` hook fires on it; a reload that fails is logged and is not a session death.
3. **Refresh-on-401**: a call rejected with HTTP 401 / `InvalidSession` is refreshed and
   retried exactly once. A 401 on the request itself means Turnkey rejected it before executing
   anything, so this is safe for sends too. A 401 inside the poll after a send was accepted stays
   inside the send as `RAIN_305`, under [After a send is accepted](#after-a-send-is-accepted); it
   never re-runs the submit. A second 401 surfaces as `RainError.TokenExpired`.
4. **Transient backoff**: idempotent reads (balances, history, transaction-status polls) and
   key export retry HTTP 5xx/429/408 and network I/O failures with exponential backoff. Sends and
   signing are never retried on transient failures.
5. **Re-auth hook**: when the session dies for good (Turnkey refused the refresh, or its own expiry
   timer cleared it while the app was idle), `onSessionExpired` fires once. It fires even with no
   Rain call in flight, through a passive watcher over Turnkey's auth state. In bring-your-own mode
   a session you clear through `TurnkeyContext` fires it too, since the watcher reads the clear as
   a death. A refresh Turnkey refused with HTTP 401 also clears the stored session before the call
   throws `RainError.TokenExpired`, so `currentSessionState()`, `currentAuthState()` and
   `hasActiveSession()` read signed out when it returns and `sessionState` emits it. A 401 there
   means the session was revoked, which a login elsewhere with `invalidateExisting`, a managed
   passkey login for example, does; a code login does not. A refresh that got no answer (offline,
   a 408, 429 or 5xx) is not a death: the session stays stored, the hook stays silent,
   `refreshSession()` throws as its row under [Error handling](#error-handling) says, and a wallet
   call proceeds on the session while it is unexpired. Any other failed refresh throws
   `RainError.TokenExpired` and leaves the session stored. The clear holds in both modes: in
   bring-your-own mode the session is removed from the `TurnkeyContext` the host owns, and the
   vendor's expiry timer for it is cancelled with it. A refresh whose session a login replaced while
   it was on the network proceeds with the new session instead.

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

provider.refreshSession()  // manual refresh; throws RainError.TokenExpired and clears the stored
                           // session when Turnkey refuses it
```

`sessionState` emits on every Turnkey auth/session change and additionally re-checks when an
active session passes its expiry instant, so a silent death is observable without polling.

Resolving the provider always starts a passive watcher over the process-wide Turnkey singleton: it
evicts cached accounts when the session dies and fires `onSessionExpired` when one is set. A host
that rebuilds the SDK per login should call `provider.close()` on the provider it is discarding so a
stale watcher cannot fire.

## Registering alongside Portal

`TurnkeyProvider` and the Rain wallet's `RainProvider` cannot share a builder; the `build()` row
under [RainSdk.Builder](METHODS.md#rainsdkbuilder) in docs/METHODS.md has the rule.

Turnkey and Portal can share one builder. Register both adapters on the same builder and resolve
each to its own `RainClient`, one SDK instance with two independent provider-bound clients:

```kotlin
val rain = RainSdk.builder()
    .rpcEndpoints(endpoints)
    .register(PortalProvider(PortalConfig(portalSessionToken)))
    .register(TurnkeyProvider(TurnkeyConfig(turnkeyContext)))
    .build()

val portalClient = rain.provider(ProviderId.PORTAL)
val turnkeyClient = rain.provider(ProviderId.TURNKEY)
```

## Bouncy Castle dependency conflict (downstream consumers)

Turnkey (via `com.turnkey:crypto` and `com.turnkey:encoding`) and `rain-core-android` depend on `org.bouncycastle:bcprov-jdk15to18`, while web3j depends on `org.bouncycastle:bcprov-jdk18on`. Both publish the same `org.bouncycastle.*` class names, so dexing them together fails with a duplicate-class error. Rain SDK standardizes on `bcprov-jdk15to18`, floored at the catalog's `bouncycastle` version by a published constraint, and publishes the `bcprov-jdk18on` exclusion in its Module Metadata. A Gradle consumer that reaches the vendor SDKs only through Rain's modules needs nothing. If your build still hits the duplicate-class error (older Gradle, Maven POM-only resolution, or a dependency of your own that pulls `bcprov-jdk18on`), add the exclusion in your own module:

```kotlin
// build.gradle.kts
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}
```

If you need `bcprov-jdk18on` for another library you control, swap the exclusion the other way and make every consumer compile against the same single BC artifact.
