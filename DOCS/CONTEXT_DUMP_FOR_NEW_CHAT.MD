# FairPrice Context Dump for New Chat

**Branch:** `main` (Phase 12 merged)  
**Last Updated:** 2026-03-05  
**Purpose:** Copy-paste this into a new chat to bootstrap context quickly.

---

## Project Overview

**FairPrice** is an Android app that defeats dynamic/personalized pricing by running multi-stage price extraction and comparing baseline vs spoofed prices. Currently runs on **clear-net** (VPN removed in Phase 12). Telemetry is logged to Supabase.

**Tech Stack:** Kotlin, Jetpack Compose, MVVM, GeckoView (no WebView), Supabase. Future: Railway for strategy/config logic, Vercel for analytics dashboard.

---

## Architecture (Post Phase 12)

### Entry & Composition

- **MainActivity** – Creates `repository`, `extractionEngine`, `strategyResolver`, `homeViewModel`. Wires Compose UI. Handles `ACTION_SEND` for shared URLs.
- **HomeViewModel** – Holds `HomeUiState`, builds `DefaultPriceCheckCoordinator` (with `PreSpoofStageRunner`, `SpoofAttemptRunner`, `TelemetryAssembler`), forwards user actions. Exposes `uiState` via `StateFlow`.

### Coordinator Pipeline

- **PriceCheckCoordinator** (interface) – `state`, `startPriceCheck`, `onEnterShoppingMode`, `onBackToApp`, `onCloseShoppingSession`, `onAppClosing`.
- **DefaultPriceCheckCoordinator** – Runs flow: canonicalize URL → `preSpoofStageRunner.run` → `spoofAttemptRunner.execute` → log telemetry → update state.
- **PreSpoofStageRunner** – Sniffer pass → (if fail/WAF) clean-control fallback → (if sniffer succeeds, 20% sample) shadow clean-control. Returns `Success` or `TerminalFailure`.
- **SpoofAttemptRunner** – Up to 2 clear-net spoof attempts via `extractionEngine.loadAndExtract`. Uses `"clear_net"` for telemetry; handles WAF blocks; measures latency and passes into telemetry.

### Engines

- **ExtractionEngine** – GeckoView WebExtension; `loadAndExtract(url, ExtractionRequest)` → `Result<ExtractionResult>`.
- **StrategyResolver** – Returns `StrategyResult` for `(url, baselineTactics)`. The strategy engine lives on Railway; the app uses `RailwayStrategyClient` (calls Railway) and `LocalStrategyFallback` (when Railway unreachable). See `DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD`.

### Telemetry & Utils

- **TelemetryAssembler** – Builds `PriceCheck`, `PriceCheckAttempt`, `SummaryData`. Uses `CLEAR_NET` / `CLEAR_NET_DISPLAY` for config-related values.
- **EngineBootstrapUtils** – Shared: `appendEngineBootstrapToken`, `ExtractionResult.isWafBlockDetected()`. Strategy profile is an open string from the backend; behavior mapping in `StrategyProfileBehavior`.

### Data Flow Summary

```
URL share/paste → HomeViewModel.startPriceCheck
  → DefaultPriceCheckCoordinator.startPriceCheck
    → PreSpoofStageRunner (sniffer → optional clean-control fallback/shadow)
    → StrategyResolver.resolveStrategy
    → SpoofAttemptRunner (clear-net loadAndExtract up to 2 attempts)
    → FairPriceRepository.logPriceCheckRun
  → state update → HomeScreen
```

---

## Key Paths

| Area | Path |
|------|------|
| Coordinators & runners | `app/src/main/java/com/fairprice/app/coordinator/` |
| Strategy & extraction | `app/src/main/java/com/fairprice/app/engine/` |
| ViewModel | `app/src/main/java/com/fairprice/app/viewmodel/HomeViewModel.kt` |
| Repository / Supabase | `app/src/main/java/com/fairprice/app/data/FairPriceRepository.kt` |
| UI | `app/src/main/java/com/fairprice/app/ui/HomeScreen.kt` |
| Data models | `app/src/main/java/com/fairprice/app/data/models/` |
| Docs | `DOCS/MASTER_ARCHITECTURE.MD`, `DOCS/STRATEGY_LEVERS.MD`, `DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD`, `DOCS/SPRINT_13_IMPLEMENTATION_PLAN.MD` |

---

## Important Constants

- **Phases:** `sniffer`, `clean_control`, `spoof`
- **Clean control modes:** `none`, `fallback`, `shadow`
- **Network:** `clear_net` (telemetry), `"Clear Net"` (display)
- **URL tokens:** sniffer=`sniffer_intel`, clean-control=`clean_control_intel`, spoof=`yale_smart` or `legacy`

---

## Known Issues

1. **LocalStrategyFallback** (formerly DefaultPricingStrategyEngine) – Tests may fail: implementation uses `UUID.randomUUID()` for assignment key; should use domain+installationId. See `DOCS/SPRINT_13_IMPLEMENTATION_PLAN.MD`.
2. **Legacy telemetry fields** – `attempted_configs`, `final_config`, `vpn_config` kept in DB models; values are `"clear_net"` or empty for compatibility.
3. **Legacy code** – `PricingStrategyEngine` / `DefaultPricingStrategyEngine` still in use; refactor to `StrategyResolver` / `LocalStrategyFallback` per Sprint 13 plan.

---

## Planned Next Steps

1. Execute `DOCS/SPRINT_13_IMPLEMENTATION_PLAN.MD` Part A (architecture alignment: StrategyResolver, LocalStrategyFallback).
2. Deploy Railway strategy engine (Part B).
3. Wire `RailwayStrategyClient` with `LocalStrategyFallback` as fallback.

---

## Development Rules (from MASTER_ARCHITECTURE.MD)

- No deprecated Views; use GeckoView only.
- Decoupled extraction via WebExtension message bridge.
- Strategy resolution behind `StrategyResolver`; strategy engine lives on Railway. See `DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD`.
- Multi-pass integrity: sniffer → optional clean-control (fallback/shadow) → spoof.
- State via `StateFlow`; UI observes flows.
- Graceful degradation: no blocking on extraction failure.
