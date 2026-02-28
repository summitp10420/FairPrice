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
import kotlin.random.Random
import kotlinx.coroutines.delay

interface FairPriceRepository {
    suspend fun logPriceCheck(priceCheck: PriceCheck): Result<Unit>
    suspend fun logPriceCheckRun(priceCheck: PriceCheck, attempts: List<PriceCheckAttempt>): Result<Unit>
}

class FairPriceRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : FairPriceRepository {
    private val tag = "FairPriceRepository"

    override suspend fun logPriceCheck(priceCheck: PriceCheck): Result<Unit> {
        return logPriceCheckRun(priceCheck, attempts = emptyList())
    }

    override suspend fun logPriceCheckRun(
        priceCheck: PriceCheck,
        attempts: List<PriceCheckAttempt>,
    ): Result<Unit> {
        return runCatching {
            ensureAuthenticatedSession()
            insertSummaryWithFallback(priceCheck)
            if (attempts.isNotEmpty()) {
                insertAttemptsWithRetry(attempts)
            }
            Unit
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

    private suspend fun insertAttemptsWithRetry(attempts: List<PriceCheckAttempt>) {
        val maxAttempts = 3
        var attempt = 0
        var lastFailure: Throwable? = null

        while (attempt < maxAttempts) {
            try {
                supabaseClient.postgrest["price_check_attempts"].insert(attempts)
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

        Log.w(tag, "Attempt telemetry insert failed; continuing without attempt rows.", lastFailure)
    }

    private fun PriceCheck.toLegacyPayload(): PriceCheck {
        return copy(
            strategyName = null,
            attemptedConfigs = null,
            finalConfig = null,
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
}
