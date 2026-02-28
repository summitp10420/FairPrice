package com.fairprice.app.engine

import android.content.Context

interface VpnRotationEngine {
    fun availableConfigs(): List<String>
    fun nextConfig(excludedConfigs: Set<String> = emptySet()): String?
    fun reportAttemptResult(config: String, success: Boolean)
}

class AssetVpnRotationEngine(
    context: Context,
    private val blockedConfigs: Set<String> = emptySet(),
    private val unhealthyFailureThreshold: Int = 2,
    private val cooldownMs: Long = 10 * 60 * 1000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    discoveredConfigsOverride: List<String>? = null,
) : VpnRotationEngine {
    private val configs: List<String> = (discoveredConfigsOverride ?: discoverConfigs(context))
        .filterNot { it in blockedConfigs }
        .sorted()
    private val healthByConfig: MutableMap<String, ConfigHealth> = mutableMapOf()
    private var nextIndex: Int = 0

    override fun availableConfigs(): List<String> = configs

    @Synchronized
    override fun nextConfig(excludedConfigs: Set<String>): String? {
        if (configs.isEmpty()) return null
        val now = nowMs()
        repeat(configs.size) {
            val index = nextIndex
            nextIndex = (nextIndex + 1) % configs.size
            val config = configs[index]
            if (config in excludedConfigs) return@repeat
            val health = healthByConfig.getOrPut(config) { ConfigHealth() }
            if (health.cooldownUntilMs > now) return@repeat
            return config
        }
        return null
    }

    @Synchronized
    override fun reportAttemptResult(config: String, success: Boolean) {
        val health = healthByConfig.getOrPut(config) { ConfigHealth() }
        if (success) {
            health.consecutiveFailures = 0
            health.cooldownUntilMs = 0L
            return
        }

        health.consecutiveFailures += 1
        if (health.consecutiveFailures >= unhealthyFailureThreshold) {
            health.cooldownUntilMs = nowMs() + cooldownMs
            health.consecutiveFailures = 0
        }
    }

    private fun discoverConfigs(context: Context): List<String> {
        val files = context.assets.list("vpn").orEmpty().asList()
        return files
            .filter { it.endsWith(".conf", ignoreCase = true) }
            .sorted()
    }

    private data class ConfigHealth(
        var consecutiveFailures: Int = 0,
        var cooldownUntilMs: Long = 0L,
    )
}
