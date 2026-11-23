# Rain SDK for Android: method reference

Reference for the Rain SDK public API. The SDK is **modular**: `rain-core-android` carries the
vendor-free port, registry, and domain logic, and no wallet-vendor SDK. Each wallet provider ships
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

val client = rain.provider(ProviderId.PORTAL)   // suspend: RainClient for wallet operations
rain.buildEIP712Message(...)                     // wallet-agnostic: no provider required
```

A `RainClient` is bound to one provider for its lifetime. See [TURNKEY_SUPPORT.md](TURNKEY_SUPPORT.md) for the Turnkey adapter walkthrough.

---

## RainSdk

Entry point. Built via `RainSdk.builder()`; the host registers exactly the provider adapters it
ships and the chains it talks to.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `providerIds` | `Set<ProviderId>` | Ids of every provider the host registered. |
| `providers` | `List<ProviderDescriptor>` | The registered provider descriptors in registration order, for capability resolution. |
| `authPullChainIds` | `Set<Int>` | Chains Auth Pull is enabled on for this instance; see [authPullChainIds](#authpullchainids). |

### Methods

#### builder(): Builder

Starts a new `Builder`.

#### provider(id): RainClient

Resolves the `RainClient` backed by the provider registered under `id`, materializing the vendor
wallet on first access and caching it thereafter.

- **Returns:** `RainClient` bound to that provider.
- **Throws:** `RainError.ProviderNotRegistered` if no provider was registered for `id`;
  `RainError.SdkNotInitialized` after `close()`. A failure while the adapter materializes the
  wallet leaves as the `RainError` its README's Errors section maps it to, `ProviderError` for
  anything unmapped.
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

### Wallet-agnostic transaction building

These methods do **not** require a resolved provider: they can be used with any wallet or backend,
backed only by the configured RPC endpoints.

#### getLatestNonce(chainId, proxyAddress)

Reads the collateral's current admin nonce, the value `buildEIP712Message` binds when `nonce` is
omitted.

- **Returns:** `BigInteger`, the current nonce.
- **Throws:** `RainError.InvalidConfig` (`RAIN_102`) for a chain with no RPC endpoint, or a
  `proxyAddress` that is malformed or fails its EIP-55 mixed-case checksum.
  `RainError.InternalError` (`RAIN_502`) when the node answers with an RPC error or a value that
  does not decode. `RainError.NetworkError` (`RAIN_301`) for any other failure, a transport
  failure included.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID; the RPC endpoint is resolved from it. |
| `proxyAddress` | `String` | The collateral proxy contract address. |

#### isCollateralAdmin(chainId, proxyAddress, walletAddress)

Whether `walletAddress` is an admin of the collateral.

- **Returns:** `Boolean?`: the contract's answer, or `null` when the check could not run (RPC
  failure, or a collateral exposing no `isAdmin`). Treat `null` as unknown and proceed, never as
  "not authorized". A withdrawal proceeds on `null`; a non-zero Auth Pull approval is refused.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID; the RPC endpoint is resolved from it. |
| `proxyAddress` | `String` | The collateral proxy contract address. |
| `walletAddress` | `String` | The wallet to test for admin rights. A malformed address, or a chain with no endpoint, answers `null` like any other failed check. |

#### buildEIP712Message(chainId, walletAddress, addresses, amount, decimals, nonce)

Builds the EIP-712 message the wallet signs to authorize a withdrawal, along with the salt bound
into it.

- **Returns:** `RainEIP712Message`: `message` (typed-data JSON), `salt` (32 raw bytes), and
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

#### buildWithdrawTransactionData(addresses, amount, decimals, executorSignature, walletSalt, walletSignature)

ABI-encodes the `withdrawAsset` call for the collateral controller. Pure encoding, no RPC, so it
needs no chain ID.

Two distinct salt/signature pairs go in, and the contract names them differently from Rain's API.
`executorSignature` (Rain's authorization, fetched by the host from the Rain API) encodes into
`_executorPublisherSalt` / `_executorPublisherSignature`. The wallet's own pair encodes into
`_adminSalts` / `_adminSignatures`, since the wallet is an admin of the collateral.

- **Returns:** `String`: hex-encoded calldata (e.g. `"0x..."`).
- **Throws:** `RainError` if ABI encoding or validation fails.
- **Suspend:** No

| Parameter | Type | Description |
|-----------|------|-------------|
| `addresses` | `RainWithdrawAddresses` | Proxy, controller, token, recipient addresses. |
| `amount` | `BigDecimal` | Amount in human-readable token units. |
| `decimals` | `Int` | Token decimals. |
| `executorSignature` | `RainAdminSignature` | Rain's authorization (salt, signature, expiresAt). |
| `walletSalt` | `ByteArray` | `RainEIP712Message.salt`, unchanged (32 bytes). |
| `walletSignature` | `String` | The wallet's hex signature over the EIP-712 message (65 bytes). |

#### buildTransactionParameters(walletAddress, contractAddress, transactionData)

Composes a wallet-agnostic transaction parameter bag for a contract call. Pure composition, no
wallet provider and no RPC, returning a Rain-owned `RainTransactionParameters` struct with `value`
pre-set to `"0x0"`. Hosts can hand the result to any provider for signing / broadcast.

- **Returns:** `RainTransactionParameters`: `from`, `to`, `value` (`"0x0"`), `data`.
- **Suspend:** No

| Parameter | Type | Description |
|-----------|------|-------------|
| `walletAddress` | `String` | Sender wallet address. |
| `contractAddress` | `String` | Target contract address. |
| `transactionData` | `String` | Hex-encoded calldata. |

### Rain API (host responsibility)

The SDK does not call the Rain issuing API. Your backend fetches the user's collateral contract and
the admin withdrawal signature with the program Api-Key, and the SDK takes them as a
`RainWithdrawAddresses` and a `RainAdminSignature`.
[7. Rain API: collateral contracts and admin signature (host responsibility)](../README.md#7-rain-api-collateral-contracts-and-admin-signature-host-responsibility)
in README.md has the two calls, their parameters, the readiness rule and the mapping;
`app/src/main/java/com/rain/sdk/sample/RainApiClient.kt` is the reference client. Metadata for the
contract's tokens comes from `tokenMetadata` below.

### Token metadata

These methods need no wallet provider, only the configured RPC endpoints.

#### tokenMetadata(chainId: Int, address: String): TokenInfo?

Metadata (`symbol`, `name`, `decimals`) for a token known only by address, such as a collateral
contract's tokens. Resolution order: the built-in registry, host-registered tokens, then on-chain
`decimals()` / `symbol()` / `name()` reads over the chain's RPC endpoint, cached once decimals
resolve. Returns `null` when decimals could not be established (unknown token, failed read, RPC
unreachable), never a guessed default, so disable money actions for such a token rather than
assuming a scale. `symbol` and `name` inside a non-null result may still be null. Solana chains
resolve from the registry and host-registered tokens only. A `null` result is not cached, so a later
call reads the chain again.

- **Throws:** `RainError.InvalidConfig` (`RAIN_102`) when no RPC endpoint was configured for
  `chainId`, or when `address` is malformed for its chain family. Malformed means, on EVM chains,
  anything that is not `0x` plus 40 hex characters, or a mixed-case address whose EIP-55 checksum
  fails; on Solana chains, anything that does not base58-decode to 32 bytes. The same when the chain
  reports `decimals()` outside `0..77`, a token no money path can scale by; nothing is cached then.
  `RainError.SdkNotInitialized` (`RAIN_101`) after `close()`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Numeric chain ID the token lives on; it must have a configured RPC endpoint. |
| `address` | `String` | Token contract address (SPL mint on Solana). |

#### registerTokens(tokens: List\<TokenInfo\>)

Registers token metadata after `build()`, without a resolved provider. The entries are stored before
the call returns, so a `tokenMetadata` call that follows sees them; every resolved client shares the
store. Re-registering a host-added address replaces its entry. A built-in registry token cannot be
overridden: a registration naming one is ignored, with a warning when its metadata differs from the
built-in entry.

- **Throws:** `RainError.InvalidConfig` (`RAIN_102`) when an entry's address is malformed for its
  chain family (as above) or its `decimals` lies outside `0..77`. The whole list is validated first,
  so nothing is registered. `RainError.SdkNotInitialized` (`RAIN_101`) after `close()`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `tokens` | `List<TokenInfo>` | Tokens to add to the shared token store; an empty list is a no-op. |

---

## RainSdk.Builder

Assembles a `RainSdk`.

| Method | Description |
|--------|-------------|
| `rpcEndpoints(endpoints: Map<Int, String>)` | Sets the `chainId → RPC URL` map every provider shares. **Required.** |
| `rpcEndpoints(configs: List<NetworkConfig>)` | Same, as `NetworkConfig` values (chain id + RPC URL + optional display name). Replaces rather than appends; a later duplicate `chainId` wins. |
| `register(descriptor: ProviderDescriptor)` | Registers a provider adapter (e.g. `PortalProvider`, `TurnkeyProvider`, `RainProvider`). Re-registering the same id replaces the prior descriptor; `build()` closes the replaced instance after the token, Auth Pull and provider checks pass, so a build those checks refuse leaves it untouched. A build that fails on a bad RPC URL or chain id has already closed it. Registering the same instance twice is a no-op. |
| `registerTokens(tokens: List<TokenInfo>)` | Seeds the shared token store with extra token metadata. Validated at `build()` exactly as `RainSdk.registerTokens` validates: a malformed address or mint, or `decimals` outside `0..77`, fails the build with `RainError.InvalidConfig`. |
| `authPullConfig(config: RainAuthPullConfig)` | Enables Auth Pull for the exact token contracts in `config` (`RainAuthPullConfig.sandbox()` / `.production()` / `.custom(...)`). Without it, the approval, allowance, confirmation, and approval-fee methods fail closed. See [AUTH_PULL.md](AUTH_PULL.md). |
| `build(): RainSdk` | Validates endpoints (fail-fast on an RPC URL that is not `https`, or plain `http` on a host other than `localhost`, `127.0.0.1` or `::1`, or a chain id below 1) and returns the SDK. Throws `RainError.InvalidConfig` if no RPC endpoints were configured or a seed token is invalid (see `registerTokens`). The same for an invalid Auth Pull configuration: a malformed, wrong-checksum or zero token address, an empty token map, or none of its chains has an RPC endpoint. A chain without an endpoint is left out of `authPullChainIds`. A chain outside the set its factory names (`sandbox`, `production`, or either set for `custom`) is invalid too. Also throws `RainError.InvalidConfig` when both the Rain wallet provider and the Turnkey provider are registered: they drive one process-wide wallet backend, so an app uses one or the other. That check is best-effort; a second `RainSdk` or a provider that is never registered can still collide, which the backend reports as `RainError.InvalidConfig` on the first authentication call. |

Registering **zero** providers is allowed: the SDK is then wallet-agnostic, exposing
the transaction-building methods, `tokenMetadata` and `registerTokens`. Resolving `provider(id)` throws
`RainError.ProviderNotRegistered` until a provider is registered.

### Provider adapters

Each adapter is a `ProviderDescriptor` that owns its vendor SDK as a private dependency.

| Adapter | Module | Notes |
|---------|--------|-------|
| `PortalProvider(PortalConfig(sessionToken, chainId?, sessionPolicy?, onSessionTokenNeeded?, onSessionExpired?, autoApprove?), onPortalCreated?)` | `rain-portal-android` | Portal MPC signer (EVM). [rain-portal-android/README.md](../rain-portal-android/README.md) has the configuration, sessions, chains and errors, and [Portal construction](../rain-portal-android/README.md#portal-construction) the vendor construction, the `autoApprove` default and the `chainId` fallback. |
| `TurnkeyProvider(TurnkeyConfig(turnkey, walletAddress?, sessionPolicy?, onSessionExpired?, sponsorGas?, sponsorRent?))` | `rain-turnkey-android` | Turnkey P256 signer (EVM + Solana). [rain-turnkey-android/README.md](../rain-turnkey-android/README.md) has the capability set and its two adapter-only error rows, [Which chains can send](TURNKEY_SUPPORT.md#which-chains-can-send) the chains, *Rent* under [Solana notes](TURNKEY_SUPPORT.md#solana-notes) the `sponsorRent` flag and [Error handling](TURNKEY_SUPPORT.md#error-handling) the mapping. [Session expiry, refresh, and retry](TURNKEY_SUPPORT.md#session-expiry-refresh-and-retry) has the sessions and [Bring-your-own mode](TURNKEY_SUPPORT.md#bring-your-own-mode-the-public-turnkey-integration) the configuration; the managed mode, internal API, is under [Managed mode (internal API)](TURNKEY_SUPPORT.md#managed-mode-internal-api). |
| `RainProvider(application, RainWalletConfig(authTokenProvider, environment?, sessionPolicy?, onSessionExpired?, sponsorGas?, passkeyDomain?))` | `rain-wallet-android` | The Rain wallet: code and passkey login, one wallet with Ethereum and Solana accounts, sessions and key export, under Rain's names. [rain-wallet-android/README.md](../rain-wallet-android/README.md) has the contract; [Rain wallet provider](#rain-wallet-provider) below names its sections. |
| `PrivyProvider(PrivyConfig(privy, walletAddress?, sessionPolicy?, onSessionExpired?))` | `rain-privy-android` | Privy embedded-wallet signer (EVM + Solana). [rain-privy-android/README.md](../rain-privy-android/README.md) has the configuration, sessions, chains and errors. |

Each descriptor also exposes its session: `sessionState`, `currentSessionState()`, `suspend fun
refreshSession()` and `close()`. *Session expiry and retry* in its README, or the guide it points
at, has the model, what a failed refresh clears or throws, and the members it adds.

**Bring your own provider:** implement the `WalletProvider` port and a `ProviderDescriptor`, then
`register(...)` it; only `rain-core-android` is needed, and
[Bring your own provider](../rain-core-android/README.md#bring-your-own-provider) in
rain-core-android/README.md has a minimal adapter and the error contract.

#### Rain wallet provider

`RainProvider` in `rain-wallet-android` (package `com.rain.sdk.wallet`) is the Rain wallet: a `ProviderDescriptor` under which the SDK owns authentication, wallet provisioning, sessions and key export, with no wallet-vendor type on its surface. The [Members](../rain-wallet-android/README.md#members) table of rain-wallet-android/README.md has each member's signature and rules, [Configuration](../rain-wallet-android/README.md#configuration) has `RainWalletConfig`, and [Capabilities and chains](../rain-wallet-android/README.md#capabilities-and-chains) the capability set, the chain gate and the Solana rent rule.

#### Turnkey key export

Public API on `TurnkeyProvider`, in bring-your-own and managed mode alike: `suspend fun exportRecoveryPhrase(): String`, `suspend fun exportPrivateKey(family: TurnkeyKeyFamily): String` and `enum class TurnkeyKeyFamily { ETHEREUM, SOLANA }`. [Key export](TURNKEY_SUPPORT.md#key-export) in TURNKEY_SUPPORT.md has the formats, which wallet and account each value comes from, the session rule and your duties after the return; [Key export errors](TURNKEY_SUPPORT.md#key-export-errors) has the codes.

---

## RainClient

Operations Rain exposes against a single, already-resolved wallet provider, obtained from
`rain.provider(id)` / `rain.first { ... }`.

Money APIs are `BigDecimal`-first.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `providerId` | `ProviderId` | Identifier of the provider backing this client (e.g. `ProviderId.PORTAL`). |
| `capabilities` | `Set<Capability>` | Optional behaviours the backing provider supports (see [Capabilities](#capabilities)). |
| `isInitialized` | `Boolean` | Whether the SDK's chain configuration is set up. |
| `authPullChainIds` | `Set<Int>` | Chains this client will accept an Auth Pull approval on; see [authPullChainIds](#authpullchainids). |

---

### withdrawCollateral(chainId, addresses, amount, decimals, adminSignature, nonce)

Full withdrawal flow: builds the transaction, signs via the backing provider, submits, and
returns the transaction hash. Use [prepareWithdrawal](#preparewithdrawalchainid-addresses-amount-decimals-adminsignature-nonce)
to build without broadcasting.

On EVM chains this calls `withdrawAsset` on the collateral controller, `addresses.controllerAddress`. On Solana chains it
drives Rain's on-chain collateral program instead, reading the collateral account from the chain;
single-signer collateral accounts only, with the wallet as the account's owner. The Collateral
withdrawal bullet under [Solana notes](TURNKEY_SUPPORT.md#solana-notes) has the mechanism.

- **Returns:** `String`: the transaction hash (EVM) or transaction signature (Solana).
- **Throws:** `RainError` if construction, signing or submission fails. A chain the provider cannot
  broadcast on throws `RainError.ChainNotSupported` (`RAIN_104`) before anything is read or signed.
  A revert the provider decoded is `WithdrawalRevertedByNetwork`; the failed-status rows under
  [Error handling](TURNKEY_SUPPORT.md#error-handling) in TURNKEY_SUPPORT.md have the mapping. A
  fee-sponsored withdrawal skips the self-paid dry run. On Solana a recipient without a token
  account costs the owner rent, checked up front (`InsufficientFunds`) unless the provider's
  organization funds it (`WalletProvider.sponsorsRent`); *Rent* under
  [Solana notes](TURNKEY_SUPPORT.md#solana-notes) has the rule. On Turnkey, the Rain wallet
  and Privy, a lost or unreadable answer after the submit is `TransactionOutcomeUnknown`
  (`RAIN_305`), never a re-submit. The adapter README's Errors section has the rest.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID (e.g. `43114`, or `901` for Solana devnet). |
| `addresses` | `RainWithdrawAddresses` | All required addresses: proxy, controller, token, recipient. On Solana, `proxyAddress` is the collateral account, `tokenAddress` is the SPL mint, `controllerAddress` is unused (the coordinator is read from the collateral account), and `depositAddress` is required: a collateral program that does not derive it is refused with `RainError.InvalidConfig`. |
| `amount` | `BigDecimal` | Amount in human-readable token units (e.g. `BigDecimal("100.0")`). |
| `decimals` | `Int` | Token decimals (e.g. 6 for USDC, 18 for most tokens). Load-bearing on every chain including Solana: it scales the amount and is **not** checked against the SPL mint, so pass the mint's real decimals. |
| `adminSignature` | `RainAdminSignature` | Admin signature for authorization (salt, signature, expiresAt). |
| `nonce` | `BigInteger?` | Optional nonce; if `null`, SDK resolves from contract. Ignored on Solana: the nonce always comes from the on-chain collateral account. |

---

### prepareWithdrawal(chainId, addresses, amount, decimals, adminSignature, nonce)

Builds a collateral withdrawal without broadcasting it. Takes the same parameters as
`withdrawCollateral`.

This is **not** an offline build: it still prompts the wallet to sign EIP-712 (EVM) and reads the
collateral's admin set on chain. On Solana it additionally fetches a recent blockhash and simulates
the transaction.

Not gated on the provider's broadcast chains, so it works on a chain the provider cannot send on.
The EVM result is an unsigned envelope whose `data` carries the typed-data signature; a host signs
it with the wallet's key and submits it through its own RPC there. On Solana the fee check, the
rent check and the dry run always run, whatever the provider sponsors, because the prepared
transaction is the host's own self-paid submission.

- **Returns:** `RainPreparedWithdrawal`; its row and the `UnsignedSolanaTransfer` row under
  [Types](#types) have the fields.
- **Throws:** `RainError` if construction or signing fails; a raw provider failure on the Solana
  wallet-address read floors at `InternalError` (`RAIN_502`), as
  [Bring your own provider](../rain-core-android/README.md#bring-your-own-provider) in
  rain-core-android/README.md states.
- **Suspend:** Yes

> A Solana blockhash lives for roughly 150 slots, 60 to 90 seconds. The SDK fetches it at `finalized`
> commitment, so about 13 seconds have passed when the preparation returns. Submit promptly or prepare again.

Use `evmParameters` / `solanaTransfer` to read the payload without writing a `when`.

---

### getWalletAddress()

Returns the current wallet address from the backing provider.

- **Returns:** `String`: hex-encoded wallet address (e.g. `"0x..."`).
- **Throws:** `RainError` if address cannot be retrieved.
- **Suspend:** Yes

---

### getWalletAddress(chainId)

Returns the wallet address for a specific chain. For EVM chains this is the same hex address as
`getWalletAddress()`. A provider that also holds non-EVM accounts (advertising
`Capability.MULTI_CHAIN`) returns the address matching `chainId`'s family, e.g. a base58 Solana
address for a Solana chain id (`RainChain.SOLANA_DEVNET`). EVM-only providers ignore the family
distinction and return the hex address.

- **Parameters:** `chainId: Int`
- **Returns:** `String`: the wallet address for that chain's family.
- **Throws:** `RainError` if the address cannot be retrieved.
- **Suspend:** Yes

---

### estimateGas(chainId, from, to, data)

Estimates the gas fee required for a transaction.

On a provider that sponsors fees the quote is what the wallet would pay itself;
[Gas sponsorship](TURNKEY_SUPPORT.md#gas-sponsorship) in TURNKEY_SUPPORT.md has the rule.

- **Returns:** `BigDecimal`: estimated gas fee in the chain's native token (e.g. AVAX).
- **Throws:** `RainError` if estimation fails. A node revert is `TransactionSimulationFailed` (`RAIN_403`) on every adapter; anything else maps as the adapter README's Errors section says.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `from` | `String` | Sender wallet address. |
| `to` | `String` | Target contract address. |
| `data` | `String` | Hex-encoded transaction calldata (e.g. from `buildWithdrawTransactionData`). |

---

### estimateWithdrawalFee(chainId, addresses, amount, decimals, adminSignature, nonce)

Estimates the total fee required to execute a collateral withdrawal transaction.

Internally builds the EIP-712 payload, signs it with the wallet, then runs `eth_estimateGas`
against the withdrawal controller. Nothing is broadcast.

> **Signing side effect.** The estimated calldata embeds a wallet signature the controller
> verifies, since a placeholder would revert the estimate. Estimate-then-withdraw therefore signs
> twice. To quote without a second signature, call `prepareWithdrawal` once and pass the result to
> `estimateWithdrawalFee(chainId, prepared)` below.

- **Returns:** `BigDecimal`, the estimated withdrawal fee in the chain's native token.
- **Throws:** `RainError` if estimation fails. A node revert arrives as `WithdrawalRevertedByNetwork` (`RAIN_405`), the withdrawal flow's rewrap of a simulation failure; everything else maps as under `estimateGas`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `addresses` | `RainWithdrawAddresses` | All addresses required for the withdrawal (controller, proxy, token, recipient). |
| `amount` | `BigDecimal` | Human-readable amount to withdraw. |
| `decimals` | `Int` | Token decimals (e.g. 6 for USDC, 18 for most tokens). |
| `adminSignature` | `RainAdminSignature` | Rain's withdrawal authorization, fetched by the host from the Rain API. |
| `nonce` | `BigInteger?` | Optional; pin the estimate to the nonce the withdrawal will sign. |

EVM only: a Solana `chainId` throws `InternalError` (`RAIN_502`). Sponsorship does not change the
quote; [Gas sponsorship](TURNKEY_SUPPORT.md#gas-sponsorship) has the rule.

---

### estimateWithdrawalFee(chainId, prepared)

Estimates the fee of a withdrawal already built by `prepareWithdrawal`, running `eth_estimateGas` on
the prepared parameters. Builds and signs nothing, so the flow is: prepare once (one signature),
quote the fee on the preparation, then submit.

- **Returns:** as for [estimateWithdrawalFee(chainId, addresses, amount, decimals, adminSignature, nonce)](#estimatewithdrawalfeechainid-addresses-amount-decimals-adminsignature-nonce) above.
- **Throws:** `RainError` if estimation fails; `InternalError` (`RAIN_502`) for a Solana preparation or chain id (EVM only, like the building overload); a node revert arrives as `WithdrawalRevertedByNetwork` (`RAIN_405`), as for the building overload.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. EVM only. Not checked against `prepared`; pass the chain id `prepareWithdrawal` was called with. |
| `prepared` | `RainPreparedWithdrawal` | The withdrawal built by `prepareWithdrawal`. |

---

### sendNative(chainId, to, amount)

Sends native tokens (e.g. ETH, AVAX, SOL) from the current wallet.

A chain the provider cannot broadcast on throws `RAIN_104` before anything is read or signed; the
`RAIN_104` row under [Errors](#errors) has the rule,
[Which chains can send](TURNKEY_SUPPORT.md#which-chains-can-send) the chains and
[Gas sponsorship](TURNKEY_SUPPORT.md#gas-sponsorship) the Monad caveat.

- **Returns:** `RainTokenTransferResult`, carrying the transaction hash.
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

- **Returns:** `RainTokenTransferResult`, carrying the transaction hash.
- **Throws:** `RainError` if send fails.
- **On Solana chains** (Rain IDs 900 mainnet / 901 devnet, SDK-internal 902 testnet):
  `contractAddress` is the SPL mint, and the mint's own on-chain `decimals` are authoritative. The
  `decimals` parameter is not used to scale the amount. This applies to `sendToken` only; see
  `withdrawCollateral`, where `decimals` **is** load-bearing. The recipient's token account and its
  rent are under [Solana notes](TURNKEY_SUPPORT.md#solana-notes).
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `contractAddress` | `String` | ERC-20 token contract address, or the SPL mint on Solana. The EIP-55 rule of `to` under [sendNative](#sendnativechainid-to-amount) applies. |
| `to` | `String` | Recipient wallet address. The EIP-55 rule under [sendNative](#sendnativechainid-to-amount) applies. |
| `amount` | `BigDecimal` | Amount in human-readable form (e.g. `BigDecimal("100.0")` for 100 USDC). |
| `decimals` | `Int?` | Optional token decimals. When `null` (the default), the SDK resolves the token's `decimals()` from its registry or an on-chain read, so callers don't have to track it. If neither can establish it, the send throws `TokenNotFound` rather than scaling by a guessed value. |

---

### authPullChainIds

The chains this client will accept an Auth Pull approval on: the host's `RainAuthPullConfig`
narrowed to the chains that have an RPC endpoint, and the same set the approval guard enforces.
Empty until `RainSdk.Builder.authPullConfig(...)` supplies the trusted targets. Also available on
`RainSdk` itself, for gating before a client is resolved.

Gate host UI on this rather than on the static `RainAuthPullChains.SANDBOX` / `PRODUCTION` sets, which
answer for an environment, not for this SDK instance. See [Auth Pull](AUTH_PULL.md#supported-chains-and-assets).

- **Type:** `Set<Int>`
- **Suspend:** No

---

### approveTokenAllowance(chainId, contractAddress, spender, amount?)

Approves `spender` to move up to `amount` of an ERC-20 token from the current wallet, the
wallet-side prerequisite for Rain's [Auth Pull](AUTH_PULL.md). Rain executes the pull itself; the
SDK only sets the allowance.

Auth Pull is disabled until `RainSdk.Builder.authPullConfig(...)` supplies the trusted
per-chain token targets. The SDK rejects any different chain or token before wallet access.

- **Returns:** `RainTokenApprovalResult`, the transaction hash of the `approve` call.
- **Throws:** `RainError`. EVM only: a Solana `chainId` throws `RainError.InternalError`, since
  SPL delegation is not an ERC-20 allowance. A `chainId` outside `authPullChainIds` throws
  `RainError.InvalidConfig`; so does a token whose `decimals()` read answers outside `0..77`.
  When `amount` is not zero the SDK reads `isAdmin(wallet)` on `spender` before anything is signed.
  An answer of no is `RainError.WalletNotAuthorized` (`RAIN_407`), a `spender` that is not a
  collateral contract is `RAIN_102`, and a node error on the read that is not a revert, or an
  answer that does not decode, is `RainError.InternalError` (`RAIN_502`).
  `RainError.NetworkError` (`RAIN_301`) when that read gets no answer, on a network failure on a
  self-paid send's nonce and gas reads, on a provider's pre-flight simulation, or, with Privy, on
  an approval whose request never left the device. Retryable: nothing was signed or broadcast. On
  Turnkey and the Rain wallet the
  `statusId` of a `RainError.TransactionPending` (`RAIN_302`) can be the wallet backend's send
  status id, or the approval's activity id when the backend had not finished it. Neither is a
  transaction hash, so do not build an explorer link from it. A wallet without native gas on a
  self-paid provider is refused as that adapter maps it: `RainError.InsufficientFunds` (`RAIN_402`)
  from Privy's pre-flight, `RainError.TransactionSimulationFailed` (`RAIN_403`) from Portal's. A
  self-paid Turnkey or Rain wallet send quotes gas over the chain's RPC first, and a node refusal
  there is `RainError.InternalError` (`RAIN_502`) unless it is a revert (`RAIN_403`, as under
  `estimateGas`) or a funds shortfall (`RAIN_402`); a sponsored Turnkey or Rain wallet approval
  needs no native gas.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target EVM network chain ID. Must be an Auth Pull chain for the configured `RainAuthPullConfig`. |
| `contractAddress` | `String` | ERC-20 token contract (USDC for Auth Pull). |
| `spender` | `String` | Address being approved: the user's collateral contract on `chainId`, the `proxyAddress` from the Rain API. With an `amount` of `BigDecimal.ZERO`, any well-formed, non-zero address is accepted. |
| `amount` | `BigDecimal?` | Human-readable allowance (e.g. `BigDecimal("250")`). `null` (the default) approves an unlimited (`uint256` max) allowance; `BigDecimal.ZERO` revokes. |

There is deliberately **no `decimals` parameter** on any Auth Pull method: the scale comes from
registry metadata or an on-chain `decimals()` read, never from the caller;
[Amounts and decimals](AUTH_PULL.md#amounts-and-decimals) in AUTH_PULL.md has the rules.

```kotlin
// Unlimited: what Rain recommends, so the user never has to re-approve.
val result = client.approveTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdc,
    spender = proxyAddress
)

