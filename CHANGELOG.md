# Changelog

Notable changes to the Rain Android SDK, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). An entry a host must act on starts with
**Breaking:** and carries a migration note.

## [Unreleased]

### Added

- **Breaking:** `RAIN_305` `RainError.TransactionOutcomeUnknown`, a new `RainErrorCode` case and a
  new `RainError` subclass: a send through `rain-turnkey-android` or `rain-wallet-android`, EVM or
  Solana, that the wallet backend may have accepted before its answer was lost (a 5xx answer to the
  submit, a failed poll read, a transport failure, an answer that did not decode). The SDK has no
  activity id to resume from, so the error says the send may still land and names the backend's
  failure; read history or the balance before sending again. In 5.0.0-beta.1 that case was
  `RAIN_501`, the code a send the backend settled as failed carries too, so a host could not tell a
  send it may retry from one it must not. Migration: add a branch for `RAIN_305` or
  `TransactionOutcomeUnknown` wherever your code switches exhaustively on the code or the class,
  never resend on it, and read `RAIN_501` from a Turnkey or Rain wallet send as settled or refused,
  with nothing moved. See [TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md#after-a-send-is-accepted).
  The Privy provider raises it too, for a send whose answer was lost after the request left the
  device; see the Privy entry under Fixed.
- Rain USD (`0x6122C5964D39416C3F34475d00Ceb37f50700314`) and rUSD
  (`0x10b5Be494C2962A7B318aFB63f0Ee30b959D000b`), the two collateral tokens Rain's sandbox mints on
  Base Sepolia, in the built-in token registry, with 6 decimals each. A `registerTokens` entry for
  either address is ignored from now on, as for every built-in token.

### Fixed

- Sends and collateral withdrawals refuse an EVM address written in mixed case whose EIP-55
  checksum doesn't match, with `RAIN_102`, before anything is signed. A mistyped character almost
  always breaks the checksum, and 5.0.0-beta.1 checked only the address's shape, so `sendNative`,
  `sendToken`, `withdrawCollateral` and `prepareWithdrawal` went ahead with the mistyped address. A
  send's recipient is refused as `InvalidRecipient`, a withdrawal address as `InvalidConfig`. The
  same check guards every method that takes `RainWithdrawAddresses`, `estimateWithdrawalFee` and
  the wallet-agnostic `RainSdk.buildEIP712Message` and `RainSdk.buildWithdrawTransactionData`
  included, the proxy passed to `RainSdk.getLatestNonce`, and the wallet address
  `buildEIP712Message` signs for. `sendToken` now checks its token contract the same way, and a
  malformed contract fails with `RAIN_102` before the provider is called, where 5.0.0-beta.1 handed
  it to the provider and surfaced the provider's error. `RainSdk.Builder.build()` checks the Auth
  Pull operator and token contracts. An address written in a single letter case carries no
  checksum and is accepted as before.
- The built-in token registry lists BNB Chain USDC and Celo USDT in EIP-55 form. 5.0.0-beta.1
  carried both with a wrong mixed-case checksum, so `tokenMetadata` and `registerTokens` refused
  the registry's own spelling of those two tokens, and the check above would have refused them on
  `sendToken` as well.
- Solana sends and collateral withdrawals through `rain-turnkey-android` and `rain-wallet-android`
  reach the chain: the wallet backend's client is `com.turnkey:sdk-kotlin` 2.1.0 with
  `com.turnkey:http` 2.2.0, which post the activity type the request body requires.
- A send through `rain-turnkey-android` or `rain-wallet-android`, EVM or Solana, whose activity the
  wallet backend had not finished executing when its client stopped waiting (about four seconds),
  or which the backend failed or rejected, is no longer a bare `RAIN_501` with no activity id. The
  SDK reads the activity again by id, up to ten reads one polling interval apart, then reports
  `RAIN_302` `TransactionPending` carrying the activity id while it is still pending, and `RAIN_501`
  carrying the backend's reason when it failed or was rejected, in which case nothing moved. In
  5.0.0-beta.1 an EVM send in that state surfaced as `RAIN_501` with no activity id and no reason,
  which read as a failed send and invited a second one; Solana sends never got that far (the entry
  above). See [TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md#after-a-send-is-accepted).
- A wallet backend call that fails with an HTTP status, or with an activity the backend's client
  could not complete, is logged with the status and target, or with the activity's id, type, status
  and reason, and the same is what the error's cause carries. 5.0.0-beta.1 logged the backend's
  exception itself, whose message carries the HTTP response body.
- `getBalance(chainId, Token.Native)`, `getTokenBalances` and `getAllBalances` through
  `rain-turnkey-android` read the chain when the wallet backend refuses its balance service with
  HTTP 403, an organization without the balance feature, the way `getTransactions` already falls
  back to the activity log: the gate is logged once per provider, after which the provider reads the
  chain without asking the backend again. The native balance also falls back on any other refused
  status, retried first when transient and logged as a warning each time, because the chain gives
  the same number; the token list does not, because the backend and the registry each list tokens
  the other lacks, so such a status surfaces as before for the host to retry. Before, on a Turnkey
  organization without the feature, every such read on Ethereum, Base, Polygon and their test
  networks failed with `RAIN_202`, and `getAllBalances` listed nothing for those chains. The chain
  read lists the SDK's built-in tokens and those passed to `registerTokens`. `rain-wallet-android`
  shares the code path; Rain's organization has the feature.
- With Privy, two refusals for a shortfall of the native currency that arrived with the wrong code
  are `RAIN_402` `InsufficientFunds`: an EVM send over the balance, which the `eth_call` preflight
  now reads through the shared classifier (it knows "EVM error: OutOfFunds", Base Sepolia's node's
  wording), and a Solana `sendNative` from a wallet holding no SOL, which Privy's wallet API
  refuses with the node's "found no record of a prior credit". Every 4xx from that API other than
  a 401 or 403, on any wallet-API call, is now read as the preflight reads a node answer before it
  floors at `RAIN_501`: a revert is `RAIN_403`, then a funds shortfall is `RAIN_402`. A Solana
  `sendNative` from a wallet that holds some SOL but not the amount plus the fee is refused with a
  bare program error the SDK cannot read, so it still arrives as `RAIN_501`; core runs no native
  SOL preflight of its own.
- `PrivyProvider.refreshSession()` no longer reports a live session as dead because the device was
  offline. Only a refresh Privy refuses, or one it drops the session on, throws `RAIN_201` and
  fires `onSessionExpired`. A refresh that got no answer from the network throws `RAIN_301`
  `NetworkError`; a session the vendor restored without a network answer is verified with it
  first (`Privy.onNetworkRestored()`) and throws `RAIN_301` only if that still fails, from the
  refresh and from the `create()` probe; any other failure with the session still in place throws
  what it was (`RAIN_501` at the floor), with the hook silent. A host that logs the user out on
  the hook no longer does so after a moment offline. On a wallet call through a wallet on Privy's
  server-wallet stack, a request that never left the device (the device offline, no route, no
  connection) now leaves as `RAIN_301` instead of `RAIN_501`, as the adapter's own RPC reads
  already did, and a read whose answer was lost is `RAIN_301` after its retries; an on-device
  wallet reports an offline device through its own prose and stays `RAIN_501`. A send whose
  answer was lost after the request left (a timeout, a reset, a 5xx or an answer the vendor could
  not read, or a resend the HTTP client made after a reset that then could not connect) is
  `RAIN_305` `TransactionOutcomeUnknown`, the code the entry under Added introduces: Privy signs
  and broadcasts inside that request, so the send may still land and `RAIN_301`'s "retry" would
  send twice; in 5.0.0-beta.1 it was `RAIN_501`, the code a refused send carries too. A signature
  whose answer was lost is `RAIN_301`: nothing was broadcast. A cancellation
  the vendor wrapped in its own failure now leaves a Privy call as the cancellation.
- The SDK clears a Rain wallet or Turnkey session revoked by a login on another device as soon as
  the wallet backend refuses to refresh it: inside any wallet call that refreshes (with
  `autoRefresh` on) and inside `refreshSession()`. `hasActiveSession()`, `currentAuthState()` and
  `currentSessionState()` read signed out when the failed call returns, and `sessionState` and
  `authState` emit it. Before, they kept reporting the stored session's local expiry, for up to
  the session's whole lifetime, and a login flow that skips the code on `hasActiveSession()`
  restored the dead session. A refresh that failed for another reason (offline, a 5xx) still
  leaves the session stored, and a refresh whose session a login replaced meanwhile proceeds with
  the new session. In bring-your-own mode the clear removes the session from the `TurnkeyContext`
  the host owns, and the vendor's expiry timer for it is cancelled with it. The sample app asks the
  backend before reusing a restored session.
- The Rain wallet's contact attach refuses a contact that already signs in to another account.
  Once the code is accepted, `confirmContactVerification` asks the wallet backend which account
  the contact signs in to and throws `RAIN_202` (`Unauthorized`) before anything changes when it
  is another one; a contact nobody signs in with, or this account's own, attaches as before. In
  `5.0.0-beta.1` the attach succeeded and replaced the signed-in account's contact with one that
  kept opening the other account. The attach still replaces an existing contact of the same kind
  instead of adding a second one; see Known issues under `5.0.0-beta.1`.
- `getTokenBalances` and `getAllBalances` through `rain-turnkey-android` and `rain-wallet-android`
  list every token in the SDK's built-in registry or passed to `registerTokens` that the wallet holds
  on Ethereum, Base, Polygon and their test networks: one chain read per call runs alongside the
  wallet backend's balance service call, or alone on an organization whose backend refuses that
  service (the entry above), and fills in the tokens the service left out. That service reports
  only the assets it catalogues, so a sandbox collateral token such as Rain USD on Base Sepolia
  never appeared. The chain read needs an RPC endpoint for the chain; without one, or when it
  fails or takes longer than five seconds, the list holds the service's rows, said once per chain in
  the log. The `getTokenBalances` section of [METHODS.md](docs/METHODS.md) has the details.
- Cancelling a coroutine that waits on an RPC read (balances, token metadata, allowances, receipts)
  cancels the HTTP call; before, the call ran to its answer or its 10-second timeout first. An RPC
  that answers with an `error` that is not an object fails as `RainError.InternalError` instead of
  a JSON exception.

### Changed

- **Breaking:** `sendLoginCode` and `sendContactVerificationCode` lowercase an email before it
  reaches Turnkey, which matches emails case-sensitively: `Jo@Example.com` and `jo@example.com`
  signed up as two accounts with two wallets. Migration: an account created from a mixed-case email
  under 5.0.0-beta.1 is keyed on that exact spelling, and after this change a login with that
  address is lowercased, misses it and signs up a new, empty account. Rain cannot change the stored
  spelling, because the user is the sub-organization's only root. Before upgrading, have such users
  sign in on the current build and export the recovery phrase or move their funds out. Sandbox test
  accounts can simply be recreated.
- On a send through `rain-turnkey-android` or `rain-wallet-android`, EVM or Solana, `RAIN_302`
  `TransactionPending` may carry the wallet backend's activity id when the backend accepted the send
  without a readable status id, and the activity-log history lists Solana sends under both of the
  backend's send activity types. See
  [TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md#after-a-send-is-accepted).
- On a Turnkey or Rain wallet organization without indexed history, the activity-log history that
  `getTransactions` falls back to leaves out sends the backend failed or rejected, on EVM and
  Solana, since nothing was broadcast. 5.0.0-beta.1 listed them as rows with the activity id as the
  hash.

## [5.0.0-beta.1] - 2026-09-30

First public beta, published to Maven Central under `xyz.rain`. Pin the exact version during the
beta; see [Compatibility](README.md#compatibility).

### Added

- `xyz.rain:rain-core-android`, `rain-portal-android`, `rain-privy-android`, `rain-turnkey-android`
  and `rain-wallet-android`, all at `5.0.0-beta.1`. `rain-wallet-android` embeds Rain's production
  wallet backend, so every account it creates is a production account, in a test build too.
- `@ExperimentalRainApi`. Implementing `WalletProvider`, `ProviderDescriptor` or `RainClient`
  requires opting in to it, because Rain may add members to these interfaces in any release.

### Known issues

Rain plans to fix each of these in a later beta.

- Sends and collateral withdrawals accept an EVM address written in mixed case whose EIP-55
  checksum doesn't match. `sendNative`, `sendToken`, `withdrawCollateral`, `prepareWithdrawal` and
  `estimateWithdrawalFee` check only that the address is 40 hex characters, and so do the
  wallet-agnostic `RainSdk.buildEIP712Message`, `RainSdk.buildWithdrawTransactionData` and
  `RainSdk.getLatestNonce`, so a mistyped character in a checksummed address goes through and the
  funds go to an address nobody may control. `sendToken` doesn't check its token contract at all.
  The Auth Pull operator and token contracts passed to `RainSdk.Builder.authPullConfig(...)` have
  the same gap. Until the fix, check mixed-case input in the app before calling, by comparing it
  with its EIP-55 form from any EIP-55 implementation. The fix is listed under Unreleased above.
- Solana sends through the Turnkey provider and the Rain wallet fail with `RAIN_501`
  (`ProviderError`), and nothing reaches the chain. The wallet backend rejects the request with
  HTTP 400. This affects `sendNative`, `sendToken` and `withdrawCollateral` on Solana chains. EVM
  sends, Solana reads, `prepareWithdrawal` and Privy's Solana sends are not affected. The fix is
  listed under Unreleased above.
- `PrivyProvider.refreshSession()` with the device offline throws `RAIN_201` and fires
  `onSessionExpired`, although the session is live and the next refresh online succeeds; on a
  session restored while offline it throws `RAIN_201` without firing the hook. A host that logs
  the user out on the hook logs them out after a moment offline. Until the fix, check connectivity
  before acting on the hook, or confirm the death with a second `refreshSession()` once the device
  is online. The fix is listed under Unreleased above.
- With Privy, a send the chain refuses for a shortfall of the native currency can arrive with the
  wrong code: an EVM send over the balance as `RAIN_502` (`InternalError`, "EVM error:
  OutOfFunds") and a Solana `sendNative` from a wallet holding no SOL as `RAIN_501`
  (`ProviderError`). Both mean `RAIN_402` (`InsufficientFunds`). A Solana `sendNative` from a
  wallet holding some SOL but less than the amount plus the fee also arrives as `RAIN_501`, and
  stays so after the fix. An app that retries on `RAIN_501` or `RAIN_502` resends a transfer that
  cannot succeed. The fix is listed under Unreleased above.
- The Rain wallet's contact attach, `sendContactVerificationCode` followed by
  `confirmContactVerification`, replaces the account's email address or phone number instead of
  adding a second one. The old contact then no longer logs in to the account. The attach also
  succeeds for an email address that already logs in to another account. That address keeps
  opening the other account, which leaves the signed-in account with no working email login. The
  fix for that second part, a refusal, is listed under Unreleased above; the replacement remains.
  Tell users that an attach replaces their current contact.
- A session revoked by a login on another device keeps reading as active on the first device:
  `hasActiveSession()`, `currentAuthState()` and `currentSessionState()` report the stored
  session's local expiry until it passes, up to the session's whole lifetime, while every wallet
  call fails with `RAIN_201` and `onSessionExpired` fires once. A login flow that skips the code on
  `hasActiveSession()` restores the dead session. Call `refreshSession()` and treat `RAIN_201` as
  signed out before skipping the code. The fix is listed under Unreleased above.
- `getTokenBalances` and `getAllBalances` can leave out a token the wallet holds. On Ethereum, Base,
  Polygon and their test networks, the Turnkey provider and the Rain wallet build the list from the
  wallet backend's balance service. On other EVM chains, and with Privy on every EVM chain, the
  list holds only the SDK's built-in tokens and those passed to `registerTokens`. Portal builds it
  from Portal's own service. On Solana, the Turnkey provider and the Rain wallet ask the wallet
  backend first and read the node when it fails or lists no tokens, and Privy reads the node, which
  lists every token the wallet holds. On Base Sepolia, for example, the Rain wallet leaves out
  Rain USD.
  `getBalance(chainId, Token.Contract(address))` reads a single token from the chain and works
  for any token. The Unreleased entry above adds the registry and registered tokens on Ethereum,
  Base, Polygon and their test networks for the Turnkey provider and the Rain wallet; any other
  token is listed only if the wallet backend reports it, as the `getTokenBalances` section of
  [METHODS.md](docs/METHODS.md) describes.
- On a Turnkey organization without the wallet backend's balance feature, `getBalance(chainId,
  Token.Native)` and `getTokenBalances` through `rain-turnkey-android` fail with `RAIN_202`
  (`Unauthorized`, "HTTP 403") on Ethereum, Base, Polygon and their test networks, and
  `getAllBalances` lists nothing for those chains; nothing falls back to the chain.
  `getBalance(chainId, Token.Contract(address))` reads the chain and works. Rain's own organization
  has the feature, so `rain-wallet-android` is not affected. The fix is listed under Unreleased
  above.
- At launch with a saved session, `RainProvider.authState` and `sessionState`, and
  `TurnkeyProvider.sessionState`, can emit `Unauthenticated` for a few milliseconds between
  `Loading` and the live session. An app that opens its login screen on that `Unauthenticated`
  shows it to a user who is still signed in.
