package com.rain.sdk.provider

import com.rain.sdk.ExperimentalRainApi

/**
 * A registrable wallet-provider descriptor — the adapter side of the ports-and-adapters split.
 *
 * Each vendor module ships exactly one `ProviderDescriptor` (e.g. `rain-portal-android`'s `PortalProvider`,
 * `rain-turnkey-android`'s `TurnkeyProvider`). The host registers the providers it ships via
 * [com.rain.sdk.RainSdk.Builder.register]; nothing in core references a concrete provider type,
 * so a provider whose module isn't linked simply isn't on the classpath.
 *
 * The descriptor advertises its [id] and [capabilities] up front (cheap, synchronous) and defers
 * the expensive vendor wiring to [create], which core invokes lazily on first use.
 */
@SubclassOptInRequired(ExperimentalRainApi::class)
interface ProviderDescriptor {
    /** Stable identifier used to resolve this provider via [com.rain.sdk.RainSdk.provider]. */
    val id: ProviderId

    /** Optional behaviours this provider supports; used for capability-based resolution. */
    val capabilities: Set<Capability>

    /**
     * Materializes the vendor-backed [WalletProvider]. Core supplies shared, vendor-free
     * infrastructure (RPC view, token store, chain readers) via [context] so adapters don't
     * each rebuild it. May suspend — e.g. to probe that the vendor has a usable wallet.
     */
    suspend fun create(context: ProviderContext): WalletProvider

    /**
     * Releases everything this descriptor owns outside the returned [WalletProvider] — vendor
     * clients, session watchers, host callbacks. Called by [com.rain.sdk.RainSdk.close]; must be
     * idempotent, and is terminal: a closed descriptor is not re-registrable.
     *
     * Providers holding no such state need no override.
     */
    fun close() {}
}
