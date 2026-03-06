package com.fairprice.app.engine

/**
 * Fallback-only mapping from profile codes (legacy / yale_smart) to behavior.
 * Used when the backend sends the old payload shape (only strategy_profile).
 * Execution uses flat booleans from StrategyResult when the new payload is present.
 */
object StrategyProfileBehavior {
    /** Profile code for Yale-style spoof: URL sanitize, strict tracking, canvas spoof. */
    const val YALE_SMART = "yale_smart"

    /** Profile code for legacy control: no sanitize, tracking off, no canvas spoof. */
    const val LEGACY = "legacy"

    fun requiresUrlSanitize(profileCode: String): Boolean =
        profileCode == YALE_SMART

    fun trackingProtection(profileCode: String): String =
        if (profileCode == YALE_SMART) "strict" else "off"

    fun bootstrapTokenValue(profileCode: String): String =
        when (profileCode) {
            YALE_SMART -> "yale_smart"
            LEGACY -> "legacy"
            else -> LEGACY
        }

    /** For old-payload backward compat: amnesia wipe required only for yale_smart. */
    fun amnesiaWipeRequired(profileCode: String): Boolean = profileCode == YALE_SMART

    /** For old-payload backward compat: strict tracking only for yale_smart. */
    fun strictTrackingProtection(profileCode: String): Boolean = profileCode == YALE_SMART

    /** For old-payload backward compat: canvas spoofing only for yale_smart. */
    fun canvasSpoofingActive(profileCode: String): Boolean = profileCode == YALE_SMART
}
