package com.fairprice.app.engine

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Calls the Railway strategy engine API. Falls back to LocalStrategyFallback on timeout or error.
 */
class RailwayStrategyClient(
    private val httpClient: HttpClient,
    private val railwayEndpoint: String,
    private val localFallback: StrategyResolver,
) : StrategyResolver {

    companion object {
        private const val TAG = "RailwayStrategyClient"
        private const val NETWORK_TIMEOUT_MS = 3000L
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class StrategyRequestPayload(
        val domain: String,
        val detected_tactics: List<String>,
        val session_id: String,
    )

    override suspend fun resolveStrategy(url: String, baselineTactics: List<String>, shoppingSessionId: String): Result<StrategyResult> {
        val domain = normalizeDomain(url)

        if (railwayEndpoint.isBlank()) {
            Log.i(TAG, "Railway endpoint not configured, using local fallback")
            return localFallback.resolveStrategy(url, baselineTactics, shoppingSessionId)
        }

        return runCatching {
            withTimeout(NETWORK_TIMEOUT_MS) {
                Log.i(TAG, "Requesting strategy from Railway for domain: $domain (session: $shoppingSessionId)")
                val payload = StrategyRequestPayload(
                    domain = domain,
                    detected_tactics = baselineTactics,
                    session_id = shoppingSessionId,
                )
                val response = httpClient.post(railwayEndpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(payload))
                }
                val bodyText = response.bodyAsText()
                val result = json.decodeFromString<StrategyResult>(bodyText).normalized()
                Log.i(TAG, "Railway strategy received: ${result.effectiveStrategyCode()} via ${result.engineSelectionPolicy}")
                result
            }
        }.recoverCatching { throwable ->
            Log.w(TAG, "Railway unreachable or timed out, falling back to local", throwable)
            localFallback.resolveStrategy(url, baselineTactics, shoppingSessionId).getOrThrow()
        }
    }

    private fun normalizeDomain(url: String): String {
        val rawHost = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        val lowerHost = rawHost.trim().lowercase(Locale.US)
        if (lowerHost.isBlank()) return "unknown-domain"
        return lowerHost.removePrefix("www.").ifBlank { "unknown-domain" }
    }
}
