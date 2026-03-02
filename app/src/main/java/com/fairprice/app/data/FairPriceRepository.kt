package com.fairprice.app.data

import android.util.Log
import com.fairprice.app.data.models.PriceCheckAttempt
import com.fairprice.app.data.models.PriceCheck
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.postgrest
import java.io.IOException
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

data class RunLogResult(
    val attemptsInserted: Boolean,
    val attemptInsertError: String? = null,
    val retailerIntelInserted: Boolean = false,
    val retailerIntelError: String? = null,
)

interface FairPriceRepository {
    suspend fun logPriceCheck(priceCheck: PriceCheck): Result<RunLogResult>
    suspend fun logPriceCheckRun(priceCheck: PriceCheck, attempts: List<PriceCheckAttempt>): Result<RunLogResult>
    suspend fun fetchLifetimePotentialSavingsCents(): Result<Int>
}

class FairPriceRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : FairPriceRepository {
    private val tag = "FairPriceRepository"

    override suspend fun logPriceCheck(priceCheck: PriceCheck): Result<RunLogResult> {
        return logPriceCheckRun(priceCheck, attempts = emptyList())
    }

    override suspend fun logPriceCheckRun(
        priceCheck: PriceCheck,
        attempts: List<PriceCheckAttempt>,
    ): Result<RunLogResult> {
        return runCatching {
            ensureAuthenticatedSession()
            val attemptInsert = if (attempts.isNotEmpty()) {
                insertAttemptsWithRetry(attempts)
            } else {
                Result.success(Unit)
            }
            val summaryPayload = if (attemptInsert.isFailure) {
                priceCheck.copy(outcome = "partial_log_failure")
            } else {
                priceCheck
            }
            insertSummaryWithFallback(summaryPayload)
            val retailerIntel = insertRetailerIntel(priceCheck)
            RunLogResult(
                attemptsInserted = attemptInsert.isSuccess,
                attemptInsertError = attemptInsert.exceptionOrNull()?.message,
                retailerIntelInserted = retailerIntel.isSuccess,
                retailerIntelError = retailerIntel.exceptionOrNull()?.message,
            )
        }
    }

    override suspend fun fetchLifetimePotentialSavingsCents(): Result<Int> {
        return runCatching {
            ensureAuthenticatedSession()
            val rows = supabaseClient.postgrest["price_checks"]
                .select()
                .decodeList<PriceCheckSavingsRow>()

            rows.sumOf { row ->
                val dirtyBaseline = row.dirtyBaselinePriceCents ?: return@sumOf 0
                val found = row.foundPriceCents ?: return@sumOf 0
                (dirtyBaseline - found).coerceAtLeast(0)
            }
        }
    }

    private suspend fun ensureAuthenticatedSession() {
        supabaseClient.auth.awaitInitialization()
        val sessionStatus = supabaseClient.auth.sessionStatus.value
        if (sessionStatus is SessionStatus.Authenticated) return
        supabaseClient.auth.signInAnonymously()
    }

    private suspend fun insertSummaryWithFallback(priceCheck: PriceCheck) {
        runCatching {
            insertSummaryWithRetry(priceCheck)
        }.onFailure { throwable ->
            // Compatibility guard: if schema columns are behind, keep core logging alive.
            Log.w(tag, "Summary insert with Phase 8 columns failed; retrying with legacy payload.", throwable)
            insertSummaryWithRetry(priceCheck.toLegacyPayload())
        }.getOrThrow()
    }

    private suspend fun insertSummaryWithRetry(priceCheck: PriceCheck) {
        val maxAttempts = 3
        var attempt = 0
        var lastFailure: Throwable? = null

        while (attempt < maxAttempts) {
            try {
                supabaseClient.postgrest["price_checks"].insert(priceCheck)
                return
            } catch (throwable: Throwable) {
                lastFailure = throwable
                attempt += 1
                val shouldRetry = attempt < maxAttempts && throwable.isTransientNetworkFailure()
                if (!shouldRetry) break
                val exponentialBackoffMs = 300L * (1L shl (attempt - 1))
                val jitterMs = Random.nextLong(0L, 250L)
                delay(exponentialBackoffMs + jitterMs)
            }
        }

        throw lastFailure ?: IllegalStateException("Price check insert failed without a throwable.")
    }

