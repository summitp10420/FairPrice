package com.fairprice.app.coordinator

import com.fairprice.app.engine.ExtractionResult
import com.fairprice.app.engine.StrategyResult

private const val ENGINE_HASH_KEY = "fp_engine"
private const val CANVAS_SPOOF_KEY = "fp_canvas_spoof"

/**
 * Builds execution URL fragment from strategy: fp_canvas_spoof when canvas spoofing is active,
 * and fp_engine for telemetry (strategy code).
 */
fun buildEngineBootstrapUrl(executionUrl: String, strategy: StrategyResult): String {
    val hashIndex = executionUrl.indexOf('#')
    val base = if (hashIndex < 0) executionUrl else executionUrl.substring(0, hashIndex)
    val existingHash = if (hashIndex < 0) "" else executionUrl.substring(hashIndex + 1)
    val hashParts = existingHash
        .split("&")
        .filter { it.isNotBlank() }
        .filterNot { part ->
            val key = part.substringBefore('=').trim().lowercase()
            key == ENGINE_HASH_KEY || key == CANVAS_SPOOF_KEY
        }
        .toMutableList()
    hashParts += "$ENGINE_HASH_KEY=${strategy.effectiveStrategyCode()}"
    if (strategy.canvasSpoofingActive) {
        hashParts += "$CANVAS_SPOOF_KEY=true"
    }
    return if (hashParts.isEmpty()) base else "$base#${hashParts.joinToString("&")}"
}

/**
 * Appends or replaces the fp_engine token in the URL fragment (fallback for legacy callers).
 */
fun appendEngineBootstrapToken(executionUrl: String, tokenValue: String): String {
    val hashIndex = executionUrl.indexOf('#')
    if (hashIndex < 0) {
        return "$executionUrl#$ENGINE_HASH_KEY=$tokenValue"
    }
    val base = executionUrl.substring(0, hashIndex)
    val existingHash = executionUrl.substring(hashIndex + 1)
    val hashParts = existingHash
        .split("&")
        .filter { it.isNotBlank() }
        .filterNot { part ->
            part.substringBefore('=').trim().equals(ENGINE_HASH_KEY, ignoreCase = true)
        }
        .toMutableList()
    hashParts += "$ENGINE_HASH_KEY=$tokenValue"
    return "$base#${hashParts.joinToString("&")}"
}

/**
 * Returns true if the extraction result indicates a WAF block.
 */
fun ExtractionResult.isWafBlockDetected(): Boolean {
    if (debugExtractionPath.equals("waf_block", ignoreCase = true)) return true
    return tactics.any { it.startsWith("block_", ignoreCase = true) }
}
