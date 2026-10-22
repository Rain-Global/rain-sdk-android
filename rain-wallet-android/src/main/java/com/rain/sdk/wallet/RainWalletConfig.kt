package com.rain.sdk.wallet

import android.app.Application
import com.rain.sdk.turnkey.TurnkeyConfig

/**
 * Configuration for the Rain wallet provider. The wallet backend identity (Rain's organization and
 * API host per environment) is embedded in the SDK; a host supplies the one thing only its backend
 * can, the wallet auth token, picks the environment, and tunes behaviour. Every parameter but the
 * token supplier has a default. The hooks are passed by name: the parameter order matches the
 * SDK's other provider configs, so none is a trailing lambda.
 *
 * There is no address override: a Rain wallet holds one Ethereum account, the one the SDK provisions
 * on first login, and the SDK signs with it and reads for it. A choice of account arrives with
 * multi-account wallets, if they come.
 *
 * @param authTokenProvider Returns the wallet auth token your backend mints with its Api-Key
 *                          (`POST /v1/issuing/embedded-wallets/auth-tokens`) for the contact the
 *                          SDK passes: the canonical contact of a code request, or null for a
 *                          passkey sign-up. Called once per code request or sign-up, and once
 *                          more for each leg Rain refuses the token on. It runs under the
 *                          provider's authentication lock, so it must not call back into the
 *                          provider, and it should carry its own timeout. Throw a `RainError` to
 *                          pass it through; a backend it could not reach arrives as
 *                          `RainError.NetworkError` (`RAIN_301`) and any other exception as
 *                          `RainError.InvalidConfig` (`RAIN_102`), each naming the class only. Your
 *                          mint endpoint authenticates the app user and throttles before minting,
 *                          because Rain limits by IP only. The `RainWalletConfig` row of
 *                          docs/METHODS.md has the rules, the token's lifetime and the threading
 *                          included.
 * @param environment The Rain deployment the authentication runs against:
 *                    [RainWalletEnvironment.Production] by default, which the [RainProvider]
 *                    constructor refuses with `RainError.InvalidConfig` until the release that turns
 *                    production on, so pass [RainWalletEnvironment.Sandbox] until then, or a
 *                    [RainWalletEnvironment.Custom]. The `RainWalletConfig` row of docs/METHODS.md
 *                    has the rules.
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
 *                   `Capability.GAS_SPONSORSHIP`. Sponsorship cost passes
 *                   through to the partner. Two limits: rent for a first-time recipient's Solana
 *                   token account is a separate backend setting and stays with the sender, and a
 *                   sponsored send runs no client-side revert preflight, so a failure surfaces as
 *                   the backend's failed status after broadcast. Defaults to true, which is the
 *                   product; pass false to have users pay their own fees.
 * @param passkeyDomain A web domain the partner controls, at least two labels of letters, digits
 *                      and hyphens such as `passkeys.example.com`, that the app's passkeys bind to.
 *                      Null or blank turns
 *                      [RainProvider.loginWithPasskey], [RainProvider.signUpWithPasskey] and
 *                      [RainProvider.addPasskey] off; they then throw `RainError.InvalidConfig`
 *                      (`RAIN_102`). A scheme, port, path or a single label such as `localhost`
 *                      throws `RAIN_102` from the [RainProvider] constructor. The domain must serve
 *                      `https://<domain>/.well-known/assetlinks.json` in the shape of Google's
 *                      passkey example: a statement for the site itself with `get_login_creds`, and
 *                      an app statement with both `handle_all_urls` and `get_login_creds` listing
 *                      the app's package name and every signing-certificate fingerprint (debug,
 *                      upload and Play App Signing). The device refuses every passkey request
 *                      over a file that carries the app statement alone. The domain is
 *                      permanent, because every passkey created against it stops working when it
 *                      changes, and a passkey made for one domain does not work in an app on
 *                      another.
 *                      Partners host their own file; Rain runs no shared domain. Part of the
 *                      backend's one-shot configuration: a second provider with a different domain in
 *                      the same app launch makes every authentication call throw `RainError.InvalidConfig`.
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
 * The backing adapter's configuration for this wallet: the environment's embedded backend identity,
 * the host's token supplier in the backing's own contact terms, plus the host's behaviour, field
 * for field. A function of its own so the mapping is testable.
 */
@JvmSynthetic
internal fun RainWalletConfig.toBacking(application: Application): TurnkeyConfig {
    val backend = RainWalletBackend.rowFor(environment)
    val mintToken = authTokenProvider
    return TurnkeyConfig(
        application = application,
        organizationId = backend.organizationId,
        authProxyConfigId = RainWalletBackend.AUTH_CONFIG_ID,
        rainApiBaseUrl = backend.apiBaseUrl,
        authTokenProvider = { contact -> mintToken(contact?.toRainWallet()) },
        sessionPolicy = sessionPolicy.toBacking(),
        onSessionExpired = onSessionExpired,
        sponsorGas = sponsorGas,
        passkeyDomain = passkeyDomain,
    )
}
