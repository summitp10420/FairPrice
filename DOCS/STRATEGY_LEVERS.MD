Last Updated: 2026-02-26
Owner: FairPrice product/engineering
Status: Living document (update every phase)

1. Purpose
This document defines the strategy levers FairPrice can use against retailer price discrimination and the policy options available for each lever.

It is designed to:
- Keep strategy thinking modular and explicit.
- Separate "what we can change" (levers) from implementation details.
- Standardize how new tactics are added and evaluated.

2. Strategy Principle
A strategy run is a combination of lever decisions.

Example:
- VPN lever decides location/IP identity.
- Session lever decides browser freshness and retry behavior.
- Extraction lever decides how price/tactic data is captured.
- Policy lever decides rotation, retries, cooldowns, and fallback path.

The goal is to identify which combinations produce consistent savings by retailer/domain.

3. Lever Catalog

3.1 VPN Config Pool Management Lever
What it controls:
- Which WireGuard configs are available, active, and eligible for rotation.

Approaches:
- Static bundled pool (assets only).
- User-imported pool (provider configs from Proton/Surfshark/etc).
- Mixed pool (bundled + user).
- Provider-priority pool (Proton-first, then provider-agnostic fallback).

Actions:
- Import config.
- Validate config.
- Enable/disable config.
- Set baseline config.
- Remove config.
- Quarantine unhealthy config.

Policy knobs:
- Maximum active config count.
- Eligibility filters (enabled, healthy, cooldown expired).
- Provider ranking policy.
- Baseline fallback chain.

Key telemetry:
- Config success rate.
- Config timeout/failure rate.
- Median connection + extraction latency.
- Savings contribution by config.

3.2 VPN Rotation Policy Lever
What it controls:
- How configs are selected across spoof attempts.

Approaches:
- Always rotate each spoof attempt.
- Sticky-per-retailer rotation (reuse winner for a domain/time window).
- Weighted rotation (prefer recent winners).
- Exploration/exploitation blend.

Actions:
- Select next config.
- Record attempt outcome.
- Apply cooldown after repeated failures.
- Skip unhealthy configs temporarily.

Policy knobs:
- Retry budget (currently bounded).
- Failure threshold for unhealthy status.
- Cooldown duration.
- Exclusion logic for already-attempted configs.

Key telemetry:
- Attempt chain per run.
- Retry count.
- Time to first success.
- Win rate by rotation policy.

3.3 Baseline Identity Lever
What it controls:
- What "normal" user context is used outside active spoof attempts.

Approaches:
- Baseline always-on VPN config.
- User-selected baseline config.
- Dynamic baseline (best stable config).
- Clear-net baseline (for diagnostics only).

Actions:
- Reconnect baseline on Done.
- Reconnect baseline on app close.
- Validate baseline availability.
- Fallback to safe baseline when missing/failing.

Policy knobs:
- Baseline persistence source (hardcoded vs user setting).
- Reconnect strictness and retries.
- Failure UX behavior.

Key telemetry:
- Baseline reconnect success/failure.
- Baseline restore latency.
- User interruption rate due to baseline restore failures.

3.4 Session Lifecycle Lever (Gecko)
What it controls:
- Browser/session hygiene between baseline and spoofed passes.

Approaches:
- Fresh session per extraction pass.
- Session handoff with deferred old-session close.
- Bounded retry on known lifecycle churn failures.

Actions:
- Build fresh session before spoof extraction.
- Apply stabilization gate after VPN connect.
- Retry extraction once on specific lifecycle signatures.

Policy knobs:
- Stabilization delay window.
- Retry eligibility signatures.
- Session retirement timing.

Key telemetry:
- Extraction timeout rate.
- Gecko lifecycle churn errors.
- Success rate after retry.

3.5 Extraction Method Lever (Sniffer)
What it controls:
- How the app discovers and validates price signals.

Approaches:
- JSON-LD first, DOM fallback.
- Retailer-specific selector packs (Amazon, etc).
- Event-driven extraction (MutationObserver/listeners).
- Multi-path extraction diagnostics.

Actions:
- Parse structured data.
- Run fallback selectors.
- Detect anti-user tactics.
- Emit debug extraction path.

