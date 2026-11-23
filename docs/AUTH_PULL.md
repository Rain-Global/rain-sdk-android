# Auth Pull integration (Android)

Auth Pull lets a card authorization draw funds directly from the user's wallet into their Rain
collateral contract. When an authorization arrives for a user whose tenant is enabled for Auth
Pull, Rain pulls the full amount in USDC from that wallet before approving the authorization.

**What the SDK does:** sets and inspects the ERC-20 allowance that makes the pull possible.
**What the SDK does not do:** the pull itself. That is Rain's `transferFrom`, executed server-side
with no app involvement.

Four methods cover the whole surface:

| Method | Purpose |
|---|---|
| `approveTokenAllowance(chainId, contractAddress, spender, amount?)` | Approve the user's collateral contract to spend the user's USDC |
| `getTokenAllowance(chainId, contractAddress, spender, owner?)` | Read the current allowance |
| `estimateApprovalFee(chainId, contractAddress, spender, amount?)` | Price the approval before submitting it |
| `confirmTokenAllowance(transactionHash, chainId, contractAddress, spender, amount?, owner?)` | Wait for a successful receipt and read back the resulting allowance |

The [Method reference](METHODS.md#approvetokenallowancechainid-contractaddress-spender-amount) has
the full parameter tables.

---

## Prerequisites

Before an authorization can pull:

1. The tenant is enabled for Auth Pull: contact Rain.
2. The user has a wallet address associated with their Rain account on a supported chain, and that wallet is an admin of the user's collateral contract there.
3. That wallet holds USDC on the supported chain.
4. That wallet holds native gas for the approval, unless the provider sponsors it ([Gas sponsorship](TURNKEY_SUPPORT.md#gas-sponsorship)).
5. That wallet has approved the user's collateral contract, the part this SDK performs.

## Supported chains and assets

USDC is the only asset supported while Auth Pull is in beta.

### Sandbox

| Chain | Chain ID | USDC |
|---|---|---|
| Base Sepolia | `84532` (`RainChain.BASE_SEPOLIA`) | `0x036CbD53842c5426634e7929541eC2318f3dCF7e` |
| Arbitrum Sepolia | `421614` (`RainChain.ARBITRUM_SEPOLIA`) | `0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d` |

### Production

| Chain | Chain ID | USDC |
|---|---|---|
| Base | `8453` (`RainChain.BASE_MAINNET`) | `0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913` |
| Arbitrum | `42161` (`RainChain.ARBITRUM_MAINNET`) | `0xaf88d065e77c8cC2239327C5EDb3A432268e5831` |

All four are in the SDK's built-in token registry, so balance and allowance reads resolve USDC's
decimals with no `registerTokens` call and no on-chain lookup.

`RainAuthPullChains.SANDBOX` and `PRODUCTION` hold these sets for an environment. Gate UI on
`authPullChainIds` instead, the configured set narrowed to chains with an RPC endpoint and the one
the approval guard enforces; the [authPullChainIds](METHODS.md#authpullchainids) row in
docs/METHODS.md has the rules. Reach for the static sets only before an SDK exists, such as a chain
picker built at startup.

## The configuration is the environment

The sandbox and production chain sets are disjoint, and the USDC addresses differ
between them. An approval mined on the wrong environment's chain is valid, never drawn on, and on
mainnet costs the user real gas.

The SDK therefore rejects mismatched chain and token targets locally with
`RainError.InvalidConfig` (`RAIN_102`), before wallet access.

Auth Pull has no environment setting of its own and reads none from elsewhere in the SDK, not even
the Rain wallet's `RainWalletConfig.environment`; nothing checks that the two agree. The
`RainAuthPullConfig` you pass is the environment, and its chains must belong to that environment's
set. Auth Pull stays disabled until the builder receives one:

```kotlin
val rain = RainSdk.builder()
    .rpcEndpoints(rpcEndpoints)
    .authPullConfig(RainAuthPullConfig.sandbox())
    .register(provider)
    .build()
```

A non-standard deployment uses `RainAuthPullConfig.custom(tokenAddresses)`, whose chains
may come from either environment's set.

## The spender

The spender is the user's collateral contract on the chain, the `proxyAddress` that
`GET /v1/issuing/users/{userId}/contracts` returns, one per user and per chain. Take it from the
Rain API and never from user input. Before a non-zero approval the SDK asks the spender itself
whether the wallet is one of its admins, and refuses unless it answers yes; the
[approveTokenAllowance](METHODS.md#approvetokenallowancechainid-contractaddress-spender-amount)
row in docs/METHODS.md has the codes. That read stops a typo, a plain address and a contract of
another kind. It does not prove that Rain issued the contract. A zero amount, `getTokenAllowance`
and `confirmTokenAllowance` accept any well-formed, non-zero spender.

A wallet that approved Rain's operator on `5.0.0-beta.1` approves its collateral contract as well,
and revokes the operator with a zero amount only when Rain says so. The SDK cannot grant the
operator a new allowance.

## Funding the wallet in sandbox

Testnet USDC comes from the [Circle faucet](https://faucet.circle.com/) for Base Sepolia or
Arbitrum Sepolia, sent to the wallet address associated with the Rain user. Native gas, where the
provider does not sponsor it, comes from the chain's faucet.

---

## Integration

### 1. Read the current allowance

Cheap and free: do this before prompting the user, so a wallet that is already approved does not
pay for a redundant transaction.

```kotlin
val allowance = client.getTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = proxyAddress
)

when {
    allowance.isUnlimited -> Unit               // Nothing to do.
    allowance.covers(expectedSpend) -> Unit     // Enough for now.
    else -> promptForApproval()
}
```

Compare `rawAmount` or `covers(...)`, never the display fields, and gate on `isUnlimited` before
rendering; the [RainTokenAllowance value type](METHODS.md#raintokenallowance-value-type) row in
docs/METHODS.md has each member's rules.

### 2. Estimate the fee (optional)

```kotlin
val fee = client.estimateApprovalFee(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = proxyAddress
)
// e.g. 0.00042 ETH
```

Nothing is broadcast and no signature is requested;
[Gas sponsorship](TURNKEY_SUPPORT.md#gas-sponsorship) in docs/TURNKEY_SUPPORT.md has the
sponsored-chain rule.

### 3. Approve

```kotlin
// Unlimited: Rain's recommendation, so the user never has to re-approve.
val result = client.approveTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = proxyAddress
)
println(result.transactionHash)
```

An omitted (or `null`) `amount` means unlimited. Pass a `BigDecimal` to cap the allowance, and
`BigDecimal.ZERO` to revoke:

```kotlin
// Cap at 250 USDC.
client.approveTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = proxyAddress,
    amount = BigDecimal("250")
)

// Revoke.
client.approveTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = proxyAddress,
    amount = BigDecimal.ZERO
)
```

A capped approval is a standing balance, not a per-authorization budget: each pull consumes part
of it, and once it runs out the next authorization is declined. Unlimited avoids that failure mode.

**Changing a non-zero allowance.** The call writes the new value straight over the old one. USDC
accepts that on all four Auth Pull chains. Some ERC-20s (USDT and its clones) revert unless the
allowance is set to `BigDecimal.ZERO` first, so approve zero, wait for it to mine, then approve the
new amount.

### 4. Confirm

`transactionHash` means submitted, not mined; confirm before treating the user as ready:

```kotlin
val updated = client.confirmTokenAllowance(
    transactionHash = result.transactionHash,
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = proxyAddress,
    amount = BigDecimal("250")   // omit for the unlimited approval
)
```

The read is pinned to the block the approval mined in and must equal the request; the
[confirmTokenAllowance](METHODS.md#confirmtokenallowancetransactionhash-chainid-contractaddress-spender-amount-owner)
row in docs/METHODS.md has each outcome and its code. A timeout is `TransactionPending`, not
failure: re-read the allowance or confirm again, never re-approve.

---

## Amounts and decimals

Amounts are human-readable `BigDecimal`s, as on `sendToken`; one finer than the token's decimals is
refused as `RainError.InvalidAmount` (`RAIN_406`), never truncated. There is no `decimals`
parameter: the scale comes from the registry or a strict on-chain `decimals()` read, and a token
that resolves neither way throws `RainError.TokenNotFound` (`RAIN_102`) rather than being scaled by
a guess. Register it with `registerTokens` if you hit it. An unlimited approval needs no scale, so
`approveTokenAllowance` and `estimateApprovalFee` skip the read. `getTokenAllowance` and
`confirmTokenAllowance` resolve it for every amount, unlimited included, so a token that resolves
neither way approves unlimited and still fails to confirm. Balance reads do fall back to 18; the [getBalance](METHODS.md#getbalancechainid-token) row in
docs/METHODS.md has the rule.

## Provider support

| Provider | Approve / allowance / fee estimate | Notes |
|---|---|---|
| **Portal** | Supported | Broadcast goes through `WalletProvider.sendTransaction`, the same path withdrawals use; Portal's pre-simulation applies to approvals unchanged. On chains where the Portal environment uses account abstraction, the adapter resolves the UserOperation hash; [Capabilities and chains](../rain-portal-android/README.md#capabilities-and-chains) in rain-portal-android/README.md has the mechanism, the window and the codes. |
| **Privy** | Supported | Same generic path, carrying the approve calldata unchanged. |
| **Turnkey** | Supported | Approvals ride the same pipeline as any other Turnkey send: gas-sponsored while `sponsorGas` is on (the default), and behind the broadcast-chain gate. [Which chains can send](TURNKEY_SUPPORT.md#which-chains-can-send) in docs/TURNKEY_SUPPORT.md has the gate, and every Auth Pull chain is inside it. |
| **Rain wallet** | Supported | Same pipeline as Turnkey: gas-sponsored while `sponsorGas` is on (the default), behind the same broadcast-chain gate. |

No provider needed a bespoke approval path, so no capability gate exists for this feature
(Turnkey's broadcast-chain gate applies to approvals as it does to every send). The
allowance value itself is read over the SDK's own configured RPC (`eth_call`), never through the
wallet provider, so it works on any provider and on any configured, trusted Auth Pull chain.

## Failure modes

The four methods throw `RainError` subclasses. The [Errors table](METHODS.md#errors) in
docs/METHODS.md has the meaning of each code, and the
[approveTokenAllowance](METHODS.md#approvetokenallowancechainid-contractaddress-spender-amount),
[getTokenAllowance](METHODS.md#gettokenallowancechainid-contractaddress-spender-owner) and
[confirmTokenAllowance](METHODS.md#confirmtokenallowancetransactionhash-chainid-contractaddress-spender-amount-owner)
rows there have the conditions each call throws under. Two facts live only here. An approval and a
fee estimate resolve the wallet's address, and an allowance read or a confirmation does so only when
`owner` is omitted; a provider with no address throws `RainError.WalletUnavailable` (`RAIN_404`)
from those calls. After `RainError.TransactionOutcomeUnknown` (`RAIN_305`) the approval may still
land, so re-read the allowance before approving again.

## Not covered

EIP-2612 `permit` (gasless) approvals are out of scope. Account abstraction is supported only as the
Portal adapter's UserOperation resolution, under
[Capabilities and chains](../rain-portal-android/README.md#capabilities-and-chains) in
rain-portal-android/README.md, not as a general ERC-4337 integration.

## See also

- [Method reference](METHODS.md)
- The Portal provider's README, [rain-portal-android/README.md](../rain-portal-android/README.md)
- [app/README.md](../app/README.md): the sample app's Auth Pull screen runs this whole flow
- Rain's [Auth Pull documentation](https://docs.rain.xyz/docs/real-time-funding)
