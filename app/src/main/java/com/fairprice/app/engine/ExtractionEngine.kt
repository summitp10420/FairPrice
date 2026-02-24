package com.fairprice.app.engine

import android.content.Context
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

interface ExtractionEngine {
    val currentSession: StateFlow<GeckoSession?>
    suspend fun loadAndExtract(url: String): Result<Int>
}

class GeckoExtractionEngine(context: Context) : ExtractionEngine {
    private val runtime: GeckoRuntime = GeckoRuntime.getDefault(context)
    private val _currentSession = MutableStateFlow<GeckoSession?>(null)
    override val currentSession: StateFlow<GeckoSession?> = _currentSession.asStateFlow()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingLock = Any()
    private var pendingExtraction: PendingExtraction? = null

    init {
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_RESOURCE_PATH, EXTENSION_ID)
            .accept(
                { extension ->
                    val resolvedExtension = extension ?: run {
                        Log.e("GeckoExtractionEngine", "Built-in extractor extension resolved as null.")
                        return@accept
                    }
                    resolvedExtension.setMessageDelegate(
                        messageDelegate,
                        EXTENSION_ID,
                    )
                    Log.i("GeckoExtractionEngine", "Built-in extractor extension registered.")
                },
                { throwable ->
                    Log.e("GeckoExtractionEngine", "Failed to register built-in extractor extension.", throwable)
                },
            )
    }

    override suspend fun loadAndExtract(url: String): Result<Int> = runCatching {
        val session = createFreshSession()
        withTimeout(EXTRACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                setPendingExtraction(session, continuation)
                continuation.invokeOnCancellation {
                    clearPendingExtraction(continuation)
                }

                try {
                    session.load(GeckoSession.Loader().uri(url))
                } catch (throwable: Throwable) {
                    clearPendingExtraction(continuation)
                    if (continuation.isActive) {
                        continuation.cancel(
                            CancellationException("Failed to load extraction URL: $url", throwable),
                        )
                    }
                }
            }
        }
    }

    private fun createFreshSession(): GeckoSession {
        _currentSession.value?.let { oldSession ->
            runCatching { oldSession.close() }
                .onFailure { throwable ->
                    Log.w("GeckoExtractionEngine", "Failed closing old GeckoSession before refresh.", throwable)
                }
        }

        val newSession = GeckoSession().apply { open(runtime) }
        _currentSession.value = newSession
        return newSession
    }

    private fun setPendingExtraction(
        session: GeckoSession,
        continuation: CancellableContinuation<Int>,
    ) {
        synchronized(pendingLock) {
            pendingExtraction?.continuation?.cancel(
                CancellationException("Replaced by a new extraction request."),
            )
            pendingExtraction = PendingExtraction(session, continuation)
        }
    }

    private fun clearPendingExtraction(continuation: CancellableContinuation<Int>) {
        synchronized(pendingLock) {
            if (pendingExtraction?.continuation === continuation) {
                pendingExtraction = null
            }
        }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            val payload = parsePriceExtractMessage(message) ?: return null
            if (payload.type != PRICE_EXTRACT_TYPE) return null

            mainScope.launch {
                resumePendingExtraction(sender.session, payload.priceCents)
            }
            return null
        }
    }

    private fun resumePendingExtraction(senderSession: GeckoSession?, priceCents: Int) {
        val pending = synchronized(pendingLock) {
            val current = pendingExtraction ?: return
            if (senderSession != null && senderSession !== current.session) return
            pendingExtraction = null
            current
        }
        if (pending.continuation.isActive) {
            pending.continuation.resume(priceCents)
        }
    }

    private fun parsePriceExtractMessage(message: Any): PriceExtractMessage? {
        return when (message) {
            is JSONObject -> {
                val type = message.optString("type", "")
                if (!message.has("priceCents")) return null
                val priceCents = message.optInt("priceCents", -1)
                if (priceCents < 0) return null
                PriceExtractMessage(type = type, priceCents = priceCents)
            }
            is Map<*, *> -> {
                val type = message["type"] as? String ?: return null
                val centsAny = message["priceCents"] ?: return null
                val priceCents = when (centsAny) {
                    is Int -> centsAny
                    is Number -> centsAny.toInt()
                    else -> return null
                }
                if (priceCents < 0) return null
                PriceExtractMessage(type = type, priceCents = priceCents)
            }
            else -> null
        }
    }

    private data class PendingExtraction(
        val session: GeckoSession,
        val continuation: CancellableContinuation<Int>,
    )

    private data class PriceExtractMessage(
        val type: String,
        val priceCents: Int,
    )

    private companion object {
        private const val EXTENSION_ID = "extractor@fairprice.com"
        private const val EXTENSION_RESOURCE_PATH = "resource://android/assets/extension/"
        private const val PRICE_EXTRACT_TYPE = "PRICE_EXTRACT"
        private const val EXTRACTION_TIMEOUT_MS = 15_000L
    }
}
