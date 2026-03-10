# FairPrice Context Dump for New Chat

**Branch:** `main` (Sprint 14 merged)  
**Last Updated:** 2026-03-06  
**Purpose:** Copy-paste this into a new chat to bootstrap context quickly.

---

## Project Overview

**FairPrice** is an Android app that defeats dynamic/personalized pricing by running multi-stage price extraction and comparing baseline vs spoofed prices. Runs on **clear-net** (VPN removed in Phase 12). Telemetry is logged to Supabase. The strategy engine runs on Railway and uses a tactic registry + tiered profiles.

**Tech Stack:** Kotlin, Jetpack Compose, MVVM, GeckoView (no WebView), Supabase, Railway (strategy engine). Future: Vercel for analytics dashboard.

---

## Architecture (Sprint 14)

### Entry & Composition

- **MainActivity** – Creates `repository`, `extractionEngine`, `strategyResolver`, `homeViewModel`. Wires Compose UI. Handles `ACTION_SEND` for shared URLs.
- **HomeViewModel** – Holds `HomeUiState` (includes `adminEngineOverride`), builds `DefaultPriceCheckCoordinator`, forwards user actions. Exposes `uiState` via `StateFlow`.

### Coordinator Pipeline

- **PriceCheckCoordinator** – `state`, `startPriceCheck`, `onEnterShoppingMode`, etc.
- **DefaultPriceCheckCoordinator** – Canonicalize URL → `preSpoofStageRunner.run` → `strategyResolver.resolveStrategy` → `resolveActiveStrategy` (admin override) → `spoofAttemptRunner.execute` → log telemetry.
- **PreSpoofStageRunner** – Sniffer → (if fail/WAF) clean-control fallback → (20% sample) shadow clean-control.
- **SpoofAttemptRunner** – Up to 2 clear-net spoof attempts; passes `selection_mode` into telemetry.

### Backend Strategy Engine (Railway)

- **In-memory cache:** `strategy_profiles` and `tactic_registry` from Supabase; 60s background refresh. Zero DB calls on critical path.
- **Profile selection:** Merge `required_countermeasures` from detected tactics → select lowest-tier qualifying profile.
- **Epsilon-greedy (75/25):** bucket 0–74 = exploit; bucket 75–99 = explore (tier = `bucket % 4`).
- **Cold-boot fallback:** tier 2 (`amnesia_standard`).

### Admin Engine Override

- When `isAdmin`, admins can force a profile: AUTO, T0 (`clean_baseline`), T1 (`shield_basic`), T2 (`amnesia_standard`), T3 (`stealth_max`).
- `resolveActiveStrategy` in coordinator overrides engine result when a non-AUTO override is selected.

### Engines & Telemetry

- **ExtractionEngine** – GeckoView WebExtension; `loadAndExtract` → `Result<ExtractionResult>`.
- **StrategyResolver** – `RailwayStrategyClient` + `LocalStrategyFallback`. Returns `StrategyResult` with `selection_mode`, `strategy_profile`, flat levers.
- **TelemetryAssembler** – Builds `PriceCheck` (including `selectionMode`), `PriceCheckAttempt`, `SummaryData`.

---

## Important Constants

- **Phases:** `sniffer`, `clean_control`, `spoof`
- **Clean control modes:** `none`, `fallback`, `shadow`
- **Network:** `clear_net` (telemetry), `"Clear Net"` (display)
- **URL tokens:** sniffer=`sniffer_intel`, clean-control=`clean_control_intel`, spoof=`<strategy_profile>` (e.g. `clean_baseline`, `shield_basic`, `amnesia_standard`, `stealth_max`)
- **Strategy profiles (tiers 0–3):** `clean_baseline`, `shield_basic`, `amnesia_standard`, `stealth_max`

---

## Key Paths

| Area | Path |
|------|------|
| Coordinators | `app/src/main/java/com/fairprice/app/coordinator/` |
| Strategy & extraction | `app/src/main/java/com/fairprice/app/engine/` |
| Backend | `backend/src/index.ts` |
| Docs | `DOCS/MASTER_ARCHITECTURE.MD`, `DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD`, `DOCS/STRATEGY_LEVERS.MD`, `DOCS/migrations/sprint14_tactic_registry.sql` |

---

## Related Documents

- `DOCS/MASTER_ARCHITECTURE.MD` — Full architecture, data flow, DB schema
- `DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD` — Tiered profiles, tactic registry, selection_mode
- `DOCS/STRATEGY_LEVERS.MD` — Strategy levers and policy knobs
- `DOCS/migrations/sprint14_tactic_registry.sql` — Tactic registry, tiered profiles, selection_mode column, strategy_win_rates view
