# rain-turnkey-android

The [Turnkey](https://turnkey.com) adapter for the modular Rain Android SDK: `TurnkeyProvider`, in
package `com.rain.sdk.turnkey`, the `ProviderDescriptor` for Turnkey's P256-stamper signer;
[docs/TURNKEY_SUPPORT.md](../docs/TURNKEY_SUPPORT.md) is the full guide. In bring-your-own mode
your app authenticates against Turnkey's Kotlin SDK and hands the `TurnkeyContext` to
`TurnkeyConfig(turnkey)`. Managed mode (`@InternalRainTurnkeyApi`) is internal API the Rain wallet
provider is built on: [Managed mode (internal API)](../docs/TURNKEY_SUPPORT.md#managed-mode-internal-api)
names its members, and the [Members](../rain-wallet-android/README.md#members) table of
rain-wallet-android/README.md has their contract.

Depends on `rain-core-android` and the Turnkey Kotlin SDK, so linking this module is all a
bring-your-own app needs. [Adding the dependency](../docs/TURNKEY_SUPPORT.md#adding-the-dependency)
has the install line and points at the version catalog for the pins;
[Requirements](../docs/TURNKEY_SUPPORT.md#requirements) has the JDK 24 note.

## Quickstart

Drive Turnkey's Kotlin SDK yourself, then hand the authenticated `TurnkeyContext` to Rain:

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyKeyFamily
import com.rain.sdk.turnkey.TurnkeyProvider
import com.turnkey.core.TurnkeyContext

val provider = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext))
val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(8453 to "https://mainnet.base.org", 84532 to "https://sepolia.base.org"))
    .register(provider)
    .build()
val client = rain.provider(ProviderId.TURNKEY)

// Backup, in either mode: the recovery phrase, or one private key per chain family, decrypted on the device.
val phrase = provider.exportRecoveryPhrase()
val solanaKey = provider.exportPrivateKey(TurnkeyKeyFamily.SOLANA)
```

[Bring-your-own mode](../docs/TURNKEY_SUPPORT.md#bring-your-own-mode-the-public-turnkey-integration)
has the configuration and points at Turnkey's getting-started guide for the initialization; the
sample's `app/src/main/java/com/rain/sdk/sample/TurnkeyAuthSample.kt` is reference code for the
host-side login.

## Capabilities and chains

Advertised capabilities: `EXPORT`, `MULTI_CHAIN`, plus `GAS_SPONSORSHIP` while `sponsorGas` is on
(the default); `sponsorRent`, off by default, is read with it. [Which chains can send](../docs/TURNKEY_SUPPORT.md#which-chains-can-send),
[Gas sponsorship](../docs/TURNKEY_SUPPORT.md#gas-sponsorship) and
[Solana notes](../docs/TURNKEY_SUPPORT.md#solana-notes) in docs/TURNKEY_SUPPORT.md have the rules.

## Session expiry and retry

`TurnkeySessionPolicy` controls refresh and retry;
[Session expiry, refresh, and retry](../docs/TURNKEY_SUPPORT.md#session-expiry-refresh-and-retry)
and [Observing session state](../docs/TURNKEY_SUPPORT.md#observing-session-state) in
docs/TURNKEY_SUPPORT.md have the policy snippet, the rules, what a refused refresh clears and the
states.

Reference: the sample's `app/src/main/java/com/rain/sdk/sample/RainSession.kt`,
`WalletSessionStatus.kt` and `screens/HomeScreen.kt` (`SessionCard`).

## Errors

The Errors table in [docs/METHODS.md](../docs/METHODS.md#errors) has the meanings;
[Error handling](../docs/TURNKEY_SUPPORT.md#error-handling) in docs/TURNKEY_SUPPORT.md has the
full mapping, [Key export errors](../docs/TURNKEY_SUPPORT.md#key-export-errors) the export table
and [After a send is accepted](../docs/TURNKEY_SUPPORT.md#after-a-send-is-accepted) the send
endings. Conditions this adapter maps itself:

| Condition | Code |
|---|---|
| `walletAddress` at construction: a malformed address, or a wrong mixed-case checksum. A valid override is stored in EIP-55 checksum form, the spelling `getWalletAddress()` returns | `RainError.InvalidConfig` (`RAIN_102`) |
| The self-paid fee estimate runs over core's JSON-RPC client, not Turnkey: a node JSON-RPC error other than a revert, or a result the client cannot read | `RainError.InternalError` (`RAIN_502`) |

## Notes

- A `Duplicate class org.bouncycastle...` build error:
  [Bouncy Castle dependency conflict](../docs/TURNKEY_SUPPORT.md#bouncy-castle-dependency-conflict-downstream-consumers)
  in docs/TURNKEY_SUPPORT.md has the exclusion to add.