Policy knobs:
- Path ordering by retailer.
- Selector confidence thresholds.
- Timeout windows.

Key telemetry:
- Extraction path used.
- Baseline vs spoof extraction success.
- Tactic detection frequency.

3.6 URL Canonicalization Lever
What it controls:
- Conversion of shared short/redirect links to canonical product URLs.

Approaches:
- Host-based canonicalization (e.g., a.co).
- HEAD then GET fallback.
- Per-domain resolver strategy.

Actions:
- Resolve short URL.
- Preserve original URL when resolution fails.
- Log canonicalization outcomes.

Policy knobs:
- Resolve timeout.
- Allowed redirect depth.
- Domain allowlist for canonicalization.

Key telemetry:
- Canonicalization success rate.
- Baseline extraction lift from canonicalized URLs.

3.7 User Input & Comparison Lever
What it controls:
- How user-observed baseline is captured and compared to extracted spoof result.

Approaches:
- Manual cents-only entry.
- Optional entry (skip supported).
- Future: OCR/import from screenshot (not yet implemented).

Actions:
- Sanitize input digits.
- Format as currency.
- Compute potential savings.
- Display victory summary.

Policy knobs:
- Input strictness (numbers only).
- Victory threshold (strictly > 0).
- Presentation and CTA behavior.

Key telemetry:
- Manual baseline completion rate.
- Average potential savings.
- Victory rate by retailer/config.

3.8 Graceful Degradation Lever
What it controls:
- User experience when extraction/spoofing steps fail.

Approaches:
- Continue browsing with clear error state.
- Log fallback telemetry and keep flow usable.
- Preserve user agency over blocking failures.

Actions:
- Create degraded `PriceCheck`.
- Surface browser for manual shopping.
- Store diagnostics for analysis.

Policy knobs:
- Which failures are terminal vs degradable.
- Messaging strictness and user guidance.

Key telemetry:
- Degraded run rate.
- Primary failure stage (baseline, connect, spoof, logging).

3.9 Telemetry Granularity Lever
What it controls:
- Amount and structure of data captured for training and policy iteration.

Approaches:
- Summary-only logging.
- Summary + per-attempt detailed rows.
- Future aggregation in external analytics service.

Actions:
- Persist summary outcome.
- Persist attempt rows with phase/error/latency.
- Maintain schema backward compatibility fallback.

Policy knobs:
- Required vs optional fields.
- Retry policy for telemetry writes.
- Redaction policy for sensitive values.

Key telemetry:
- Insert success/failure.
- Data completeness by run.
- Coverage of lever variables in logs.

3.10 Security & Secret Hygiene Lever
What it controls:
- Protection of VPN keys and user-sensitive artifacts.

Approaches:
- Never commit `.conf` files.
- Encrypt imported config content at rest.
- Store only non-secret metadata in clear text.

Actions:
- Validate and redact errors.
- Prevent secret values in logs.
- Separate metadata from secret blobs.
- Enforce delete semantics for removed configs.

Policy knobs:
- Encryption method/key lifecycle.
- Retention and deletion policy.
- Secret-scanning checks in CI/workflow.

Key telemetry:
- Secret exposure incidents (target: zero).
- Import validation failure categories.

4. Strategy Patterns to Evaluate
Use this section to define named combinations to test.

Pattern template:
- Name:
- Target retailer/domain:
- Lever settings:
  - Config pool:
  - Rotation:
  - Session:
  - Extraction path:
  - Baseline behavior:
  - Retry/cooldown:
- Expected benefit:
- Risks:
- Metrics to judge success:

Initial candidate patterns:
- Pattern A: Proton-first rotating with current retry budget.
- Pattern B: Weighted rotation by recent wins, bounded exploration.
- Pattern C: Sticky winner by domain for short horizon with health fallback.

5. Operating Cadence (How We Maintain This)
- Update this file at the end of each completed phase.
- For every new feature/fix, map it to one or more levers above.
- Add any new lever before implementation, not after.
- Record policy changes with date and rationale.
- Keep deprecated approaches listed but clearly marked "deprecated."

6. Change Log
- 2026-02-26: Initial strategy lever catalog created.
