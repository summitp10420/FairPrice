package com.fairprice.app.engine

import android.content.Context
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
    suspend fun loadAndExtract(url: String): Result<ExtractionResult>
}

data class ExtractionResult(
    val priceCents: Int,
    val tactics: List<String>,
    val debugExtractionPath: String? = null,
)

class GeckoExtractionEngine(context: Context) : ExtractionEngine {
    private val tag = "GeckoExtractionEngine"
    private val runtime: GeckoRuntime = GeckoRuntime.getDefault(context)
    private val _currentSession = MutableStateFlow<GeckoSession?>(null)
    override val currentSession: StateFlow<GeckoSession?> = _currentSession.asStateFlow()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingLock = Any()
    private var pendingExtraction: PendingExtraction? = null

    init {
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_RESOURCE_PATH, EXTENSION_ID).accept(
            { extension ->
                if (extension == null) {
                    Log.e(tag, "Built-in extractor extension resolved as null during warmup.")
                } else {
                    Log.i(tag, "Built-in extractor extension warmup succeeded.")
                }
            },
            { throwable ->
                Log.e(tag, "Failed warmup for built-in extractor extension.", throwable)
            },
        )
    }

    override suspend fun loadAndExtract(url: String): Result<ExtractionResult> = runCatching {
        Log.i(tag, "Starting loadAndExtract for URL: $url")
        val extension = awaitBuiltInExtension()
        val sessionSwap = createFreshSession()
        val session = sessionSwap.newSession
        attachDelegate(extension, session)

        withTimeout(EXTRACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                setPendingExtraction(session, continuation)
                continuation.invokeOnCancellation {
                    clearPendingExtraction(continuation)
                    Log.w(tag, "Extraction continuation cancelled for URL: $url")
                }

                try {
                    session.load(GeckoSession.Loader().uri(url))
                    Log.i(
                        tag,
                        "Session load started for URL: $url (session=${session.hashCode()})",
                    )
                    retireOldSession(sessionSwap.oldSession)
                } catch (throwable: Throwable) {
                    retireOldSession(sessionSwap.oldSession)
                    clearPendingExtraction(continuation)
                    if (continuation.isActive) {
                        continuation.cancel(
                            CancellationException("Failed to load extraction URL: $url", throwable),
                        )
                    }
                }
            }
        }
    }.onFailure { throwable ->
        when (throwable) {
            is TimeoutCancellationException -> {
                Log.e(tag, "Extraction timed out after $EXTRACTION_TIMEOUT_MS ms.")
            }
            else -> {
                Log.e(tag, "Extraction failed.", throwable)
            }
        }
    }

    private suspend fun awaitBuiltInExtension(): WebExtension {
        return suspendCancellableCoroutine { continuation ->
            runtime.webExtensionController.ensureBuiltIn(EXTENSION_RESOURCE_PATH, EXTENSION_ID).accept(
                { extension ->
                    val resolvedExtension = extension ?: run {
                        if (continuation.isActive) {
                            continuation.cancel(
                                CancellationException("Built-in extractor extension resolved as null."),
                            )
                        }
                        return@accept
                    }
                    Log.i(tag, "Built-in extractor extension ready.")
                    continuation.resume(resolvedExtension)
                },
                { throwable ->
                    if (continuation.isActive) {
                        continuation.cancel(
                            CancellationException("Failed ensuring built-in extractor extension.", throwable),
                        )
                    }
                },
            )
        }
    }

    private fun attachDelegate(extension: WebExtension, session: GeckoSession) {
        extension.setMessageDelegate(
            messageDelegate,
            NATIVE_APP_CHANNEL,
        )
        session.webExtensionController.setMessageDelegate(
            extension,
            messageDelegate,
            NATIVE_APP_CHANNEL,
        )
        Log.i(
            tag,
            "Message delegate attached for extractor native app channel (session=${session.hashCode()})",
        )
    }

    private fun createFreshSession(): SessionSwap {
        val oldSession = _currentSession.value
        val newSession = GeckoSession().apply { open(runtime) }
        _currentSession.value = newSession
        Log.i(
            tag,
            "Opened fresh GeckoSession (new=${newSession.hashCode()}, old=${oldSession?.hashCode()})",
        )
        return SessionSwap(newSession = newSession, oldSession = oldSession)
    }

    private fun retireOldSession(oldSession: GeckoSession?) {
        if (oldSession == null) return
        runCatching { oldSession.close() }
            .onSuccess {
                Log.i(tag, "Retired previous GeckoSession (old=${oldSession.hashCode()})")
            }
            .onFailure { throwable ->
                Log.w(tag, "Failed closing previous GeckoSession after refresh.", throwable)
            }
    }

    private fun setPendingExtraction(
        session: GeckoSession,
        continuation: CancellableContinuation<ExtractionResult>,
    ) {
        synchronized(pendingLock) {
            pendingExtraction?.continuation?.cancel(
                CancellationException("Replaced by a new extraction request."),
            )
            pendingExtraction = PendingExtraction(session, continuation)
        }
    }

    private fun clearPendingExtraction(continuation: CancellableContinuation<ExtractionResult>) {
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
            if (nativeApp != NATIVE_APP_CHANNEL) {
                Log.i(tag, "Ignoring message for unexpected native app channel: $nativeApp")
                return null
            }
            val payload = parsePriceExtractMessage(message)
            if (payload == null) {
                Log.w(tag, "Ignoring malformed extension message: $message")
                return null
            }
            if (payload.type != PRICE_EXTRACT_TYPE) {
                Log.i(tag, "Ignoring non-extraction message type: ${payload.type}")
                return null
            }
            Log.i(tag, "Received PRICE_EXTRACT message with ${payload.priceCents} cents.")

            mainScope.launch {
                resumePendingExtraction(
                    sender.session,
                    ExtractionResult(
                        priceCents = payload.priceCents,
                        tactics = payload.detectedTactics,
                        debugExtractionPath = payload.debugExtractionPath,
                    ),
                )
            }
            return null
        }
    }

    private fun resumePendingExtraction(senderSession: GeckoSession?, result: ExtractionResult) {
        val pending = synchronized(pendingLock) {
            val current = pendingExtraction ?: return
            if (senderSession != null && senderSession !== current.session) {
                Log.w(tag, "Ignoring message from non-active session.")
                return
            }
            pendingExtraction = null
            current
        }
        if (pending.continuation.isActive) {
            pending.continuation.resume(result)
            Log.i(tag, "Extraction continuation resumed successfully.")
        }
    }

    private fun parsePriceExtractMessage(message: Any): PriceExtractMessage? {
        return when (message) {
            is JSONObject -> {
                val type = message.optString("type", "")
                if (!message.has("priceCents")) return null
                val priceCents = message.optInt("priceCents", -1)
                if (priceCents < 0) return null
                val detectedTactics = parseTacticsFromJson(message)
                PriceExtractMessage(
                    type = type,
                    priceCents = priceCents,
                    detectedTactics = detectedTactics,
                    debugExtractionPath = message.optString("debugExtractionPath", "").ifBlank { null },
                )
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
                val detectedTactics = parseTacticsFromMap(message)
                PriceExtractMessage(
                    type = type,
                    priceCents = priceCents,
                    detectedTactics = detectedTactics,
                    debugExtractionPath = (message["debugExtractionPath"] as? String)?.ifBlank { null },
                )
            }
            else -> null
        }
    }

    private fun parseTacticsFromJson(message: JSONObject): List<String> {
        if (!message.has("detectedTactics")) return emptyList()
        val rawTactics = message.optJSONArray("detectedTactics") ?: return emptyList()
        val tactics = mutableListOf<String>()
        for (index in 0 until rawTactics.length()) {
            val tactic = rawTactics.optString(index, "").trim()
            if (tactic.isNotEmpty()) {
                tactics += tactic
            }
        }
        return tactics
    }

    private fun parseTacticsFromMap(message: Map<*, *>): List<String> {
        val raw = message["detectedTactics"] ?: return emptyList()
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            (item as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private data class PendingExtraction(
        val session: GeckoSession,
        val continuation: CancellableContinuation<ExtractionResult>,
    )

    private data class SessionSwap(
        val newSession: GeckoSession,
        val oldSession: GeckoSession?,
    )

    private data class PriceExtractMessage(
        val type: String,
        val priceCents: Int,
        val detectedTactics: List<String>,
        val debugExtractionPath: String? = null,
    )

    private companion object {
        private const val EXTENSION_ID = "extractor@fairprice.com"
        private const val NATIVE_APP_CHANNEL = "com.fairprice.extractor"
        private const val EXTENSION_RESOURCE_PATH = "resource://android/assets/extension/"
        private const val PRICE_EXTRACT_TYPE = "PRICE_EXTRACT"
        private const val EXTRACTION_TIMEOUT_MS = 15_000L
    }
}
