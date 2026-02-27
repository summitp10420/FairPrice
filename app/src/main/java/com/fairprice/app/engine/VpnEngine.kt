package com.fairprice.app.engine

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface VpnEngine {
    suspend fun connect(configStr: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>
}

class VpnPermissionRequiredException(val intent: Intent) : Exception("VPN Permission Required")

class WireguardVpnEngine(private val context: Context) : VpnEngine {
    private val backend: Backend by lazy { GoBackend(context) }
    private var currentTunnel: WgTunnel? = null

    override suspend fun connect(configStr: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val permissionIntent = VpnService.prepare(context)
                if (permissionIntent != null) {
                    throw VpnPermissionRequiredException(permissionIntent)
                }

                val parsedConfig = context.assets.open("vpn/$configStr").bufferedReader().use { reader ->
                    Config.parse(reader)
                }
                val tunnel = currentTunnel ?: WgTunnel("fairprice").also { currentTunnel = it }
                backend.setState(tunnel, Tunnel.State.UP, parsedConfig)
                awaitTunnelInternetReadiness()
                Unit
            }
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val tunnel = currentTunnel ?: return@runCatching Unit
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                currentTunnel = null
                Unit
            }
        }
    }

    private class WgTunnel(private val tunnelName: String) : Tunnel {
        @Volatile
        private var state: Tunnel.State = Tunnel.State.DOWN

        override fun getName(): String = tunnelName

        override fun onStateChange(newState: Tunnel.State) {
            state = newState
        }
    }

    private suspend fun awaitTunnelInternetReadiness() {
        repeat(READINESS_MAX_ATTEMPTS) { attempt ->
            if (probeInternetRoute()) {
                return
            }
            if (attempt < READINESS_MAX_ATTEMPTS - 1) {
                delay(READINESS_RETRY_DELAY_MS)
            }
        }
        throw IllegalStateException("VPN tunnel is up but internet route is not ready yet.")
    }

    private fun probeInternetRoute(): Boolean {
        val connection = (URL(READINESS_PROBE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = READINESS_CONNECT_TIMEOUT_MS
            readTimeout = READINESS_READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
        }
        return runCatching {
            connection.connect()
            val code = connection.responseCode
            code in 200..399
        }.getOrDefault(false).also {
            connection.disconnect()
        }
    }

    private companion object {
        private const val READINESS_PROBE_URL = "https://www.gstatic.com/generate_204"
        private const val READINESS_CONNECT_TIMEOUT_MS = 1500
        private const val READINESS_READ_TIMEOUT_MS = 1500
        private const val READINESS_RETRY_DELAY_MS = 300L
        private const val READINESS_MAX_ATTEMPTS = 10
    }
}
