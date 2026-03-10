# Strategy & Engine Terminology

**Last Updated:** 2026-03-06  
**Status:** Canonical — use this terminology for all strategy/engine references going forward.

---

## Mental Model

The app is a **strategy executor**. It does not decide which strategy to run. It receives a strategy, loads it, and executes it against the retailer.

The **strategy engine** is the model that selects which strategy profile to use. It lives on the Railway backend. The engine uses a **tactic registry** to map detected retailer tactics to required countermeasures, then selects the lowest-tier qualifying profile. It also uses **epsilon-greedy** (75/25) for exploration. The engine is identified by **strategy_engine** in the API and telemetry.

---

## Definitions

| Term | Definition |
|------|------------|
| **Strategy Engine** | The model that selects the strategy profile. Identified by **strategy_engine** in API/telemetry (e.g. railway_brain_v2.0). Lives on Railway; fully synchronous on critical path (in-memory cache, 60s background refresh). |
| **Strategy Name** | A label for the strategy result (e.g. policy name). Not the engine identifier. |
| **Strategy Profile** | Open string returned by the engine. The selected runnable profile we deploy to beat the retailer. Canonical codes (Sprint 14): `clean_baseline` (tier 0), `shield_basic` (tier 1), `amnesia_standard` (tier 2), `stealth_max` (tier 3). Definitions live in Supabase `strategy_profiles`; the engine selects by tactic-driven rules; Android executes from the flat definition (booleans). |
| **Tactic Registry** | Supabase table mapping tactic codes (e.g. `vendor_perimeterx`, `block_datadome`) to `required_countermeasures`. The engine merges requirements from detected tactics and picks the lowest-tier profile that satisfies them. |
| **Selection Mode** | `exploit` (tactic-driven) or `explore` (epsilon-greedy random tier). Returned by the engine and persisted to `price_checks.selection_mode` for analytics. |
| **Strategy** | A `StrategyResult` payload (strategy profile, flat lever booleans, engine metadata, selection_mode, etc.) that tells the app how to run a spoof pass against the retailer. |
| **Strategy Resolver** | App-side abstraction. Returns a `StrategyResult` for a given URL and tactics. Hides whether the result came from Railway or from a local fallback. The coordinator depends on this abstraction only. |
| **Railway Strategy Client** | App-side component. Calls the Railway strategy engine API. Returns `StrategyResult` when the call succeeds. |
| **Local Strategy Fallback** | App-side component. Produces a `StrategyResult` when the Railway call fails (timeout, unreachable, error). It is not an engine — it is a fallback provider. Uses legacy profile codes for backward compatibility. |
| **Execute / Load** | The app loads the strategy into the extraction flow and runs it. For spoof passes: URL token (strategy profile code), flat lever booleans, proxy config (future), etc. |

---

## Flow

```
Pre-spoof (sniffer + optional clean-control) → tactics collected
  → Strategy Resolver.resolveStrategy(url, tactics)
       → Railway Strategy Client (calls Railway engine)
            → Success: return StrategyResult (profile, selection_mode, etc.)
            → Failure: Local Strategy Fallback returns StrategyResult
  → (Admin override: if isAdmin and EngineOverride != AUTO, coordinator overrides profile)
  → SpoofAttemptRunner executes StrategyResult against retailer
```

## Tiered Strategy Profiles (Sprint 14)

| Tier | Code | Name | Description |
|------|------|------|-------------|
| 0 | `clean_baseline` | Clean Baseline | Least aggressive: no countermeasures. Equivalent to incognito load. |
| 1 | `shield_basic` | Shield Basic | Strict tracking protection + URL sanitization. |
| 2 | `amnesia_standard` | Amnesia Standard | Shield Basic + full session amnesia wipe. |
| 3 | `stealth_max` | Stealth Max | Maximum countermeasures: amnesia + tracking + canvas spoofing + URL sanitize. |

Engine selection: merge `required_countermeasures` from detected tactics → pick lowest-tier qualifying profile. Epsilon-greedy (75/25): exploit = tactic-driven; explore = `bucket % 4` tier.

---

## Rules

1. **Engine = Railway only.** Do not call any app-side component an "engine." The engine is the backend.
2. **Fallback ≠ Engine.** The local fallback provides a strategy when the engine is unreachable. Name it `LocalStrategyFallback` (or similar), not `DefaultStrategyEngine` or `LocalStrategyEngine`.
3. **Every spoof run has a strategy.** In all cases, a strategy must be selected (from Railway or fallback) and loaded to the app before spoof execution.
4. **App executes; it does not decide.** The app's job is to fetch strategy, load it, run it. Strategy decisions live on Railway.

---

## Deprecated Terms (Do Not Use)

| Deprecated | Use Instead |
|------------|-------------|
| `DefaultPricingStrategyEngine` | `LocalStrategyFallback` (when referring to the fallback implementation) |
| `PricingStrategyEngine` (as "the engine") | `StrategyResolver` (app abstraction) or "Railway strategy engine" (backend) |
| "Strategy engine on the app" | "Strategy resolver" or "Railway strategy client + local fallback" |
| `legacy` (strategy profile) | `clean_baseline` |
| `yale_smart` (strategy profile) | `stealth_max` |

---

## Related Documents

- `DOCS/MASTER_ARCHITECTURE.MD` — Overall architecture
- `DOCS/STRATEGY_LEVERS.MD` — Strategy policy levers and catalog
- `DOCS/SPRINT_13_IMPLEMENTATION_PLAN.MD` — Plan to bring this model into the codebase
