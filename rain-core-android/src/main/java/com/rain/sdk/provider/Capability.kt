package com.rain.sdk.provider

/**
 * The set of optional behaviours a wallet provider may support.
 *
 * Core models capabilities explicitly so it can degrade gracefully instead of crashing when a
 * provider can't do something at all (see the modular-architecture proposal, §4 "The provider
 * port"). The port stays the lowest common denominator of what Rain needs; anything a provider
 * *might* do, but not all do, is expressed here rather than as a new required port method.
 *
 * - [EXPORT]: the wallet's key material can be exported / backed up.
 * - [RECOVERY]: the wallet supports a recovery ceremony.
 * - [MULTI_CHAIN]: the provider holds accounts across multiple chain families (e.g. EVM + Solana).
 * - [BIOMETRIC_GATE]: signing is gated behind a device biometric / passkey prompt.
 * - [GAS_SPONSORSHIP]: a third party pays the network fee for the provider's sends.
 *   [WalletProvider.sponsorsFees] is core's per-chain check; *Gas sponsorship* in
 *   `docs/TURNKEY_SUPPORT.md` has the rules, what core skips included.
 */
enum class Capability {
    EXPORT,
    RECOVERY,
    MULTI_CHAIN,
    BIOMETRIC_GATE,
    GAS_SPONSORSHIP,
}
