package com.fairprice.app.engine

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
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
}
