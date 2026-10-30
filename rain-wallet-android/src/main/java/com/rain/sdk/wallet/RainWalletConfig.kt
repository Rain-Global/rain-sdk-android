package com.rain.sdk.wallet

import android.app.Application
import com.rain.sdk.turnkey.TurnkeyConfig

/**
 * Configuration for the Rain wallet provider. The wallet backend identity (Rain's organization and
 * API host per environment) is embedded in the SDK; a host supplies the wallet auth token, picks
 * the environment, and tunes behaviour. The hooks are passed by name: the parameter order matches
 * the SDK's other provider configs, so none is a trailing lambda.
 *
 * There is no address override: a Rain wallet holds one Ethereum account, the one the SDK provisions
 * on first login, and the SDK signs with it and reads for it. A choice of account arrives with
 * multi-account wallets, if they come.
 *
 * @param authTokenProvider Returns the wallet auth token your backend mints with its Api-Key
 *                          (`POST /v1/issuing/embedded-wallets/auth-tokens`) for the contact the
 *                          SDK passes: the canonical contact of a code request or attach, or null
 *                          for a passkey sign-up. It runs under the
 *                          provider's authentication lock, so it must not call back into the
 *                          provider, and it should carry its own timeout. Throw a `RainError` to
 *                          pass it through; a backend it could not reach arrives as
 *                          `RainError.NetworkError` (`RAIN_301`) and any other exception as
 *                          `RainError.InvalidConfig` (`RAIN_102`). The `RainWalletConfig` row of
 *                          docs/METHODS.md has the rules: the token's lifetime, the retry on a
 *                          refused token, the threading and the mint endpoint's duties.
 * @param environment The Rain deployment the authentication runs against, which also fixes the
 *                    wallet backend organization: [RainWalletEnvironment.Production] by default,
 *                    or [RainWalletEnvironment.Sandbox]. Part of
 *                    the backend's one-shot configuration, so a second provider with another
 *                    deployment in the same launch makes every authentication call throw
 *                    `RainError.InvalidConfig`. The `RainWalletConfig` row of docs/METHODS.md has
 *                    the rules.
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
 *                   `Capability.GAS_SPONSORSHIP`. Sponsorship cost passes through to the partner.
 *                   Two limits: a sponsored send runs no client-side revert preflight, so a
 *                   failure surfaces as the backend's failed status after broadcast; and the rent
 *                   of a first-time recipient's Solana token account is left to Rain's
 *                   organization, so the SDK does not demand it from the sender while this is on
 *                   (it does with this off, and always on `prepareWithdrawal`; the Rain wallet
 *                   section of docs/METHODS.md has the contract). Defaults to true, which is the
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
