# Sprint 13: Implementation Plan

**Last Updated:** 2026-03-05  
**Goal:** Align the codebase with the strategy/engine mental model, then deploy the Railway backend.

**Terminology reference:** `DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD`

---

## Overview

This plan has two parts:

1. **Part A: Architecture Alignment** — Refactor the app to use the new terminology and structure (StrategyResolver, LocalStrategyFallback, Railway Strategy Client). No Railway deployment yet.
2. **Part B: Railway Deployment** — Deploy the Railway strategy engine, add RailwayStrategyClient, wire it up.

Part A must be complete before Part B. Part A can be done with no network calls; it's purely renaming and restructuring.

---

## Part A: Architecture Alignment (In-App Refactor)

### A1. Create the Strategy Resolver Abstraction

**Goal:** Introduce `StrategyResolver` as the interface the coordinator depends on. Keep the existing local logic as the implementation for now.

| Task | Details |
|------|---------|
| Create `StrategyResolver` interface | `suspend fun resolveStrategy(url: String, baselineTactics: List<String>): Result<StrategyResult>` |
| Rename `DefaultPricingStrategyEngine` → `LocalStrategyFallback` | Implements `StrategyResolver`. Same logic (domain + installationId bucketing, LEGACY vs YALE_SMART). |
| Rename `PricingStrategyEngine` | Either deprecate and migrate to `StrategyResolver`, or make `StrategyResolver` extend/alias `PricingStrategyEngine` for minimal change. Recommendation: Add `StrategyResolver`, have `LocalStrategyFallback` implement it, update coordinator to depend on `StrategyResolver`. Keep `PricingStrategyEngine` as a deprecated alias if needed for gradual migration. |
| Update coordinator | `DefaultPriceCheckCoordinator` depends on `StrategyResolver`, not `PricingStrategyEngine`. |
| Update MainActivity | Wire `strategyResolver: StrategyResolver = LocalStrategyFallback(...)`. |
| Update HomeViewModel / coordinator builder | Pass `strategyResolver` instead of `strategyEngine`. |
| Update tests | `DefaultPricingStrategyEngineTest` → `LocalStrategyFallbackTest`. `FakeStrategyEngine` in ViewModel tests → `FakeStrategyResolver`. |

**Files to touch:**
- `app/src/main/java/com/fairprice/app/engine/StrategyEngine.kt` (or new `StrategyResolver.kt`)
- `app/src/main/java/com/fairprice/app/coordinator/DefaultPriceCheckCoordinator.kt`
- `app/src/main/java/com/fairprice/app/viewmodel/HomeViewModel.kt`
- `app/src/main/java/com/fairprice/app/MainActivity.kt`
- `app/src/test/.../DefaultPricingStrategyEngineTest.kt` → `LocalStrategyFallbackTest.kt`
- `app/src/test/.../HomeViewModelTest.kt` (FakeStrategyEngine → FakeStrategyResolver)

**Outcome:** Coordinator and ViewModel depend on `StrategyResolver`. The only implementation is `LocalStrategyFallback`. No behavior change; naming and mental model aligned.

---

### A2. Fix LocalStrategyFallback Determinism

**Goal:** Ensure `LocalStrategyFallback` uses `domain|installationId` for bucketing (not `UUID.randomUUID()`). This fixes the known test failure.

| Task | Details |
|------|---------|
| Use domain + installationId for assignment key | `assignmentKey = "$domain|${installationIdProvider()}"` |
| Compute bucket from assignment key | `(assignmentKey.hashCode() and Int.MAX_VALUE) % BUCKET_MODULUS` |
| Update tests | Tests that assume domain/installation-based stability should now pass. |

**Outcome:** LocalStrategyFallback is deterministic and testable. Behavior matches what Railway will do.

---

### A3. Make StrategyResult Serializable (Prep for Railway)

