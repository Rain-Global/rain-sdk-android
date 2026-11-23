package com.rain.sdk.models

/**
 * Result of an ERC-20 approval submitted by
 * [com.rain.sdk.interfaces.RainClient.approveTokenAllowance].
 *
 * The wrapper exists so the public surface stays forward-compatible — future versions can attach
 * richer metadata (status, included block, fee paid) without breaking the call shape.
 *
 * @property transactionHash The on-chain transaction hash of the `approve` call. The approval is
 *   only in effect once that transaction is mined; pass this hash to
 *   [com.rain.sdk.interfaces.RainClient.confirmTokenAllowance], which waits for the receipt and
 *   verifies the allowance at the mined block. An immediate
 *   [com.rain.sdk.interfaces.RainClient.getTokenAllowance] read is unpinned; the
 *   `getTokenAllowance` section in `docs/METHODS.md` has the rule.
 */
data class RainTokenApprovalResult(
    val transactionHash: String
)
