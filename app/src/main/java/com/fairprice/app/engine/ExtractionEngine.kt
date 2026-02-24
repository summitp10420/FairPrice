package com.fairprice.app.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

interface ExtractionEngine {
    val currentSession: StateFlow<GeckoSession?>
    suspend fun loadAndExtract(url: String): Result<Int>
}

class GeckoExtractionEngine(context: Context) : ExtractionEngine {
    private val runtime: GeckoRuntime = GeckoRuntime.getDefault(context)
    private val _currentSession = MutableStateFlow<GeckoSession?>(null)
    override val currentSession: StateFlow<GeckoSession?> = _currentSession.asStateFlow()

    override suspend fun loadAndExtract(url: String): Result<Int> {
        Log.i("GeckoExtractionEngine", "Stub loadAndExtract invoked for URL: $url")
        _currentSession.value?.let { oldSession ->
            runCatching { oldSession.close() }
                .onFailure { throwable ->
                    Log.w("GeckoExtractionEngine", "Failed closing old GeckoSession before refresh.", throwable)
                }
        }

        val newSession = GeckoSession().apply {
            open(runtime)
        }
        _currentSession.value = newSession

        // Phase 4 will run extraction scripts in this active session.
        delay(2000)
        return Result.success(8999)
    }
}