    private suspend fun insertAttemptsWithRetry(attempts: List<PriceCheckAttempt>): Result<Unit> {
        val maxAttempts = 3
        var attempt = 0
        var lastFailure: Throwable? = null

        while (attempt < maxAttempts) {
            try {
                supabaseClient.postgrest["price_check_attempts"].insert(attempts)
                return Result.success(Unit)
            } catch (throwable: Throwable) {
                lastFailure = throwable
                attempt += 1
                val shouldRetry = attempt < maxAttempts && throwable.isTransientNetworkFailure()
                if (!shouldRetry) break
                val exponentialBackoffMs = 300L * (1L shl (attempt - 1))
                val jitterMs = Random.nextLong(0L, 250L)
                delay(exponentialBackoffMs + jitterMs)
            }
        }

        Log.w(tag, "Attempt telemetry insert failed; continuing without attempt rows.", lastFailure)
        return Result.failure(lastFailure ?: IllegalStateException("Attempt telemetry insert failed"))
    }

    private suspend fun insertRetailerIntel(priceCheck: PriceCheck): Result<Unit> {
        return runCatching<Unit> {
            val now = Instant.now().toString()
            val retailer = RetailerRow(
                domain = priceCheck.domain,
                firstSeenAt = now,
                lastSeenAt = now,
                activeTracking = true,
            )
            runCatching {
                supabaseClient.postgrest["retailers"].insert(retailer)
            }.onFailure {
                // Existing domain conflict should refresh recency without mutating first_seen_at.
                val recencyRefresh = runCatching {
                    updateRetailerRecency(
                        domain = priceCheck.domain,
                        lastSeenAt = now,
                    )
                }
                if (recencyRefresh.isFailure) {
                    Log.w(tag, "Retailer insert/update failed; continuing.", recencyRefresh.exceptionOrNull())
                }
            }

            val tactics = extractDetectedTactics(priceCheck)
            if (tactics.isEmpty()) return@runCatching Unit
            val strategyRows = tactics.map { tactic ->
                RetailerStrategyRow(
                    retailerDomain = priceCheck.domain,
                    tactic = tactic,
                    observedAt = now,
                    sourcePhase = "baseline",
                )
            }
            supabaseClient.postgrest["retailer_strategies"].insert(strategyRows)
            Unit
        }.onFailure { throwable ->
            Log.w(tag, "Retailer intel insert failed; continuing without retailer rows.", throwable)
        }
    }

    private fun PriceCheck.toLegacyPayload(): PriceCheck {
        return copy(
            strategyName = null,
            attemptedConfigs = null,
            finalConfig = null,
            finalConfigSource = null,
            finalConfigProvider = null,
            retryCount = 0,
            outcome = null,
            degraded = null,
            baselineSuccess = null,
            spoofSuccess = null,
            dirtyBaselinePriceCents = null,
        )
    }

    private fun Throwable.isTransientNetworkFailure(): Boolean {
        return this is IOException || this is HttpRequestException
    }

    @Serializable
    private data class PriceCheckSavingsRow(
        @SerialName("dirty_baseline_price_cents")
        val dirtyBaselinePriceCents: Int? = null,
        @SerialName("found_price_cents")
        val foundPriceCents: Int? = null,
    )

    private fun extractDetectedTactics(priceCheck: PriceCheck): List<String> {
        val value = priceCheck.rawExtractionData["detected_tactics"] as? JsonArray ?: return emptyList()
        return value.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
    }

    private suspend fun updateRetailerRecency(
        domain: String,
        lastSeenAt: String,
    ) {
        supabaseClient.postgrest["retailers"]
            .update(
                RetailerRecencyUpdate(
                    lastSeenAt = lastSeenAt,
                    activeTracking = true,
                ),
            ) {
                filter {
                    eq("domain", domain)
                }
            }
    }

    @Serializable
    private data class RetailerRow(
        val domain: String,
        @SerialName("first_seen_at")
        val firstSeenAt: String,
        @SerialName("last_seen_at")
        val lastSeenAt: String,
        @SerialName("active_tracking")
        val activeTracking: Boolean,
    )

    @Serializable
    private data class RetailerStrategyRow(
        @SerialName("retailer_domain")
        val retailerDomain: String,
        val tactic: String,
        @SerialName("observed_at")
        val observedAt: String,
        @SerialName("source_phase")
        val sourcePhase: String,
    )

    @Serializable
    private data class RetailerRecencyUpdate(
        @SerialName("last_seen_at")
        val lastSeenAt: String,
        @SerialName("active_tracking")
        val activeTracking: Boolean,
    )
}
