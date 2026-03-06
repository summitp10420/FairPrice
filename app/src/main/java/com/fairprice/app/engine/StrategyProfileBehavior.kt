package com.fairprice.app.engine

/**
 * Maps strategy profile codes (from backend) to run behavior.
 * Unknown codes use the same behavior as clean_control_v1 (safe default).
 */
object StrategyProfileBehavior {
    /** Profile code for Yale-style spoof: URL sanitize, strict tracking, yale bootstrap token. */
    const val YALE_SMART = "yale_smart"

    /** Profile code for legacy/clean control: no sanitize, tracking off, clean_control token. */
    const val CLEAN_CONTROL_V1 = "clean_control_v1"

    /**
     * Whether this profile requires URL sanitization before the spoof run.
     */
    fun requiresUrlSanitize(profileCode: String): Boolean =
        profileCode == YALE_SMART

    /**
     * Tracking protection value for telemetry and extraction request.
     * "strict" for yale_smart, "off" otherwise.
     */
    fun trackingProtection(profileCode: String): String =
        if (profileCode == YALE_SMART) "strict" else "off"

    /**
     * Bootstrap token value for engine telemetry in the execution URL fragment.
     * Stored as-is in telemetry for analytics.
     */
    fun bootstrapTokenValue(profileCode: String): String =
        when (profileCode) {
            YALE_SMART -> "yale_smart"
            CLEAN_CONTROL_V1 -> "clean_control_v1"
            else -> "clean_control_v1"
        }
}