**Goal:** `StrategyResult` must parse JSON from Railway. Add `@Serializable`, `proxyConfig`, and ensure `EngineProfile` is serializable.

| Task | Details |
|------|---------|
| Add `@Serializable` to `StrategyResult` and `EngineProfile` | kotlinx.serialization |
| Add `proxyConfig: JsonObject? = null` | Reserved for Sprint 14 residential proxies |
| Add Ktor ContentNegotiation deps | `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json` |
| Verify `wireguardConfig` compatibility | Keep for schema compatibility; leave empty |

**Outcome:** `StrategyResult` can be deserialized from Railway JSON. No functional change until Part B.

---

### A4. Update Documentation

| Task | Details |
|------|---------|
| Update `MASTER_ARCHITECTURE.MD` | Remove VPN references. Add Sprint 13 Railway section. Use StrategyResolver, LocalStrategyFallback terminology. |
| Update `CONTEXT_DUMP_FOR_NEW_CHAT.MD` | Replace strategy engine references with StrategyResolver, LocalStrategyFallback. |
| Update `STRATEGY_LEVERS.MD` | Add "Remote Strategy Resolution" lever; note deprecation of VPN levers for clear-net phase. |

**Outcome:** All docs reflect the new model.

---

## Part B: Railway Deployment

*Begin after Part A is merged and stable.*

### B1. Create Railway Backend Repository

| Task | Details |
|------|---------|
| New repo (or subdir) for Railway backend | Node.js/TypeScript, Express |
| Implement `POST /api/v1/strategy` | Accept `{ domain, detected_tactics, anonymous_bucket }`; return `StrategyResult` JSON |
| Replicate A/B split and WAF-override logic | Same logic as LocalStrategyFallback (deterministic, testable) |
| Deploy to Railway | Obtain production URL |

---

### B2. Add RailwayStrategyClient

| Task | Details |
|------|---------|
| Create `RailwayStrategyClient` | Implements `StrategyResolver`. Calls Railway API with Ktor HttpClient. |
| Compute `anonymous_bucket` client-side | `(domain|installationId).hashCode() % 100` — never send installationId to server |
| Timeout + fallback | 3s timeout; on failure, delegate to `LocalStrategyFallback` |
| Compose resolver in MainActivity | `strategyResolver = RailwayStrategyClient(..., localFallback = LocalStrategyFallback(...))` |

---

### B3. Configuration and Verification

| Task | Details |
|------|---------|
| Add `RAILWAY_STRATEGY_ENDPOINT` to BuildConfig | From `local.properties` or env |
| Verify telemetry | Ensure `strategyEngineName`, `engineSelectionPolicy` from Railway are logged |
| End-to-end test | Confirm app receives YALE_SMART or LEGACY from Railway, logs to Supabase |

---

## Dependency Order

```
A1 (StrategyResolver + LocalStrategyFallback rename)
  → A2 (Determinism fix)
  → A3 (StrategyResult serialization)
  → A4 (Docs)
  → Merge Part A
  → B1 (Railway backend)
  → B2 (RailwayStrategyClient)
  → B3 (Config, verification)
```

---

## Config / Environment

**Supabase:** Android uses `local.properties` (gitignored), not `.env`. Add:
```
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
```
Copy `local.properties.example` to `local.properties` and fill in values.

---

## Checklist for Part A (Before Railway)

- [x] `StrategyResolver` interface exists; coordinator depends on it
- [x] `LocalStrategyFallback` replaces `DefaultPricingStrategyEngine`; all references updated
- [x] `LocalStrategyFallback` uses domain+installationId for bucketing
- [x] `StrategyResult` is `@Serializable`; `proxyConfig` added
- [x] All tests pass (including renamed `LocalStrategyFallbackTest`)
- [x] DOCS updated (MASTER_ARCHITECTURE, CONTEXT_DUMP, STRATEGY_LEVERS)
- [x] No references to "DefaultPricingStrategyEngine" or "strategy engine on app" in docs
