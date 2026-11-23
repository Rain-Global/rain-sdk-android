package com.rain.sdk.wallet

import android.app.Application
import com.rain.sdk.turnkey.TurnkeyConfig

/**
 * Configuration for the Rain wallet provider. The wallet backend identity (Rain's organization and
 * API host per environment) is embedded in the SDK; a host supplies the wallet auth token, picks
 * the environment, and tunes behaviour. The hooks are passed by name: the parameter order matches
 * the SDK's other provider configs, so none is a trailing lambda. There is no address override;
 * the Configuration section of rain-wallet-android/README.md says why.
 *
 * @param authTokenProvider Returns the wallet auth token your backend mints with its Api-Key
 *                          (`POST /v1/issuing/embedded-wallets/auth-tokens`) for the contact the
 *                          SDK passes: the canonical contact of a code request or attach, or null
 *                          for a passkey sign-up. It runs under the
 *                          provider's authentication lock, so it must not call back into the
 *                          provider, and it should carry its own timeout. Throw a `RainError` to
 *                          pass it through; a backend it could not reach arrives as
 *                          `RainError.NetworkError` (`RAIN_301`) and any other exception as
 *                          `RainError.InvalidConfig` (`RAIN_102`). Its row in
 *                          rain-wallet-android/README.md has the threading rules, *Minting the
 *                          wallet auth token* the token's lifetime and the mint endpoint's duties,
 *                          and *Errors* the retry on a refused token.
 * @param environment The Rain deployment the authentication runs against, which also fixes the
 *                    wallet backend organization: [RainWalletEnvironment.Production] by default,
 *                    or [RainWalletEnvironment.Sandbox]. Part of
 *                    the backend's one-shot configuration, so a second provider with another
 *                    deployment in the same launch makes every authentication call throw
 *                    `RainError.InvalidConfig`.
 * @param sessionPolicy Expiry/refresh/retry behaviour for the session guarding every wallet call.
 * @param onSessionExpired Re-auth hook: invoked once per session death when the session dies and
 *                         cannot be refreshed, whether that is discovered during a wallet call or
 *                         by the passive session watcher. May run on the calling coroutine's
 *                         thread or a watcher thread; hop to the main thread before touching UI,
 *                         and never call back into the SDK synchronously from it. Restart
 *                         authentication from here ([RainProvider.sendLoginCode] then
 *                         [RainProvider.confirmLoginCode], or [RainProvider.loginWithPasskey]). A
 *                         deliberate [RainProvider.logout] does not fire it. The provider holds the hook for its whole life, so
 *                         capture no Activity or ViewModel in it.
 * @param sponsorGas When true, every send on a supported chain is fee-sponsored: the wallet
 *                   backend builds and pays the network fee, fee estimates still quote what the
 *                   wallet would pay itself, and the descriptor advertises
 *                   `Capability.GAS_SPONSORSHIP`. Defaults to true, which is the product; pass
 *                   false to have users pay their own fees. *Gas sponsorship* in
 *                   docs/TURNKEY_SUPPORT.md has the rules, *Solana notes* there the preflight
 *                   limits, and *Capabilities and chains* in rain-wallet-android/README.md the
 *                   rent of a first-time recipient's Solana token account, which Rain's
 *                   organization funds while this is on.
 * @param passkeyDomain A web domain the partner controls, at least two labels of letters, digits
 *                      and hyphens such as `passkeys.example.com`, that the app's passkeys bind to;
 *                      [RainProvider]'s constructor refuses anything else with `RAIN_102`. Null or
 *                      blank turns [RainProvider.loginWithPasskey], [RainProvider.signUpWithPasskey]
 *                      and [RainProvider.addPasskey] off; they then throw `RainError.InvalidConfig`
 *                      (`RAIN_102`). The domain is permanent and must serve the association file
 *                      *Passkeys and login contacts* in rain-wallet-android/README.md describes; it
 *                      is part of the backend's one-shot configuration, as `environment` is.
 *                      Defaults to null.
 */
class RainWalletConfig(
    val authTokenProvider: suspend (RainWalletContact?) -> String,
    val environment: RainWalletEnvironment = RainWalletEnvironment.Production,
    val sessionPolicy: RainWalletSessionPolicy = RainWalletSessionPolicy(),
    val onSessionExpired: (() -> Unit)? = null,
    val sponsorGas: Boolean = true,
    val passkeyDomain: String? = null,
) {
    /** Every field, for logs and crash reports; the functions read as set or null. Nothing here is secret. */
    override fun toString(): String =
        "RainWalletConfig(authTokenProvider=set, environment=$environment, sessionPolicy=$sessionPolicy, " +
            "onSessionExpired=${if (onSessionExpired != null) "set" else "null"}, sponsorGas=$sponsorGas, " +
            "passkeyDomain=$passkeyDomain)"
}

/**
 * The backing adapter's configuration for this wallet, field for field, with `sponsorRent` always
 * true: whether Rain's organization funds the rent is Rain's switch, which a partner can neither see
 * nor set, and a flip of it has to reach installed apps without a release. A function of its own so
 * the mapping is testable.
 */
@JvmSynthetic
internal fun RainWalletConfig.toBacking(application: Application): TurnkeyConfig {
    val backend = RainWalletBackend.rowFor(environment)
    val mintToken = authTokenProvider
    return TurnkeyConfig(
        application = application,
        organizationId = backend.organizationId,
        rainApiBaseUrl = backend.apiBaseUrl,
        authTokenProvider = { contact -> mintToken(contact?.toRainWallet()) },
        sessionPolicy = sessionPolicy.toBacking(),
        onSessionExpired = onSessionExpired,
        sponsorGas = sponsorGas,
        sponsorRent = true,
        passkeyDomain = passkeyDomain,
    )
}