// Capped, then revoked.
client.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, proxyAddress, BigDecimal("250"))
client.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, proxyAddress, BigDecimal.ZERO)
```

The new value is written straight over the old one; some ERC-20s (USDT and its clones) revert unless
an existing non-zero allowance is set to zero first, as [3. Approve](AUTH_PULL.md#3-approve) in
AUTH_PULL.md explains.

---

### getTokenAllowance(chainId, contractAddress, spender, owner?)

Reads the ERC-20 allowance `spender` currently holds over `owner`'s balance. Call it before
approving (to skip a redundant transaction). To confirm an approval was mined, use
`confirmTokenAllowance`: this read is unpinned and can still return the pre-approval value.

- **Returns:** `RainTokenAllowance`; see [RainTokenAllowance value type](#raintokenallowance-value-type).
- **Throws:** `RainError`. EVM only (a Solana `chainId` → `InternalError`), and gated to the
  configured `RainAuthPullConfig`'s chains like the approval itself. A malformed `owner`, or a
  `spender` that is malformed, zero or fails its EIP-55 checksum →
  `InvalidConfig` (`RAIN_102`); a token whose decimals cannot be established → `TokenNotFound`
  (`RAIN_102`). An `eth_call` that gets no answer, or an answer that is not JSON → `NetworkError`
  (`RAIN_301`), retryable; a node error on it, or a result that does not decode → `InternalError`
  (`RAIN_502`).
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target EVM network chain ID. |
| `contractAddress` | `String` | ERC-20 token contract. |
| `spender` | `String` | Address whose allowance is being read; any well-formed, non-zero address is accepted. |
| `owner` | `String?` | Wallet whose balance is approved. `null` (the default) reads this client's own wallet, which is the only case that touches the wallet provider at all. |

---

### estimateApprovalFee(chainId, contractAddress, spender, amount?)

Estimates the total fee (estimated gas x gas price) to submit the approval, in the chain's native
token. Nothing is broadcast and no signature is requested; the fee is priced against the exact
calldata `approveTokenAllowance` would send. The sponsored-chain rule under
[Gas sponsorship](TURNKEY_SUPPORT.md#gas-sponsorship) applies.

- **Returns:** `BigDecimal`: fee in the chain's native currency (e.g. ETH).
- **Throws:** `RainError`: the pre-broadcast cases of `approveTokenAllowance` (the Auth Pull guard, the
  `isAdmin` read on `spender`, a Solana `chainId`, no wallet address, an unscalable amount), then the
  provider's estimate failure.
- **Suspend:** Yes

Parameters are identical to `approveTokenAllowance`.

---

### confirmTokenAllowance(transactionHash, chainId, contractAddress, spender, amount?, owner?)

Waits for an approval transaction to mine successfully, then reads back the resulting allowance. A
transaction hash alone means submitted, not ready: use this before treating the user as approved for
Auth Pull.

Polls `eth_getTransactionReceipt` once a second for up to 60 seconds, then reads the allowance
through the same path as `getTokenAllowance`, pinned to the block the transaction mined in. A
transient receipt or allowance read failure is retried inside the window.

- **Returns:** `RainTokenAllowance`: the allowance actually in place after the transaction mined.
- **Throws:** `RainError`. A malformed `transactionHash`, or a `spender` that is malformed, zero or
  fails its EIP-55 checksum → `InvalidConfig` (`RAIN_102`) before the
  first poll. Reverted receipt → `TransactionSimulationFailed`. No receipt within the window →
  `TransactionPending`, `statusId` the transaction hash, and [4. Confirm](AUTH_PULL.md#4-confirm) in
  AUTH_PULL.md has the read-back rule. Mined, but the allowance could not be read at that block
  within the window → the read's own error, `NetworkError` (`RAIN_301`) for a lost or non-JSON
  answer or `InternalError` (`RAIN_502`) for a node error or a result that does not decode. The approval is mined, so re-read with
  `getTokenAllowance` rather than re-approve. Mined allowance not equal to the request, or a Solana
  `chainId` → `InternalError`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `transactionHash` | `String` | Hash returned by `approveTokenAllowance`. |
| `chainId` | `Int` | Target EVM network chain ID. Must match the approval's chain. |
| `contractAddress` | `String` | ERC-20 token contract the approval was against. |
| `spender` | `String` | Address that was approved; any well-formed, non-zero address is accepted. |
| `amount` | `BigDecimal?` | The allowance that was requested, so the result can be checked against it. `null` (the default) means the unlimited approval; `BigDecimal.ZERO` means a revoke. |
| `owner` | `String?` | Wallet whose allowance to read. `null` (the default) reads this client's own wallet. |

**The returned allowance must equal `amount`**, since the read is pinned to the approval's block;
anything else throws `InternalError` (`RAIN_502`). One edge: an Auth Pull `transferFrom` mined in
the same block reads back lower and surfaces as `RAIN_502` too, so re-read with `getTokenAllowance`
before treating that as a failed approval. Later pulls decrement the live allowance; use
`getTokenAllowance` (and `covers`) for the ongoing check.

```kotlin
val result = client.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, proxyAddress)
val allowance = client.confirmTokenAllowance(
    transactionHash = result.transactionHash,
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdc,
    spender = proxyAddress
)
```

---

### RainTokenAllowance value type

| Member | Type | Description |
|--------|------|-------------|
| `rawAmount` | `BigInteger` | Exact allowance in the token's smallest unit. Never lossy; compare against this. |
| `decimals` | `Int` | The token's decimals (e.g. 6 for USDC). |
| `chainId` / `tokenAddress` / `owner` / `spender` | | What was read, so a merged list stays self-describing. |
| `isUnlimited` | `Boolean` | `rawAmount == uint256` max. Exact: some tokens decrement even a max allowance, so `false` does not mean "must re-approve"; compare `rawAmount` against what you need. |
| `isZero` | `Boolean` | Nothing approved, the state after a revoke. |
| `decimalAmount` | `BigDecimal` | Derived: `rawAmount / 10^decimals`. For an unlimited approval this is ~1.16e71 on a 6-decimal token such as USDC; gate on `isUnlimited` before rendering. |
| `formatted` | `String` | Derived display string with trailing zeros trimmed. |
| `covers(amount)` | `(BigDecimal) -> Boolean` | Whether a human-readable amount is still covered, compared in exact base units. A negative amount, or one finer than the token's scale, cannot be represented and answers `false` as well: read `false` as "do not rely on it", never as "approve more". |
| `UNLIMITED_RAW_AMOUNT` | `BigInteger` | Companion constant: `uint256` max. |

---

### Balance value type

All balance methods return rich `Balance` values rather than lossy `Double`s.

| Field | Type | Description |
|-------|------|-------------|
| `token` | `Token` | `Token.Native` or `Token.Contract(address)`. |
| `chainId` | `Int` | Chain ID the balance was read on: EIP-155 for an EVM chain, Rain's Solana sentinel (900 to 902) for a Solana cluster. |
| `rawAmount` | `BigInteger` | Exact balance in the token's smallest unit (never lossy). |
| `decimals` | `Int` | Token decimal places (e.g. 6 for USDC, 18 for ETH). |
| `symbol` | `String?` | Token symbol, when known. |
| `name` | `String?` | Human-readable name, when known. |
| `decimalAmount` | `BigDecimal` | Derived: `rawAmount / 10^decimals`. |
| `formatted` | `String` | Derived display string (e.g. `"1.5"`). |

---

### getBalance(chainId, token)

Fetches a single balance (native or a contract token) for the current wallet.

- **Returns:** `Balance`: exact `rawAmount` plus resolved decimals / symbol / name. A contract token outside the registry whose `decimals()` read fails carries the 18-decimal default. That holds for every adapter's `getBalance` on an EVM chain, for the `getTokenBalances` rows Portal's service adds, and for the wallet backend's rows that carry no decimals. `rawAmount` stays exact; `decimalAmount` and `formatted` can then
  be off by a power of ten, so resolve the token with `tokenMetadata` first when the scale matters.
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
| Turnkey provider, Rain wallet | On Ethereum, Base, Polygon and their test networks: every token the wallet backend's balance service reports, plus every token in the SDK's built-in registry or passed to `registerTokens` that the service left out. Each call on these chains also reads the registry and registered tokens from the chain's RPC endpoint alongside the service call; the service's row wins where both answer. That read is one Multicall3 `eth_call` batch on Ethereum, Base, Polygon and Base Sepolia, and parallel `eth_call`s on Sepolia and Polygon Amoy; a chain with no registry or registered tokens is not read. A chain with no endpoint, or a read that fails or takes longer than five seconds, leaves the service's rows and is logged once per chain. On an organization whose wallet backend has no balance service (HTTP 403): the registry and registered tokens, read from the chain, with no service call after the first refusal. The paragraph under [What Rain uses Turnkey for](TURNKEY_SUPPORT.md#what-rain-uses-turnkey-for) in TURNKEY_SUPPORT.md has the refusal rule. On other EVM chains: the registry and registered tokens, read from the chain. | The wallet backend's list where it indexes the cluster; otherwise every token account the node reports. See [Solana notes](TURNKEY_SUPPORT.md#solana-notes). |
| Privy | The registry and registered tokens, read from the chain. | Every token account the node reports. |
| Portal | The tokens Portal's own service reports. | The adapter uses no Solana account; a Solana chain id is not supported. |

A token outside the provider's list is not in the result; [getBalance](#getbalancechainid-token)
reads any single token by address.

- **Returns:** `List<Balance>`: the native balance plus one per listed contract token with a non-zero balance.
- **Throws:** `RainError` if the request fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |

---

### getAllBalances()

Fetches balances across every chain the SDK was initialized with, in parallel, flattened
into a single list. Each `Balance` carries its own `chainId`. Per-chain failures are
tolerated: a chain that errors out contributes no entries rather than failing the whole
call. The exception is a dead session, `RainError.TokenExpired` (`RAIN_201`), which fails the call:
it affects every chain, and an empty list would read as zero balances. It is built from the same
per-chain read as [getTokenBalances](#gettokenbalanceschainid), so that section's table applies
here too.

- **Returns:** `List<Balance>`: a flat list spanning all healthy configured chains.
- **Throws:** `RainError.TokenExpired` (`RAIN_201`) when any chain's read fails on a dead session;
  every other per-chain failure is logged and dropped.
- **Suspend:** Yes

---

### registerTokens(tokens)

The same operation, checks and store as [registerTokens](#registertokenstokens-listtokeninfo) on
`RainSdk`, for a host that holds a resolved client. The store is shared by every client the
`RainSdk` resolves, so the entries survive `reset()`.

- **Throws:** as `RainSdk.registerTokens`.
- **Suspend:** Yes

---

### generateAddressQRCode(address, dimension)

Generates a square QR code `Bitmap` encoding `address`, or the wallet's own address when `address`
is `null`. Use it for any address the host needs to show: a chain-specific wallet address (the
Solana account rather than the EVM one), or a Rain collateral deposit address.

- **Returns:** `Bitmap`: QR code image.
- **Throws:** `RainError` if wallet is unavailable or QR generation fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `address` | `String?` | Address to encode. If `null`, uses the current wallet address. |
| `dimension` | `Int` | Output width and height in pixels (the QR is square). Defaults to `256`. |

---

### getTransactions(chainId, limit, offset, order)

Fetches transaction history for the current wallet on the given network.

- **Returns:** `List<RainTransaction>`: the transaction records. `value` is a `BigDecimal?` in human-readable units; null when decimals could not be resolved, with `rawValue` still populated.
- **Source:** Turnkey provider and Rain wallet: the wallet backend's indexed history when the organization has the feature, otherwise the activity log, sends only. The `getTransactions` row under [What Rain uses Turnkey for](TURNKEY_SUPPORT.md#what-rain-uses-turnkey-for) and the History bullet under [Solana notes](TURNKEY_SUPPORT.md#solana-notes) have the rules. Portal: Portal's own transactions API, as [Capabilities and chains](../rain-portal-android/README.md#capabilities-and-chains) in rain-portal-android/README.md describes. Privy: Privy's indexer, for the registry and registered tokens on the chains under [Capabilities and chains](../rain-privy-android/README.md#capabilities-and-chains) in rain-privy-android/README.md.
- **Throws:** `RainError` if transaction history cannot be retrieved.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `limit` | `Int?` | Optional max number of transactions to return. |
| `offset` | `Int?` | Optional pagination offset. |
| `order` | `RainTransactionOrder?` | Optional sort order: `ASC` or `DESC`. |

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
| `GAS_SPONSORSHIP` | The provider's sends are fee-sponsored. [Gas sponsorship](TURNKEY_SUPPORT.md#gas-sponsorship) in TURNKEY_SUPPORT.md has the rules: what core skips and what the fee estimates quote. |

Bundled providers: **Portal** → `EXPORT`, `RECOVERY`. **Turnkey** → `EXPORT`, `MULTI_CHAIN`, plus
`GAS_SPONSORSHIP` while `sponsorGas` is on (the default). **Rain wallet** →
the same set as Turnkey, forwarded from the adapter it wraps. **Privy** → `EXPORT`, `RECOVERY`,
`MULTI_CHAIN`.

---

## Types

Enums and sealed types may gain new cases in a minor release, unless their docs say a `when` over
them is exhaustive; give any other `when` over them an `else` branch. The sealed
`RainWalletSessionState`, `RainWalletAuthState` and `RainWalletLoginOutcome` are exhaustive, and
adding a state or case is a major-version change; `RainWalletEnvironment` may gain a deployment.

| Type | Description |
|------|-------------|
| **`ProviderId`** | Value class wrapping a provider id string. Well-known constants: `PORTAL`, `TURNKEY`, `PRIVY`, `RAIN`. Host apps can ship a custom id. |
| **`Capability`** | Enum: `EXPORT`, `RECOVERY`, `MULTI_CHAIN`, `BIOMETRIC_GATE`, `GAS_SPONSORSHIP`. |
| **`TurnkeyKeyFamily`** | Enum: `ETHEREUM`, `SOLANA`, and possibly more as Turnkey adds curves. Which of a Turnkey wallet's keys `TurnkeyProvider.exportPrivateKey` returns; see [Turnkey key export](#turnkey-key-export). |
| **`RainEIP712Message`** | `message`, `salt`, `saltHex`. Returned by `buildEIP712Message`. |
| **`ProviderDescriptor`** | Registrable provider descriptor: `id`, `capabilities`, a suspend `create(context)` that materializes the `WalletProvider`, and `close()`, a no-op by default, which `RainSdk.close()` calls. Implemented by `PortalProvider`, `TurnkeyProvider`, `PrivyProvider`, `RainProvider`, and host-supplied providers. |
| **`RainWallet*` types** | `RainWalletConfig`, `RainWalletEnvironment`, `RainWalletSessionPolicy`, `RainWalletSessionState`, `RainWalletAuthState`, `RainWalletContact`, `RainWalletLoginOutcome` with `RainWalletMaskedContact` and `RainWalletContactType`, and `RainWalletKeyAccount`: the Rain wallet's types. rain-wallet-android/README.md owns them under [Configuration](../rain-wallet-android/README.md#configuration), [Session expiry and retry](../rain-wallet-android/README.md#session-expiry-and-retry), [Members](../rain-wallet-android/README.md#members), [Two-code login](../rain-wallet-android/README.md#two-code-login) and [Passkeys and login contacts](../rain-wallet-android/README.md#passkeys-and-login-contacts); the key export formats are under [Key export](TURNKEY_SUPPORT.md#key-export) in TURNKEY_SUPPORT.md. |
| **`ExperimentalRainApi`** | Opt-in marker (`@RequiresOptIn`, error level). On `WalletProvider`, `ProviderDescriptor` and `RainClient` it guards implementing them: Rain may add members to these interfaces in any release. On any other declaration it means the declaration may change in any release, with a CHANGELOG entry. Calling the SDK needs no opt-in. |
| **`WalletProvider`** | The port each adapter implements, in `com.rain.sdk.provider`. Public so hosts can ship their own wallet stack; implementing it needs `@OptIn(ExperimentalRainApi::class)`, because Rain may add members in any release. |
| **`TokenInfo`** | A token the SDK reads balances for and scales amounts by. Fields: `chainId` (numeric; EIP-155 for EVM chains, 900 to 902 for the Solana clusters), `address` (the ERC-20 contract, or the SPL mint on Solana), `symbol: String?`, `decimals: Int`, `name: String?`. Returned by `tokenMetadata`, accepted by the three `registerTokens` methods. |
| **`RainWithdrawAddresses`** | `proxyAddress`, `controllerAddress`, `tokenAddress`, `recipientAddress`, `depositAddress` (Solana only). `validated()` returns them in EIP-55 checksum form and refuses a malformed address or a mixed-case one whose checksum doesn't match; see [Errors](#errors). |
| **`RainAdminSignature`** | Rain's authorization for one withdrawal, passed through unchanged. `salt` is base64, 32 bytes on every chain; `signature` is 0x-hex, 65 bytes on EVM, or base64, 64 bytes on Solana; `expiresAt` is unix seconds, or an ISO-8601 instant with Z or a numeric offset. |
| **`RainPreparedWithdrawal`** | Sealed: `Evm(parameters: RainTransactionParameters)` or `Solana(transfer: UnsignedSolanaTransfer)`. Has `evmParameters` / `solanaTransfer` accessors. |
| **`UnsignedSolanaTransfer`** | The Solana half of a prepared withdrawal: `transaction` (the serialized unsigned transaction, a defensive copy), `transactionHex` (the same bytes as hex without `0x`) and `recentBlockhash` (base58; see the blockhash note under `prepareWithdrawal`). `createsRecipientAccount` says the transfer creates the recipient's token account; the sender pays that rent here whatever the provider sponsors, since the host submits the prepared transaction. |
| **`RainTokenTransferResult`** | `transactionHash` (String). Returned by `sendNative` and `sendToken`. |
| **`RainTokenApprovalResult`** | `transactionHash` (String): hash of the ERC-20 `approve` call. Returned by `approveTokenAllowance`. |
| **`RainTokenAllowance`** | Exact allowance value type; see [RainTokenAllowance value type](#raintokenallowance-value-type). |
| **`RainAuthPullConfig`** | Trusted Auth Pull targets: a `chainId → token contract` map. `sandbox()` and `production()` bind the canonical USDC contracts and chains; `custom(...)` names them explicitly and may draw on either environment's chains. Passed to `RainSdk.Builder.authPullConfig(...)`. |
| **`RainAuthPullChains`** | The Auth Pull chain sets by environment: `SANDBOX` (Base Sepolia, Arbitrum Sepolia), `PRODUCTION` (Base, Arbitrum); gate UI on [authPullChainIds](#authpullchainids). |
| **`NetworkConfig`** | `chainId`, `rpcUrl`, `networkName?`; `eip155ChainId` renders the CAIP-2 id, `eip155:<chainId>` for an EVM chain or `solana:<genesis>` for a Solana sentinel; `NetworkConfig.fromEip155(...)` parses the `eip155:` form. Accepted by `Builder.rpcEndpoints(List<NetworkConfig>)`. |
| **`RainTransactionParameters`** | `from`, `to`, `value` (hex wei), `data` (hex calldata). Wallet-agnostic transaction parameter bag returned by `RainSdk.buildTransactionParameters`. |
| **`RainTransaction`** | Transaction record: `hash`, `uniqueId`, `blockNumber`, `timestamp`, `from`, `to`, `value`, `asset`, `tokenAddress`, `rawValue`, `decimals`, `category`, `chainId`, `metadata`. |
| **`RainTransactionCategory`** | Extensible constant: `External`, `Token`, `Erc20`, `Erc721`, `Erc1155`, `ContractInternal`. |
| **`EthereumConverter`** | Public helpers in `com.rain.sdk.utils` for wei and hex values: exact `BigDecimal` scaling (`convertWeiHexToDecimal`, `convertWeiToEthDecimal`, `convertHexToDecimal`, `convertEthToWeiHex`), strict hex parsers that throw `InternalError` on malformed input, and lenient readers that return zero or `null`. Each function's KDoc has its rule. |
| **`RainTransactionOrder`** | Enum: `ASC`, `DESC`. The `order` parameter of `getTransactions`. |
| **`RainChain`** | Constants: `AVALANCHE_MAINNET` (43114), `AVALANCHE_TESTNET` (43113), `BASE_MAINNET` (8453), `BASE_SEPOLIA` (84532), `ARBITRUM_MAINNET` (42161), `ARBITRUM_SEPOLIA` (421614), plus the Solana sentinels. |

---

## Errors

All methods can throw `com.rain.sdk.error.RainError` (sealed class). Each error carries a stable `code` string (`RAIN_xxx`) to branch on and a typed `errorCode`; the code table is a cross-platform contract, pinned by `RainErrorCodeParityTest`. Rain's adapters map a vendor failure to a `RainError` before it leaves the adapter; the module READMEs have each mapping.

Format: `"RainSDK Error [CODE]: message"`

| Code | Class | Meaning |
|------|-------|---------|
| `RAIN_101` | `RainError.SdkNotInitialized` | A `RainSdk` used after `close()`, `PortalProvider.refreshSession()` / `updateSessionToken()` called before the provider was resolved, or any Portal call after the provider's `close()`. |
| `RAIN_102` | `RainError.InvalidConfig` / `RainError.ProviderNotRegistered` | Init and config. An invalid RPC URL, chain ID, or address format. A token registration with a malformed address or mint, or `decimals` outside `0..77`; a `tokenMetadata` lookup with a malformed address or a chain the SDK has no RPC endpoint for. The Rain wallet provider and the Turnkey provider registered on one `RainSdk`; the `build()` row has the rule. No provider registered for the requested id, or none matching a capability (`ProviderNotRegistered`). The Rain wallet's `passkeyDomain` rule at construction is under [Configuration](../rain-wallet-android/README.md#configuration) in rain-wallet-android/README.md. |
| `RAIN_102` | `RainError.InvalidConfig` / `RainError.InvalidRecipient` / `RainError.TokenNotFound` | Sends and withdrawals. An EVM address written in mixed case whose EIP-55 checksum doesn't match, refused before anything is signed: a send's recipient as `InvalidRecipient`; a send's token contract or a withdrawal address as `InvalidConfig`. A token whose decimals could not be established when a money path needed them (`TokenNotFound`). A recipient that cannot receive the transfer (`InvalidRecipient`). A malformed withdrawal salt, signature or expiry handed to a withdrawal method. |
| `RAIN_102` | `RainError.InvalidConfig` | Login, attach and export: a closed provider, a missing or malformed precondition (contact, `passkeyDomain`, requested code, wallet auth token), or a passkey request the device refused. A passkey sign-up is refused while a session is live, or when Rain answers that the passkey already has a wallet (sign in with `loginWithPasskey`). The conditions per member are under [Members](../rain-wallet-android/README.md#members), [Configuration](../rain-wallet-android/README.md#configuration) and [Errors](../rain-wallet-android/README.md#errors) in rain-wallet-android/README.md; the key export cases, Turnkey and Rain wallet alike, are under [Key export errors](TURNKEY_SUPPORT.md#key-export-errors). |
| `RAIN_102` | `RainError.InvalidConfig` | Auth Pull. An Auth Pull token contract written in mixed case whose EIP-55 checksum doesn't match fails `build()`. Auth Pull not configured, a chain or token other than the configured `RainAuthPullConfig`'s, or a `spender` that is malformed, zero, fails its checksum or is not a collateral contract; the [approveTokenAllowance](#approvetokenallowancechainid-contractaddress-spender-amount) section has the rules. |
| `RAIN_103` | `RainError.InvalidRpcUrl` | RPC URL could not be parsed as a valid URL. |
| `RAIN_104` | `RainError.ChainNotSupported` | The active wallet provider cannot broadcast transactions on this chain; carries `chainId`. Thrown before anything is signed or broadcast on every send, withdrawals and approvals included; the one read that can precede it is a `sendToken`'s on-chain `decimals()` lookup for a token outside the registry. [Which chains can send](TURNKEY_SUPPORT.md#which-chains-can-send) in TURNKEY_SUPPORT.md has the chains and what is never gated. |
| `RAIN_201` | `RainError.TokenExpired` | Provider session token expired or invalid. Which Rain wallet members need a live session is under [Errors](../rain-wallet-android/README.md#errors) and the member rows of rain-wallet-android/README.md. |
| `RAIN_202` | `RainError.Unauthorized` | Turnkey and the Rain wallet: a request refused with HTTP 403, such as a feature the organization lacks. Portal: an empty session token at resolution. A 403 from Privy's API is `RAIN_201`, under [Errors](../rain-privy-android/README.md#errors) in rain-privy-android/README.md. The Rain wallet's conditions are under [Errors](../rain-wallet-android/README.md#errors) in rain-wallet-android/README.md. |
| `RAIN_203` | `RainError.InvalidLoginCode` | The one-time login code was refused (wrong, expired or locked), the Rain wallet's and Turnkey's managed login only. Which call gets the user past it, and which calls keep or drop the challenge, is under [Errors](../rain-wallet-android/README.md#errors) and the member rows of rain-wallet-android/README.md. |
| `RAIN_301` | `RainError.NetworkError` | Network/connectivity failure: a request over the chain's RPC, Rain's endpoints or Privy's API that got no answer, a Turnkey or Rain wallet session refresh that got no answer, a Portal read or fee estimate that got no answer or a 408, 429 or 5xx, or your backend behind `authTokenProvider` that could not be reached (an `IOException` in its cause chain). A Turnkey API read that got no answer is `RAIN_501` after its retries; [Error handling](TURNKEY_SUPPORT.md#error-handling) in TURNKEY_SUPPORT.md has the row. When that failure is on the one token renewal, the leg had already been sent once and refused with the old token. What the Rain wallet keeps for a retry when a call never answered is under [Errors](../rain-wallet-android/README.md#errors) and the member rows of rain-wallet-android/README.md. |
| `RAIN_302` | `RainError.TransactionPending` | Submitted, not yet confirmed. `statusId` is what to resume from (status id, UserOperation hash, or transaction hash). On a Turnkey or Rain wallet send accepted without a readable status id it is the backend's activity id, which no call takes; [After a send is accepted](TURNKEY_SUPPORT.md#after-a-send-is-accepted) has the rule. Do not resend. |
| `RAIN_305` | `RainError.TransactionOutcomeUnknown` | The send was handed to the wallet provider and the SDK then lost track of it: a server error, a transport failure or an unreadable answer after the provider may already have accepted it. There is no identifier to resume from, and it is not a settled failure: the transaction may still land, and resending risks a duplicate transfer. Read history or the balance before sending again. Raised by the Turnkey provider and the Rain wallet, EVM and Solana, in the cases under [After a send is accepted](TURNKEY_SUPPORT.md#after-a-send-is-accepted), and by the Privy provider in the cases under [Errors](../rain-privy-android/README.md#errors) in rain-privy-android/README.md. On every provider the error carries no cause, and its message names the failure kind. `RAIN_501` from a Turnkey or Rain wallet send means the send settled as failed or was refused, and nothing moved; what it means from a Privy send is under the same Errors section. |
| `RAIN_401` | `RainError.UserRejected` | User cancelled the signing request in the wallet, or ended a passkey sheet without a passkey (dismissed it, or the device holds none for the domain). Which Rain wallet ceremonies and fallback sheets raise it is under [Errors](../rain-wallet-android/README.md#errors) and the `signUpWithPasskey` row of rain-wallet-android/README.md. |
| `RAIN_402` | `RainError.InsufficientFunds` / `RainError.InsufficientTokenBalance` / `RainError.TokenAccountNotFound` | Balance too low for the requested amount or gas. `InsufficientTokenBalance` is a token balance below the requested amount, with `requested` and `available` as `BigDecimal` in the token's units; `TokenAccountNotFound` is a Solana sender with no token account for the mint. `InsufficientFunds` carries `required` and `available` as `BigDecimal?` in the native currency's human units, with `currency` naming the unit (`SOL`). The Solana preflight fills them (*Fees and rent* under [Solana notes](TURNKEY_SUPPORT.md#solana-notes) in TURNKEY_SUPPORT.md says what it checks), and so does a self-paid dry run, or a Turnkey or Rain wallet send status, that carries the System Program's `Transfer: insufficient lamports` line; the runtime's bare `InsufficientFundsFor*` error, and every EVM path, leave them null. A self-paid Solana dry run the runtime refuses for a fee or rent shortfall is this error, not `RAIN_403` or `RAIN_405`, on `sendToken`, `withdrawCollateral` and `prepareWithdrawal` alike. Which vendor refusals map here is under the Solana fee or rent shortfall row of [Error handling](TURNKEY_SUPPORT.md#error-handling) in TURNKEY_SUPPORT.md and each adapter README's Errors section. |
| `RAIN_403` | `RainError.TransactionSimulationFailed` | Preflight `eth_call` simulation failed: a contract revert, or on Portal any other refusal of the preflight that is neither a session failure nor transient, an insufficient-funds answer included. Also Privy's wallet API answering a send with a 4xx naming a revert, or the wallet backend's send status carrying decoded revert details. A Solana shortfall of the fee, the rent or a native send's amount is `InsufficientFunds`, checked before the revert rule. [Error handling](TURNKEY_SUPPORT.md#error-handling) in TURNKEY_SUPPORT.md says what counts as decoded, and what a failed status without them is. |
| `RAIN_404` | `RainError.WalletUnavailable` | The backing provider returned no usable wallet address (e.g. Turnkey context has no Ethereum account), or, for a Turnkey or Rain wallet key export, the cases under [Key export errors](TURNKEY_SUPPORT.md#key-export-errors). |
| `RAIN_405` | `RainError.WithdrawalRevertedByNetwork` | Withdrawal reverted on-chain (e.g. duplicate withdrawal, already-used signature), or a send status carrying decoded revert details on a withdrawal, under the same Error handling rows. A self-paid dry run the runtime refuses for a fee or rent shortfall is `RAIN_402` instead. Whether the dry run runs under sponsorship is under [Gas sponsorship](TURNKEY_SUPPORT.md#gas-sponsorship) in TURNKEY_SUPPORT.md. |
| `RAIN_406` | `RainError.InvalidAmount` | The amount is invalid for the token: zero on a withdrawal or an SPL send, negative, more decimal places than the token supports, or past `uint256` max. A caller-passed `decimals` outside `0..77` on an EVM withdrawal, a wallet-agnostic builder or an EVM `sendToken`, or a negative one on a Solana withdrawal, is refused the same way. |
| `RAIN_407` | `RainError.WalletNotAuthorized` | The wallet is not an admin of the collateral contract, or on Solana not the owner of the collateral account; checked before a withdrawal or a non-zero Auth Pull approval is signed. |
| `RAIN_501` | `RainError.ProviderError` | Portal, Turnkey, or other provider error. The key export cases are under [Key export errors](TURNKEY_SUPPORT.md#key-export-errors); the Rain wallet's cases, the passkey ceremonies' among them, are under [Errors](../rain-wallet-android/README.md#errors) in rain-wallet-android/README.md; the Privy provider's are under [Errors](../rain-privy-android/README.md#errors) in rain-privy-android/README.md. |
| `RAIN_502` | `RainError.InternalError` | EIP-712 encoding, ABI encoding, or internal processing error. The key export case is under [Key export errors](TURNKEY_SUPPORT.md#key-export-errors); the Rain wallet's cases are under [Errors](../rain-wallet-android/README.md#errors) in rain-wallet-android/README.md. |

### Error handling example

```kotlin
try {
    val client = rain.provider(ProviderId.PORTAL)
    val result = client.withdrawCollateral(...)
} catch (e: RainError) {
    when (e) {
        is RainError.SdkNotInitialized -> { /* SDK not built */ }
        is RainError.InvalidConfig -> { /* Bad config: ${e.message} */ }
        is RainError.ProviderNotRegistered -> { /* No provider registered under that id: ${e.details} */ }
        is RainError.InsufficientFunds -> { /* Not enough balance */ }
        is RainError.NetworkError -> { /* Network issue: ${e.cause} */ }
        else -> { /* Other error: ${e.code}: ${e.message} */ }
    }
}
```
