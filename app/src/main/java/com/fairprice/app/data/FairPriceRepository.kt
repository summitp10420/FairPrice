package com.fairprice.app.data

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
}

class FairPriceRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : FairPriceRepository {
    override suspend fun logPriceCheck(priceCheck: PriceCheck): Result<Unit> {
        return runCatching {
            ensureAuthenticatedSession()
            insertWithRetry(priceCheck)
            Unit
        }
    }

    private suspend fun ensureAuthenticatedSession() {
        val sessionStatus = supabaseClient.auth.sessionStatus.value
        if (sessionStatus is SessionStatus.Authenticated) return
        supabaseClient.auth.signInAnonymously()
    }

    private suspend fun insertWithRetry(priceCheck: PriceCheck) {
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

    private fun Throwable.isTransientNetworkFailure(): Boolean {
        return this is IOException || this is HttpRequestException
    }
}
