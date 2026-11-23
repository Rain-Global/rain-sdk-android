package com.rain.sdk.internal.helpers

import com.rain.sdk.interfaces.CollateralAdminCheck
import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.core.RainTransactionBuilderImpl

internal class StubTransactionBuilder(
    val adminCheck: CollateralAdminCheck = CollateralAdminCheck.Admin,
) : RainTransactionBuilder by RainTransactionBuilderImpl(emptyMap()) {

    data class AdminCall(val chainId: Int, val proxyAddress: String, val walletAddress: String)

    val adminCalls = mutableListOf<AdminCall>()

    override suspend fun isCollateralAdmin(
        chainId: Int,
        proxyAddress: String,
        walletAddress: String
    ): CollateralAdminCheck {
        adminCalls += AdminCall(chainId, proxyAddress, walletAddress)
        return adminCheck
    }
}
