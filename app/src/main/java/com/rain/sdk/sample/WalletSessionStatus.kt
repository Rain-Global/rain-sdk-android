package com.rain.sdk.sample

import com.rain.sdk.portal.PortalSessionState
import com.rain.sdk.privy.PrivySessionState
import com.rain.sdk.turnkey.TurnkeySessionState
import com.rain.sdk.wallet.RainWalletSessionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Coarse health of the wallet session, for the status dot on the Home screen's session card. */
enum class SessionHealth { Healthy, Transitional, Dead, Unknown }

/**
 * Provider-agnostic view of the wallet session; each provider's state type maps to it below.
 * [label] is the sentence-case headline the session card shows, [detail] its descriptor.
 */
data class WalletSessionStatus(
    val label: String,
    val health: SessionHealth,
    val detail: String? = null,
)

/** Rain wallet: session-backed, so `Active` carries an expiry. */
fun RainWalletSessionState.toStatus(): WalletSessionStatus = when (this) {
    is RainWalletSessionState.Loading ->
        WalletSessionStatus("Restoring session", SessionHealth.Transitional)
    is RainWalletSessionState.Active ->
        WalletSessionStatus(
            label = "Session healthy",
            health = SessionHealth.Healthy,
            detail = "Expires at ${formatClock(expiresAtEpochSeconds)}, refreshed by the SDK"
        )
    is RainWalletSessionState.Expired ->
        WalletSessionStatus("Session expired", SessionHealth.Dead, "Log in again")
    is RainWalletSessionState.Unauthenticated ->
        WalletSessionStatus("Not signed in", SessionHealth.Dead, "Log in again")
}

/** Turnkey, bring-your-own: JWT-backed, so `Active` carries an expiry. */
fun TurnkeySessionState.toStatus(): WalletSessionStatus = when (this) {
    is TurnkeySessionState.Loading ->
        WalletSessionStatus("Restoring session", SessionHealth.Transitional)
    is TurnkeySessionState.Active ->
        WalletSessionStatus(
            label = "Session healthy",
            health = SessionHealth.Healthy,
            detail = "Expires at ${formatClock(expiresAtEpochSeconds)}, refreshed by the SDK"
        )
    is TurnkeySessionState.Expired ->
        WalletSessionStatus("Session expired", SessionHealth.Dead, "Log in again")
    is TurnkeySessionState.Unauthenticated ->
        WalletSessionStatus("Not signed in", SessionHealth.Dead, "Log in again")
}

/** Privy: self-refreshing with no expiry; `Unverified` = restored offline, recoverable. */
fun PrivySessionState.toStatus(): WalletSessionStatus = when (this) {
    is PrivySessionState.Loading ->
        WalletSessionStatus("Restoring session", SessionHealth.Transitional)
    is PrivySessionState.Active ->
        WalletSessionStatus("Session healthy", SessionHealth.Healthy, "Privy refreshes the session itself")
    is PrivySessionState.Unverified ->
        WalletSessionStatus(
            label = "Session unverified",
            health = SessionHealth.Transitional,
            detail = "Restored offline; re-verified when connectivity returns"
        )
    is PrivySessionState.Unauthenticated ->
        WalletSessionStatus("Not signed in", SessionHealth.Dead, "Log in again")
}

/** Portal: derived from call outcomes — the vendor exposes no auth state. */
fun PortalSessionState.toStatus(): WalletSessionStatus = when (this) {
    is PortalSessionState.Unknown ->
        WalletSessionStatus("Session unknown", SessionHealth.Unknown, "No Portal call has completed yet")
    is PortalSessionState.Active ->
        WalletSessionStatus("Session healthy", SessionHealth.Healthy, "Last Portal call succeeded")
    is PortalSessionState.Refreshing ->
        WalletSessionStatus("Refreshing session", SessionHealth.Transitional, "Installing a re-minted session token")
    is PortalSessionState.Expired ->
        WalletSessionStatus(
            label = "Session expired",
            health = SessionHealth.Dead,
            detail = "Portal rejected the session token. Provide a new one"
        )
}

private val clockFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatClock(epochSeconds: Double): String =
    clockFormatter.format(Instant.ofEpochSecond(epochSeconds.toLong()))
