package com.rain.sdk.turnkey

import com.rain.sdk.error.RainError

/**
 * The pure pieces of the passkey flows, kept out of the controller so they can be read and tested
 * on their own: the relying-party domain's normalization, the authenticator name and the fixed
 * messages.
 */
internal object TurnkeyPasskeys {

    /**
     * A bare host name with at least one label separator: letters, digits and hyphens per label,
     * no scheme, port, path, whitespace, quote or backslash. The vendor interpolates the domain
     * into the WebAuthn request JSON unescaped, so anything else is refused before it gets there.
     */
    private val BARE_HOST = Regex(
        """^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"""
    )

    /**
     * The domain the host configured, trimmed; null when passkeys are off (a null or blank value).
     * Anything that is not a bare host name throws [RainError.InvalidConfig] here, at construction,
     * rather than at the passkey sheet.
     */
    fun normalizedDomainOrNull(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (!BARE_HOST.matches(trimmed)) throw RainError.InvalidConfig(MALFORMED_DOMAIN_MESSAGE)
        return trimmed
    }

    /**
     * The name the device's credential provider shows for a passkey, and the name `addPasskey`
     * registers one under: `passkey-<unix seconds>`, a cross-platform contract shared by Rain's SDKs
     * (Rain names the authenticator a sign-up registers itself). Explicit because the vendor's own
     * default uses milliseconds, and no character in it needs escaping in the vendor's request JSON.
     */
    fun authenticatorName(epochSeconds: Double): String = "passkey-${epochSeconds.toLong()}"

    const val MALFORMED_DOMAIN_MESSAGE =
        "passkeyDomain must be a registrable domain of at least two labels, letters, digits and hyphens only " +
            "(punycode for an internationalized name), such as passkeys.example.com, without scheme, port or path"

    const val NOT_CONFIGURED_MESSAGE =
        "Passkeys are not configured for this app: set passkeyDomain to a web domain you control and serve " +
            "https://<domain>/.well-known/assetlinks.json in the shape of Google's passkey example, with the " +
            "site's own statement and the app statement listing this app's package name and " +
            "signing-certificate fingerprints"

    const val ALREADY_SIGNED_IN_MESSAGE =
        "Already signed in on this device; log out before creating a new account with a passkey"
}
