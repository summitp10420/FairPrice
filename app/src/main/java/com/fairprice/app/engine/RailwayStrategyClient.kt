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
    private val installationIdProvider: () -> String,
) : StrategyResolver {

    companion object {
        private const val TAG = "RailwayStrategyClient"
        private const val NETWORK_TIMEOUT_MS = 3000L
        private const val BUCKET_MODULUS = 100
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class StrategyRequestPayload(
        val domain: String,
        val detected_tactics: List<String>,
        val anonymous_bucket: Int,
    )

    override suspend fun resolveStrategy(url: String, baselineTactics: List<String>): Result<StrategyResult> {
        val domain = normalizeDomain(url)
        val assignmentKey = "$domain|${installationIdProvider()}"
        val anonymousBucket = (assignmentKey.hashCode() and Int.MAX_VALUE) % BUCKET_MODULUS

        if (railwayEndpoint.isBlank()) {
            Log.i(TAG, "Railway endpoint not configured, using local fallback")
            return localFallback.resolveStrategy(url, baselineTactics)
        }

        return runCatching {
            withTimeout(NETWORK_TIMEOUT_MS) {
                Log.i(TAG, "Requesting strategy from Railway for domain: $domain (bucket: $anonymousBucket)")
                val payload = StrategyRequestPayload(
                    domain = domain,
                    detected_tactics = baselineTactics,
                    anonymous_bucket = anonymousBucket,
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
            localFallback.resolveStrategy(url, baselineTactics).getOrThrow()
        }
    }

    private fun normalizeDomain(url: String): String {
        val rawHost = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        val lowerHost = rawHost.trim().lowercase(Locale.US)
        if (lowerHost.isBlank()) return "unknown-domain"
        return lowerHost.removePrefix("www.").ifBlank { "unknown-domain" }
    }
}
