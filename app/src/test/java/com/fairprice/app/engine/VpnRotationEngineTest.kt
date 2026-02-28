package com.fairprice.app.engine

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VpnRotationEngineTest {
    @Test
    fun availableConfigs_discoversAndSortsAssetConfigs() {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        val engine = AssetVpnRotationEngine(
            context = context,
            discoveredConfigsOverride = listOf(
                "z.conf",
                "a.conf",
                "m.conf",
            ),
        )

        val configs = engine.availableConfigs()
        assertEquals(listOf("a.conf", "m.conf", "z.conf"), configs)
    }

    @Test
    fun unhealthyConfig_entersCooldownAndRecoversAfterWindow() {
        val context = RuntimeEnvironment.getApplication().applicationContext as Context
        var now = 1_000L
        val engine = AssetVpnRotationEngine(
            context = context,
            unhealthyFailureThreshold = 2,
            cooldownMs = 10 * 60 * 1000L,
            nowMs = { now },
            discoveredConfigsOverride = listOf("a.conf", "b.conf"),
        )

        val configs = engine.availableConfigs()
        val first = engine.nextConfig() ?: return
        engine.reportAttemptResult(first, success = false)
        engine.reportAttemptResult(first, success = false)

        if (configs.size > 1) {
            val excludedOthers = configs.filterNot { it == first }.toSet()
            val duringCooldown = engine.nextConfig(excludedOthers)
            assertEquals(null, duringCooldown)

            now += 10 * 60 * 1000L + 1L
            val afterCooldown = engine.nextConfig(excludedOthers)
            assertEquals(first, afterCooldown)
        }
    }
}
