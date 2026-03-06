package com.fairprice.app.coordinator

import com.fairprice.app.engine.ExtractionResult

private const val ENGINE_HASH_KEY = "fp_engine"

/**
 * Appends or replaces the fp_engine token in the URL fragment for engine bootstrap telemetry.
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
