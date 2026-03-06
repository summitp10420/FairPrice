# Database naming vs. strategy terminology

**Goal:** Compare Supabase table/column naming to our canonical strategy naming (strategy_engine = selector, strategy_name = result label, strategy_profile = selected profile) and note gaps.

---

## Canonical terminology (reference)

| Term | Meaning |
|------|--------|
| **strategy_engine** | The model that selected the profile (e.g. railway_brain_v1.0). Identified in API/telemetry as `strategy_engine`. |
| **strategy_name** | Label for the strategy result (e.g. policy name like railway_dynamic_v1_50_50). Not the engine. |
| **strategy_profile** | Open string we deployed (e.g. yale_smart, legacy). Used for effectiveness analytics. |

---

## Current database and app mapping

### 1. `price_checks` (run-level summary)

| Column (snake_case in DB) | App source | Aligned? |
|---------------------------|------------|----------|
| strategy_id | strategy.strategyId | Yes – opaque id from engine. |
| strategy_name | strategy.strategyName | Yes – we use this as the result label (policy name). |

**Gaps:**

- No **strategy_engine** or **strategy_profile** at run level. To answer “which engine and which profile were used for this run?” you must join to `price_check_attempts` and read from `applied_levers` (or take the last spoof attempt). Optional improvement: add `strategy_engine text` and `strategy_profile text` to `price_checks` and set them when building the run summary so run-level analytics don’t depend on parsing attempt JSON.

---

### 2. `price_check_attempts` (per-attempt rows)

**Schema (PHASE8 + needed for logging):**

- Existing: id, price_check_id, phase, attempt_index, vpn_config*, success, error_*, extracted_price_cents, detected_tactics, debug_extraction_path, latency_ms, created_at.
- **Missing and required for current app:** `applied_levers jsonb`, `execution_url text`.

**`applied_levers` JSON (written by TelemetryAssembler.buildAppliedLevers):**

| Key in JSON | Meaning | Aligned? |
|-------------|--------|----------|
| strategy_name | strategy.strategyName (result label) | Yes. |
| strategy_engine | strategy.strategyEngineName (selector id) | Yes. |
| strategy_profile | Profile code (e.g. yale_smart) | Yes. |
| strategy_profile_policy | engineSelectionPolicy | Yes. |
| strategy_profile_reason | engineSelectionReason | Yes. |
| strategy_profile_key_scope | engineSelectionKeyScope | Yes. |
| strategy_profile_bucket | engineSelectionBucket | Yes. |
| engine_selection_source | "strategy" or "admin_override" | Overloaded: “engine” here means “who chose” (source), not the strategy engine. Could rename to **selection_source** for clarity. |
| engine_version | App version (11.5a) | “Engine” = app/build, not strategy engine. Could rename to **app_version** to reserve “engine” for the selector. |
| engine_build_id | Build id (local-dev) | Same; could rename to **build_id**. |
| url_sanitized | Boolean | Yes. |
| amnesia_protocol | Boolean | Yes. |
| tracking_protection | "strict" / "off" | Yes. |

So inside `applied_levers`, **strategy_engine**, **strategy_name**, and **strategy_profile** are already aligned with the new naming. The only naming that still uses “engine” in a different sense is selection source and app/build metadata.

---

### 3. Other tables

- **retailer_strategies** / **retailer_tactic_rollup**: “strategies” here means retailer tactics / counter-tactics, not our strategy engine or strategy profile. Different domain; no change needed for alignment.
- **RetailerTacticsLog.recommended_counter_strategy_id**: Same – counter-tactic concept, not strategy_engine/profile.
- **vpn_config**, **vpn_config_source**, **vpn_config_provider** on attempts: Legacy names; on clear-net they hold "clear_net". Documented as legacy; renaming is optional.

---

## Summary

| Area | Aligned? | Action |
|------|----------|--------|
| price_checks.strategy_id, strategy_name | Yes | None. |
| applied_levers.strategy_engine, strategy_name, strategy_profile | Yes | None. |
| applied_levers.engine_selection_source | Overloaded | Optional: rename to **selection_source** in JSON (and in TelemetryAssembler / any reads). |
| applied_levers.engine_version / engine_build_id | Different “engine” | Optional: rename to **app_version** / **build_id** to reserve “engine” for the selector. |
| price_checks run-level | Missing engine/profile | Optional: add **strategy_engine**, **strategy_profile** columns and set them when building the run so analytics can filter/group by engine and profile without parsing attempts. |
| price_check_attempts schema | Missing columns | **Required:** add **applied_levers jsonb** and **execution_url text** so logging succeeds (see DOCS/migrations/add_applied_levers_and_execution_url_to_attempts.sql). |

---

## Recommended order

1. **Run the migration** that adds `applied_levers` and `execution_url` to `price_check_attempts` so logging works.
2. **Optionally** add `strategy_engine` and `strategy_profile` to `price_checks` and set them in the coordinator when building the run summary.
3. **Optionally** rename keys inside `applied_levers` (engine_selection_source → selection_source, engine_version → app_version, engine_build_id → build_id) in a later change; would require app + any dashboards/queries that read these keys to be updated together.
