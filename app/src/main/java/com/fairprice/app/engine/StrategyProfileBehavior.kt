package com.fairprice.app.engine

/**
 * Fallback-only mapping from profile codes (Sprint 14) to behavior.
 * Used when the backend sends the old payload shape (only strategy_profile).
 * Execution uses flat booleans from StrategyResult when the new payload is present.
 */
object StrategyProfileBehavior {
    /** Profile code for tier 0: least aggressive, no countermeasures. */
    const val CLEAN_BASELINE = "clean_baseline"

    /** Profile code for tier 3: maximum countermeasures. */
    const val STEALTH_MAX = "stealth_max"

    private val profilesWithUrlSanitize = setOf("shield_basic", "amnesia_standard", "stealth_max")
    private val profilesWithStrictTracking = setOf("shield_basic", "amnesia_standard", "stealth_max")
    private val profilesWithAmnesia = setOf("amnesia_standard", "stealth_max")
    private val profilesWithCanvasSpoof = setOf("stealth_max")

    fun requiresUrlSanitize(profileCode: String): Boolean =
        profileCode in profilesWithUrlSanitize

    fun trackingProtection(profileCode: String): String =
        if (profileCode in profilesWithStrictTracking) "strict" else "off"

    fun bootstrapTokenValue(profileCode: String): String =
        when (profileCode) {
            "clean_baseline", "shield_basic", "amnesia_standard", "stealth_max" -> profileCode
            else -> CLEAN_BASELINE
        }

    fun amnesiaWipeRequired(profileCode: String): Boolean =
        profileCode in profilesWithAmnesia

    fun strictTrackingProtection(profileCode: String): Boolean =
        profileCode in profilesWithStrictTracking

    fun canvasSpoofingActive(profileCode: String): Boolean =
        profileCode in profilesWithCanvasSpoof

    private val profilesWithUaSpoof = setOf("stealth_max")

    fun uaSpoofingActive(profileCode: String): Boolean =
        profileCode in profilesWithUaSpoof
}
