package com.fairprice.app.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

interface ExtractionEngine {
    suspend fun loadAndExtract(url: String): Result<Int>
}

class GeckoExtractionEngine(context: Context) : ExtractionEngine {
    private val runtime: GeckoRuntime = GeckoRuntime.getDefault(context)
    private val session: GeckoSession = GeckoSession().apply {
        open(runtime)
    }

    override suspend fun loadAndExtract(url: String): Result<Int> {
        Log.i("GeckoExtractionEngine", "Stub loadAndExtract invoked for URL: $url")
        delay(2000)
        return Result.success(8999)
    }
}
