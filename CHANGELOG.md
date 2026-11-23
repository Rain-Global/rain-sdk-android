# Changelog

Notable changes to the Rain Android SDK, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). An entry a host must act on starts with
**Breaking:** and carries a migration note.

## [Unreleased]

## [5.0.0-beta.2] - 2026-11-23

Second public beta. Read the **Breaking:** entries before upgrading from `5.0.0-beta.1`; each has
a migration note. Pin the exact version during the beta; see [Compatibility](README.md#compatibility).

### Added

- Two-code login on the Rain wallet. When a tenant turns the setting on, an account with both an
  email and a phone confirms a code from each. `confirmLoginCode` returns
  `RainWalletLoginOutcome.SecondCodeRequired` with the other contact masked, then
  `sendSecondLoginCode()` (call again to resend) and `confirmSecondLoginCode(code)` finish the
  login. No new error code. The three rows under [Members in rain-wallet-android/README.md](rain-wallet-android/README.md#members)
  carry the contract; the managed `TurnkeyProvider` (internal API) gains the same members.
- **Breaking:** `RAIN_305` `RainError.TransactionOutcomeUnknown`, a new `RainErrorCode` case and
  `RainError` subclass: a send the provider may have accepted before its answer was lost, with
  nothing to resume from, so it may still land. Raised by the Turnkey provider, the Rain wallet and
  the Privy provider. In 5.0.0-beta.1 that case was `RAIN_501`, the settled-failure code (Known
  issues under 5.0.0-beta.1). Migration: add a branch for `RAIN_305` or `TransactionOutcomeUnknown` wherever your
  code switches exhaustively, never resend on it, and read `RAIN_501` from a Turnkey or Rain wallet
  send as settled or refused, nothing moved. The `RAIN_305` row in
  [docs/METHODS.md](docs/METHODS.md#errors) and [After a send is
  accepted](docs/TURNKEY_SUPPORT.md#after-a-send-is-accepted) in docs/TURNKEY_SUPPORT.md have the
  cases.
- Rain USD (`0x6122C5964D39416C3F34475d00Ceb37f50700314`) and rUSD
  (`0x10b5Be494C2962A7B318aFB63f0Ee30b959D000b`), the two collateral tokens Rain's sandbox mints on
  Base Sepolia, in the built-in token registry, with 6 decimals each. A `registerTokens` entry for
  either address is ignored from now on, as for every built-in token.
- **Breaking:** `sponsorRent` on the bring-your-own `TurnkeyConfig`, off by default, with
  `WalletProvider.sponsorsRent(chainId)` in core. It says what the organization's "Sponsor Solana
  Rent" switch does. With it on, a Solana `sendToken` or `withdrawCollateral` that creates the
  recipient's token account no longer requires the sender to hold its rent before signing, since
  Turnkey pre-funds the sender for it inside the sponsored send. A wallet holding only USDC can
  then send USDC to a first-time recipient. The transaction is unchanged (the sender stays the
  create instruction's payer), `prepareWithdrawal` keeps the self-paid check, and the flag is read
  with `sponsorGas` only, because the pre-funding is part of a sponsored send. Passed true while
  the switch is off, a 0-SOL send is signed and fails at the backend after submission. It is
  reported as `RAIN_402` when the failure detail carries the runtime's log, instead of being
  refused before signing. Migration: `TurnkeyConfig` gains a trailing constructor parameter, so
  recompile against this version; a `WalletProvider` implementation inherits the default
  `sponsorsRent` and changes nothing. The Rain wallet has no such parameter; see the entry under
  Changed. *Rent* under [Solana notes](docs/TURNKEY_SUPPORT.md#solana-notes) in
  docs/TURNKEY_SUPPORT.md has the rules.

### Fixed

- **Breaking:** a Solana collateral withdrawal now needs `RainWithdrawAddresses.depositAddress`, the
  contract's `depositAddress` from Rain's contracts API, and refuses with `RAIN_102` when it is
  missing or the collateral program the RPC node reports does not derive it. Migration: pass
  `depositAddress = contract.depositAddress`. `RainWithdrawAddresses` gains a trailing constructor
  property, so recompile against this version on every chain.
- **Breaking:** `build()` refuses an `http://` RPC URL with `RAIN_102` unless the host is
  `localhost`, `127.0.0.1` or `::1`, and RPC calls no longer follow a redirect between https and
  http. Migration: use the endpoint's https URL.
- Wrong decimals in the token registry: Polygon rUSD is 18, Base DKUSD and Avalanche FRNT are 6.
- A self-paid Solana dry run the runtime refuses because the payer cannot cover the fee or the
  rent is `RAIN_402` `InsufficientFunds`, on `sendToken`, `withdrawCollateral` and
  `prepareWithdrawal` alike. The signs are the runtime's `InsufficientFundsForFee` or
  `InsufficientFundsForRent` error, or the System Program's `Transfer: insufficient lamports` line,
  which also supplies the lamports the error carries. 5.0.0-beta.1 reported it as `RAIN_403`, and
  `RAIN_405` on a withdrawal, so a host told the user the network rejected the transaction when
  they lacked SOL. A Turnkey or Rain wallet send status carrying that line is read the same way.
- A Solana `sendNative` checks, before anything is signed, that the wallet holds the amount, plus
  the fee unless the provider sponsors it, and that the send leaves the wallet empty or holding
  the rent-exempt minimum. A shortfall is `RAIN_402` `InsufficientFunds` with `required` and
  `available` in SOL, and a malformed address is `RAIN_102`. 5.0.0-beta.1 ran none of these
  checks.
- The Solana preflight reads the rent of a token account it has to create from the cluster
  (`getMinimumBalanceForRentExemption` for the 165-byte account) instead of pinning 2,039,280
  lamports. On 2026-10-29 both mainnet-beta and devnet answer 1,488,440, so 5.0.0-beta.1's
  self-paid `sendToken` and `withdrawCollateral`, and `prepareWithdrawal`, over-quoted that rent in
  the balance they demanded and in `required` on their `RAIN_402`. The read happens only when the
  send has to create the recipient's account and the rent is not sponsored, so sponsored and
  no-create sends make no new call. `required` on the error carries the chain's figure, which the
  SDK refuses to read as anything but a positive whole number. A collateral withdrawal's fee is
  billed for its two signatures, the owner's and the ed25519 verification's, where 5.0.0-beta.1
  demanded one.
- An EVM address written in mixed case whose EIP-55 checksum doesn't match is now refused with
  `RAIN_102` on sends and collateral withdrawals, before anything is signed. A send's recipient is refused as
  `InvalidRecipient`, a token contract or a withdrawal address as `InvalidConfig`. The check covers
  every method that takes `RainWithdrawAddresses`, the proxy passed to `RainSdk.getLatestNonce`, the
  wallet address `buildEIP712Message` signs for, and the Auth Pull token contracts at
  `RainSdk.Builder.build()`. 5.0.0-beta.1 checked only the address's shape, so a mistyped recipient
  or withdrawal address went through, and `sendToken` handed a malformed token contract to the
  provider. The `RAIN_102` rows in [docs/METHODS.md](docs/METHODS.md#errors) have the cases.
- The built-in token registry lists BNB Chain USDC and Celo USDT in EIP-55 form. 5.0.0-beta.1
  carried both with a wrong mixed-case checksum, so `tokenMetadata` and `registerTokens` refused
  the registry's own spelling of those two tokens, and the check above would have refused them on
  `sendToken` as well.
- Solana sends and collateral withdrawals through `rain-turnkey-android` and `rain-wallet-android`
  reach the chain: the wallet backend's client is `com.turnkey:sdk-kotlin` 2.1.0 with
  `com.turnkey:http` 2.2.0, which post the activity type the request body requires.
- A send through `rain-turnkey-android` or `rain-wallet-android`, EVM or Solana, is no longer a bare
  `RAIN_501` when the wallet backend was still executing it as its client stopped waiting, or failed
  or rejected it. The SDK reads the activity again and reports `RAIN_302` `TransactionPending` with
  the activity id while it is pending, or `RAIN_501` with the backend's reason when it failed or was
  rejected, nothing moved. In 5.0.0-beta.1 such an EVM send surfaced as `RAIN_501` with no activity
  id and no reason, which read as a failed send and invited a second one. Solana sends never got
  that far (the entry above). [After a send is
  accepted](docs/TURNKEY_SUPPORT.md#after-a-send-is-accepted) in docs/TURNKEY_SUPPORT.md has the
  rules.
- A wallet backend call that fails with an HTTP status is logged with the status and target. One
  that fails with an activity the backend's client could not complete is logged with the activity's
  id, type, status and reason. The error's cause carries the same. 5.0.0-beta.1 logged the backend's exception itself,
  whose message carries the HTTP response body.
- `getBalance(chainId, Token.Native)`, `getTokenBalances` and `getAllBalances` through
  `rain-turnkey-android` read the chain when the wallet backend refuses its balance service with
  HTTP 403, an organization without the balance feature. In 5.0.0-beta.1 every such read on
  Ethereum, Base, Polygon and their test networks failed with `RAIN_202`, and `getAllBalances`
  listed nothing for those chains. `rain-wallet-android` shares the code path; Rain's organization
  has the feature, so it is not affected. The Turnkey provider row under
  [getTokenBalances](docs/METHODS.md#gettokenbalanceschainid) in docs/METHODS.md and the balance
  rows under [What Rain uses Turnkey for](docs/TURNKEY_SUPPORT.md#what-rain-uses-turnkey-for) in
  docs/TURNKEY_SUPPORT.md have the rules.
- With Privy, two refusals for a shortfall of the native currency are now `RAIN_402`
  `InsufficientFunds`: an EVM send over the balance and a Solana `sendNative` from a wallet holding
  no SOL. 5.0.0-beta.1 answered the first with `RAIN_502` and the second with `RAIN_501`. The
  `RAIN_402` row in [docs/METHODS.md](docs/METHODS.md#errors) and
  [Errors](rain-privy-android/README.md#errors) in rain-privy-android/README.md have the cases.
- `PrivyProvider.refreshSession()` no longer reports a live session as dead because the device was
  offline, which 5.0.0-beta.1 did with `RAIN_201` and `onSessionExpired` (Known issues under 5.0.0-beta.1). A
  refresh with no network answer is `RAIN_301`, session kept and hook silent; only a refresh Privy
  refuses, or drops the session on, is `RAIN_201` with `onSessionExpired`. A wallet call through a
  wallet on Privy's server-wallet stack whose request never left the device is `RAIN_301` instead of
  `RAIN_501`; an on-device EVM wallet reports an offline device as prose and stays `RAIN_501`. A send
  whose answer was lost after the request left the device is `RAIN_305` (the entry under Added), not
  `RAIN_301`, since a retry could send twice; a lost signature answer is `RAIN_301`, nothing
  broadcast. A cancellation the vendor wrapped in its own failure leaves a Privy call as the
  cancellation. [Session expiry and retry](rain-privy-android/README.md#session-expiry-and-retry)
  and [Errors](rain-privy-android/README.md#errors) in rain-privy-android/README.md have the cases.
- The SDK clears a Rain wallet or Turnkey session revoked by a login on another device as soon as
  the wallet backend refuses to refresh it, inside `refreshSession()` and any wallet call that
  refreshes. `hasActiveSession()`, `currentAuthState()` and `currentSessionState()` then read signed
  out when the failed call returns, and `sessionState` and `authState` emit it. In 5.0.0-beta.1 they
  kept reporting the stored session's local expiry, for up to the session's whole lifetime, and a
  login flow that skips the code on `hasActiveSession()` restored the dead session. [Session expiry
  and retry](rain-wallet-android/README.md#session-expiry-and-retry) in
  rain-wallet-android/README.md and [Session expiry, refresh, and
  retry](docs/TURNKEY_SUPPORT.md#session-expiry-refresh-and-retry) in docs/TURNKEY_SUPPORT.md have
  the rules.
- `getTokenBalances` and `getAllBalances` through `rain-turnkey-android` and `rain-wallet-android`
  list every token in the SDK's built-in registry or passed to `registerTokens` that the wallet
  holds on Ethereum, Base, Polygon and their test networks. In 5.0.0-beta.1 the list held only what
  the wallet backend's balance service catalogues, so a sandbox collateral token such as Rain USD on
  Base Sepolia never appeared. [getTokenBalances](docs/METHODS.md#gettokenbalanceschainid) in
  docs/METHODS.md has the rules.
- Cancelling a coroutine that waits on an RPC read (balances, token metadata, allowances, receipts)
  cancels the HTTP call; before, the call ran to its answer or its 10-second timeout first. An RPC
  that answers with an `error` that is not an object fails as `RainError.InternalError` instead of
  a JSON exception.
- A Turnkey or Rain wallet Solana send or `getTransactions` stops when cancelled mid-read.
- `getLatestNonce` and the `isAdmin` read before a withdrawal or an Auth Pull approval return at once
  when their caller is cancelled; before, the caller waited for the read to answer or time out. A
  failed nonce read reports the original network error as its cause, no longer an
  `ExecutionException`.
- An RPC error with code 3, or one that names a revert only in its `data` string, is a revert, so a
  balance, token or allowance read, or a Turnkey or Rain wallet gas quote, answers `RAIN_403`, and
  `RAIN_405` on a withdrawal. 5.0.0-beta.1 read the message alone and answered `RAIN_502`.
- `confirmContactVerification` on the Rain wallet refuses a contact that already signs in to another
  wallet of the tenant, with `RAIN_202` and the code spent. In 5.0.0-beta.1 such an attach went
  through and the contact kept opening the other account. The attach still replaces an existing
  contact of the same kind instead of adding a second one; see Known issues under `5.0.0-beta.1`.
  [Attaching a contact](rain-wallet-android/README.md#attaching-a-contact) in
  rain-wallet-android/README.md has the outcomes.

### Changed

- **Breaking:** an Auth Pull approval names the user's collateral contract as its spender, where
  5.0.0-beta.1 approved Rain's operator. `RainAuthPullConfig` loses `operatorAddress`, and the SDK
  refuses a non-zero approval with `RAIN_407` unless the wallet is an admin of the spender. An
  allowance a wallet already gave the operator stays, and a zero-amount approval revokes it.
  Migration: drop the operator argument from the factory and pass the contract's `proxyAddress` as
  `spender`. [The spender](docs/AUTH_PULL.md#the-spender) in docs/AUTH_PULL.md has the rules.
- A transport failure or a cancellation while `confirmSecondLoginCode` checks the second code now
  ends the two-code attempt and deletes its key, as every refusal but a rejected code already did.
  Start again with `sendLoginCode`. Rain forgets the attempt before it finishes the login, so a
  retry of the same code came back as `RAIN_203` and a resend sent a new first code to the first
  contact.
- The Rain wallet leaves the rent of a first-time recipient's Solana token account to Rain's
  organization. With `sponsorGas` on, `sendToken` and `withdrawCollateral` no longer demand it from
  the sender before signing. A wallet holding only USDC can then send USDC to a first-time
  recipient once Rain's rent sponsorship is on. While it is off, such a send from a wallet holding
  no SOL is signed and fails at the backend after submission. It is reported as `RAIN_402`
  `InsufficientFunds` with the lamports the runtime's log names when the failure detail carries
  that log, where 5.0.0-beta.1 refused it before signing with the same code. No parameter: whether
  the rent is funded is Rain's setting, which a partner cannot see, and a change to it reaches
  installed apps without a release. With `sponsorGas` off the sender pays the fee and that rent,
  checked before signing as before.
- **Breaking:** `RainProvider.confirmLoginCode` and the managed `TurnkeyProvider.confirmLoginCode`
  return the login outcome instead of `Unit`. Migration: branch on `RainWalletLoginOutcome`; a call
  that ignores the result still compiles but leaves a two-code account between its codes.
- The SDK's own HTTP calls share one OkHttp client (`RainHttp` in `rain-core-android`, internal API).
  Two calls that 5.0.0-beta.1 ran with no whole-call bound now have one: the Turnkey wallet's chain
  reads at 10 s and the withdrawal reads at 60 s.
- **Breaking:** the Rain wallet authenticates through Rain's embedded-wallet endpoints
  (`/v1/issuing/embedded-wallets`) instead of Turnkey's auth proxy. `RainWalletConfig` gains
  `authTokenProvider` (required, first) and `environment` (`Production`, the default, or `Sandbox`),
  so `RainProvider(application)` with no config no longer compiles. The managed `TurnkeyConfig`
  (internal API) replaces `authProxyConfigId` with `rainApiBaseUrl` plus `authTokenProvider`. A code
  login no longer revokes other devices' sessions; a passkey login still does. A failed switch to
  the new session after a code login keeps the previous session selected, or the new one when the
  backend had already persisted it; 5.0.0-beta.1 signed the device out. Login and attach calls answer `RAIN_202` for a refused token or tenant, `RAIN_301`
  when Rain or your backend is unreachable (5.0.0-beta.1 answered `RAIN_501`), and `RAIN_102` for
  any other `authTokenProvider` failure.
  [Configuration](rain-wallet-android/README.md#configuration),
  [Errors](rain-wallet-android/README.md#errors) and
  [signUpWithPasskey](rain-wallet-android/README.md#signupwithpasskey) in
  rain-wallet-android/README.md carry the contract.
  - Accounts created under 5.0.0-beta.1 through the auth proxy are not found by Rain's login, so a code
    login creates a new, empty wallet for the contact. A passkey added under 5.0.0-beta.1 still opens
    the old wallet through `loginWithPasskey`, and a contact attached to that wallet does not become
    its code login. Before upgrading, have such users sign in on the 5.0.0-beta.1 build and
    export the recovery phrase or move their funds out.
  - Migration: mint the token on your backend with an Api-Key that holds `embeddedWallets:write` and
    pass `RainWalletConfig(authTokenProvider = ...)` to `RainProvider`, with
    `environment = RainWalletEnvironment.Sandbox` for a sandbox build. Handle `RAIN_202` and
    `RAIN_301` on your login screen; see [rain-wallet-android/README.md](rain-wallet-android/README.md).
- **Breaking:** `sendLoginCode` and `sendContactVerificationCode` lowercase an email before it
  reaches the backend, which matches emails case-sensitively. Migration: an account created from a
  mixed-case email under 5.0.0-beta.1 is keyed on that exact spelling, so a login with that address
  now misses it and signs up a new, empty account. Before upgrading, have such users sign in on the
  current build and export the recovery phrase or move their funds out. [Passkeys and login
  contacts](rain-wallet-android/README.md#passkeys-and-login-contacts) in
  rain-wallet-android/README.md has the rule.
- On a send through `rain-turnkey-android` or `rain-wallet-android`, EVM or Solana, `RAIN_302`
  `TransactionPending` may carry the wallet backend's activity id when the backend accepted the send
  without a readable status id. The activity-log history lists Solana sends under both of the
  backend's send activity types. See
  [TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md#after-a-send-is-accepted).
- On a Turnkey or Rain wallet organization without indexed history, the activity-log history that
  `getTransactions` falls back to leaves out sends the backend failed or rejected, on EVM and
  Solana, since nothing was broadcast. 5.0.0-beta.1 listed them as rows with the activity id as the
  hash.

### Removed

- `SolanaTransactionBuilder.buildTransferHex` (internal API), which nothing called;
  `UnsignedSolanaTransfer.transactionHex` is the hex form of an unsigned transaction.
- The `timeoutSeconds` parameter of `JsonRpcClient` (internal API): the client takes an `OkHttpClient`
  or runs on the shared one.

### Known issues

- The Rain wallet's contact attach, `sendContactVerificationCode` followed by
  `confirmContactVerification`, replaces the account's email address or phone number instead of
  adding a second one. The old contact then no longer logs in to the account. Tell users that an
  attach replaces their current contact.
- At launch with a saved session, `RainProvider.authState` and `sessionState`, and
  `TurnkeyProvider.sessionState`, can emit `Unauthenticated` for a few milliseconds between
  `Loading` and the live session. An app that opens its login screen on that `Unauthenticated`
  shows it to a user who is still signed in.

## [5.0.0-beta.1] - 2026-09-30

First public beta, published to Maven Central under `xyz.rain`. Pin the exact version during the
beta; see [Compatibility](README.md#compatibility).

### Added

- `xyz.rain:rain-core-android`, `rain-portal-android`, `rain-privy-android`, `rain-turnkey-android`
  and `rain-wallet-android`, all at `5.0.0-beta.1`. `rain-wallet-android` embeds the wallet
  organization Rain's sandbox API uses, so every account it creates lives there, in a release build
  too; the next release names that environment `RainWalletEnvironment.Sandbox`.
- `@ExperimentalRainApi`. Implementing `WalletProvider`, `ProviderDescriptor` or `RainClient`
  requires opting in to it, because Rain may add members to these interfaces in any release.

### Known issues

Rain plans to fix each of these in a later beta.

- Sends and collateral withdrawals accept an EVM address written in mixed case whose EIP-55
  checksum doesn't match. `sendNative`, `sendToken`, `withdrawCollateral`, `prepareWithdrawal` and
  `estimateWithdrawalFee` check only that the address is 40 hex characters, and so do the
  wallet-agnostic `RainSdk.buildEIP712Message`, `RainSdk.buildWithdrawTransactionData` and
  `RainSdk.getLatestNonce`. A mistyped character in a checksummed address therefore goes through,
  and the funds go to an address nobody may control. `sendToken` doesn't check its token contract at
  all. The Auth Pull operator and token contracts passed to `RainSdk.Builder.authPullConfig(...)`
  have the same gap. Until the fix, check mixed-case input in the app before calling, by comparing
  it with its EIP-55 form from any EIP-55 implementation. The fix is in 5.0.0-beta.2.
- Solana sends through the Turnkey provider and the Rain wallet fail with `RAIN_501`
  (`ProviderError`), and nothing reaches the chain. The wallet backend rejects the request with
  HTTP 400. This affects `sendNative`, `sendToken` and `withdrawCollateral` on Solana chains. EVM
  sends, Solana reads, `prepareWithdrawal` and Privy's Solana sends are not affected. The fix is
  in 5.0.0-beta.2.
- A send whose answer was lost after it was submitted, through any provider, arrives as `RAIN_501`
  (`ProviderError`), the code a send the provider settled as failed or refused carries too. An app
  therefore cannot tell a send it may retry from one it must not. Never resend on `RAIN_501` from
  a send before reading history or the balance. The fix, `RAIN_305`, is in 5.0.0-beta.2.
- `PrivyProvider.refreshSession()` with the device offline throws `RAIN_201` and fires
  `onSessionExpired`, although the session is live and the next refresh online succeeds; on a
  session restored while offline it throws `RAIN_201` without firing the hook. A host that logs
  the user out on the hook logs them out after a moment offline. Until the fix, check connectivity
  before acting on the hook, or confirm the death with a second `refreshSession()` once the device
  is online. The fix is in 5.0.0-beta.2.
- With Privy, a send the chain refuses for a shortfall of the native currency can arrive with the
  wrong code. An EVM send over the balance arrives as `RAIN_502` (`InternalError`,
  "EVM error: OutOfFunds") and a Solana `sendNative` from a wallet holding no SOL as `RAIN_501`
  (`ProviderError`). Both mean `RAIN_402` (`InsufficientFunds`). A Solana `sendNative` from a
  wallet holding some SOL but less than the amount plus the fee also arrives as `RAIN_501`, and
  stays so after the fix. An app that retries on `RAIN_501` or `RAIN_502` resends a transfer that
  cannot succeed. The fix is in 5.0.0-beta.2.
- The Rain wallet's contact attach, `sendContactVerificationCode` followed by
  `confirmContactVerification`, replaces the account's email address or phone number instead of
  adding a second one. The old contact then no longer logs in to the account. The attach also
  succeeds for an email address that already logs in to another account. That address keeps
  opening the other account, which leaves the signed-in account with no working email login.
  The replacement is not fixed in 5.0.0-beta.2; the attach of a contact another account
  signs in with is refused there, see Fixed under 5.0.0-beta.2. Tell users that an attach replaces
  their current contact.
- A session revoked by a login on another device keeps reading as active on the first device.
  `hasActiveSession()`, `currentAuthState()` and `currentSessionState()` report the stored
  session's local expiry until it passes, up to the session's whole lifetime, while every wallet
  call fails with `RAIN_201` and `onSessionExpired` fires once. A login flow that skips the code on
  `hasActiveSession()` restores the dead session. Call `refreshSession()` and treat `RAIN_201` as
  signed out before skipping the code. The fix is in 5.0.0-beta.2.
- `getTokenBalances` and `getAllBalances` can leave out a token the wallet holds. On Ethereum, Base,
  Polygon and their test networks the Turnkey provider and the Rain wallet build the list from the
  wallet backend's balance service, which reports only the assets it catalogues. A registry or
  registered token it lacks, Rain USD on Base Sepolia for example, is missing. `getBalance(chainId,
  Token.Contract(address))` reads a single token from the chain and works for any token. The fix is
  in 5.0.0-beta.2.
- On a Turnkey organization without the wallet backend's balance feature, `getBalance(chainId,
  Token.Native)` and `getTokenBalances` through `rain-turnkey-android` fail with `RAIN_202`
  (`Unauthorized`, "HTTP 403") on Ethereum, Base, Polygon and their test networks.
  `getAllBalances` lists nothing for those chains; nothing falls back to the chain.
  `getBalance(chainId, Token.Contract(address))` reads the chain and works. Rain's own organization
  has the feature, so `rain-wallet-android` is not affected. The fix is in 5.0.0-beta.2.
- At launch with a saved session, `RainProvider.authState` and `sessionState`, and
  `TurnkeyProvider.sessionState`, can emit `Unauthenticated` for a few milliseconds between
  `Loading` and the live session. An app that opens its login screen on that `Unauthenticated`
  shows it to a user who is still signed in.
