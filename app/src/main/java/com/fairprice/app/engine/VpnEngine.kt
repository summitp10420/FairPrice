package com.fairprice.app.engine

import android.util.Log

interface VpnEngine {
    suspend fun connect(configStr: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>
}

class WireguardVpnEngine : VpnEngine {
    override suspend fun connect(configStr: String): Result<Unit> {
        Log.i("WireguardVpnEngine", "Stub connect invoked with config placeholder.")
        // Phase 4+: WireGuard tunnel management must run in a bound Foreground Service.
        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        Log.i("WireguardVpnEngine", "Stub disconnect invoked.")
        // Phase 4+: Foreground Service teardown and tunnel cleanup will be implemented here.
        return Result.success(Unit)
    }
}
