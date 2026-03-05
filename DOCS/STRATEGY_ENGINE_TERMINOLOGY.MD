# Strategy & Engine Terminology

**Last Updated:** 2026-03-05  
**Status:** Canonical — use this terminology for all strategy/engine references going forward.

---

## Mental Model

The app is a **strategy executor**. It does not decide which strategy to run. It receives a strategy, loads it, and executes it against the retailer.

There is **one strategy engine**. It lives on the **Railway backend**. It determines which strategy to send to the app based on domain, detected tactics, and bucketing.

---

## Definitions

| Term | Definition |
|------|------------|
| **Strategy Engine** | The decision-making brain. Lives on Railway. Accepts `(domain, detected_tactics, anonymous_bucket)` and returns a `StrategyResult`. There is exactly one engine. |
| **Strategy** | A `StrategyResult` payload (engine profile, policy, URL tokens, proxy config, etc.) that tells the app how to run a spoof pass against the retailer. |
| **Strategy Resolver** | App-side abstraction. Returns a `StrategyResult` for a given URL and tactics. Hides whether the result came from Railway or from a local fallback. The coordinator depends on this abstraction only. |
| **Railway Strategy Client** | App-side component. Calls the Railway strategy engine API. Returns `StrategyResult` when the call succeeds. |
| **Local Strategy Fallback** | App-side component. Produces a `StrategyResult` when the Railway call fails (timeout, unreachable, error). It is not an engine — it is a fallback provider. |
| **Execute / Load** | The app loads the strategy into the extraction flow and runs it. For spoof passes: URL token, engine profile, proxy config (future), etc. |

---

## Flow

```
Pre-spoof (sniffer + optional clean-control) → tactics collected
  → Strategy Resolver.resolveStrategy(url, tactics)
       → Railway Strategy Client (calls Railway engine)
            → Success: return StrategyResult
            → Failure: Local Strategy Fallback returns StrategyResult
  → SpoofAttemptRunner executes StrategyResult against retailer
```

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

---

## Related Documents

- `DOCS/MASTER_ARCHITECTURE.MD` — Overall architecture
- `DOCS/STRATEGY_LEVERS.MD` — Strategy policy levers and catalog
- `DOCS/SPRINT_13_IMPLEMENTATION_PLAN.MD` — Plan to bring this model into the codebase
