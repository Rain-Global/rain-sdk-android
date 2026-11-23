package com.rain.sdk.sample

import com.rain.sdk.error.RainError
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * [contract]'s tokens with name, symbol and decimals filled in from [lookup] (`RainSdk.tokenMetadata`
 * in the app), read in parallel and returned in the contract's order. A token whose lookup returns
 * null, or throws a [RainError] (a chain this build has no RPC endpoint for, an address the SDK
 * rejects), keeps its wire fields: null metadata, never a guessed scale. [onUnavailable] hears about
 * each lookup that threw, so the caller can log it. A cancellation propagates.
 */
internal suspend fun tokensWithMetadata(
    contract: CollateralContract,
    lookup: suspend (chainId: Int, address: String) -> TokenInfo?,
    onUnavailable: (CollateralToken, RainError) -> Unit = { _, _ -> },
): List<CollateralToken> = coroutineScope {
    contract.tokens
        .map { token ->
            async {
                val info = try {
                    lookup(contract.chainId, token.address)
                } catch (e: RainError) {
                    onUnavailable(token, e)
                    null
                }
                token.withMetadata(info)
            }
        }
        .awaitAll()
}

private fun CollateralToken.withMetadata(info: TokenInfo?): CollateralToken =
    if (info == null) this else copy(name = info.name, symbol = info.symbol, decimals = info.decimals)

/**
 * [tokens] as [TokenInfo] entries for `RainSdk.registerTokens`, so the wallet's balance list names
 * and includes the collateral contract's tokens. Only a token whose decimals resolved qualifies: the
 * SDK has no registration without decimals, and a guessed scale would misstate every balance.
 */
internal fun registrableTokenInfos(chainId: Int, tokens: List<CollateralToken>): List<TokenInfo> =
    tokens.mapNotNull { token ->
        token.decimals?.let { decimals -> TokenInfo(chainId, token.address, token.symbol, decimals, token.name) }
    }
