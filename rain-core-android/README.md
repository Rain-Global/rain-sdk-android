# rain-core-android

The vendor-free core of the modular Rain Android SDK — the "one core" in *one core, many providers*.

Contains:

- The **`WalletProvider`** port (the capability the SDK needs from any wallet).
- The **`ProviderDescriptor`** + **`RainSdk`** builder/registry
  (`RainSdk.builder().register(…).build()`, resolve via `provider(id)` / `first { }`).
- The **`Capability`** model and **`ProviderId`**.
- All Rain domain logic — EIP-712 message building, collateral withdraw flow (EVM and Solana),
  transaction orchestration, chain readers, token metadata store.
- The cross-module seams the adapter modules build on, marked `@RainAdapterApi`: the chain
  readers, the JSON-RPC client, `RainHttp` (the one OkHttp client behind the SDK's own calls), the
  Solana encoders (whose Base58 also encodes the Turnkey adapter's exported Solana keypair), and the
  token metadata store. Marked rather than plain public so they stay out of the contract with host
  apps.

`rain-core-android` has **no wallet-vendor dependency at all**. Every vendor SDK lives in its own
adapter module, so a Portal-only app never fetches Turnkey and a Turnkey-only app never fetches
Portal. The Rain wallet (`rain-wallet-android`) is a product over the Turnkey adapter: SDK-owned
login, sessions and key export under Rain's names, with the adapter hidden from a host's compile
classpath.

Add a wallet provider by depending on its adapter, which pulls core transitively:
`xyz.rain:rain-wallet-android`, `rain-turnkey-android`, `rain-portal-android` or
`rain-privy-android`, at `5.0.0-beta.1`.

```kotlin
// Bring-your-own Turnkey, from :rain-turnkey-android — the host authenticated turnkeyContext.
val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(8453 to "https://mainnet.base.org"))
    .register(TurnkeyProvider(TurnkeyConfig(turnkey = turnkeyContext)))
    .build()

val client = rain.provider(ProviderId.TURNKEY)
val address = client.getWalletAddress()
```

## Architecture

```mermaid
graph TD
    App[Android App] --> RainSdk[RainSdk registry]
    RainSdk --> RainClient[RainClient]
    RainClient --> Coord[TransactionCoordinator]
    Coord --> Builder["EIP-712 and calldata builder"]
    Coord --> Port[WalletProvider port]
    Port -.-> Turnkey["TurnkeyWalletProvider<br/>(rain-turnkey-android)"]
    Port -.-> Portal["PortalWalletProvider<br/>(rain-portal-android)"]
    Port -.-> Privy["PrivyWalletProvider<br/>(rain-privy-android)"]
    Turnkey --> TurnkeyMgr["TurnkeyManager<br/>every Turnkey wallet call"]
    Portal --> PortalMgr["PortalManager<br/>every Portal wallet call"]
    Privy --> PrivyMgr["PrivyManager<br/>every Privy wallet call"]
    Builder --> Web3j[Web3j RPC]
    RainClient --> Readers[EvmChainReader / SolanaChainReader]
```

A provider is registered on the builder and resolved into a `RainClient` bound to that one wallet.
Core imports no wallet vendor; every adapter lives behind the port in its own module. Inside an
adapter the wallet provider is the port: it routes by chain, gates sends and shapes each method.
The manager is the vendor wrapper: every wallet-operation call to the vendor SDK runs there. The
adapter's session coordinator guards those calls and turns a vendor failure into a `RainError`,
inside the manager for Turnkey and Privy and inside the wallet provider for Portal. Turnkey's and
Portal's managers return Rain types; Privy's returns the vendor's transaction pages for its
provider to shape.

## Entry points

- **`RainSdk`** — the registry plus the wallet-agnostic surface: `provider(id)` / `first { }`,
  the transaction-building methods, `tokenMetadata(chainId, address)` and `registerTokens(tokens)`.
  The Rain issuing API is the host's call; the SDK consumes its results.
- **`RainClient`** — everything bound to one resolved wallet: addresses and QR, balances, transfers,
  `withdrawCollateral`, fee/gas estimation, history, `registerTokens`.
- **The wallet-agnostic builder methods on `RainSdk`** — for hosts signing with their own wallet:
  `getLatestNonce`, `isCollateralAdmin`, `buildEIP712Message`, `buildWithdrawTransactionData`. They
  live on `RainSdk` itself, so they need no resolved provider.

Full parameter tables live in [docs/METHODS.md](../docs/METHODS.md).

## Error handling

Every SDK operation throws a `RainError` subclass carrying a stable `RAIN_*` code. The code table is
a cross-platform contract, pinned by `RainErrorCodeParityTest` — see
[docs/METHODS.md](../docs/METHODS.md#errors) for the full list.

## Configuration

RPC endpoints are supplied on the builder, once, for every chain the app uses. `RainChain` holds the
chain-ID constants, including the Solana sentinels (`SOLANA_MAINNET` 900, `SOLANA_DEVNET` 901,
`SOLANA_TESTNET` 902) the SDK uses to route Solana work.

```kotlin
RainSdk.builder()
    .rpcEndpoints(
        mapOf(
            RainChain.BASE_SEPOLIA to "https://sepolia.base.org",
            RainChain.SOLANA_DEVNET to "https://api.devnet.solana.com"
        )
    )
```

`registerTokens(...)` names tokens the SDK cannot discover on chain: an SPL mint carries no
on-chain symbol, and the built-in registry covers the mainnets plus the two Auth Pull sandbox
testnets (Base Sepolia, Arbitrum Sepolia). Available on the builder, on
`RainSdk` after `build()`, and on a resolved `RainClient`; every entry is validated (address shape and
checksum, `decimals` in `0..77`).
