package com.fairprice.app.data

import com.fairprice.app.data.models.PriceCheck
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

interface FairPriceRepository {
    suspend fun logPriceCheck(priceCheck: PriceCheck): Result<Unit>
}

class FairPriceRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : FairPriceRepository {
    override suspend fun logPriceCheck(priceCheck: PriceCheck): Result<Unit> {
        return runCatching {
            supabaseClient.postgrest["price_checks"].insert(priceCheck)
            Unit
        }
    }
}
