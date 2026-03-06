Last Updated: 2026-03-05
Current Phase: Sprint 13 Railway — Aligning architecture with strategy/engine terminology and preparing for Railway strategy engine deployment.

1. Project Overview & Objective
FairPrice is an Android application designed to defeat dynamic and personalized pricing strategies employed by online retailers (referencing tactics outlined in the Jan 2025 FTC report).

The app intercepts product URLs shared by the user and uses clear-net extraction (VPN removed in Phase 12). A headless GeckoView engine loads the page. A custom WebExtension extracts the rendered price, compares it to the original, and presents the savings to the user. The app is a "thin client": strategy decisions are made by the Railway backend; the app receives a strategy, loads it, and executes it against the retailer.

Primary Goals:

Save users money by exposing lower prices hidden by retailer algorithms.

Provide a seamless, native Android UX.

Log comprehensive telemetry (retailer tactics, success rates, price deltas) to a centralized database for future analytics.

2. Technology Stack
Mobile Client: Native Android (Kotlin).

UI Framework: Jetpack Compose (Strict adherence; no XML layouts unless absolutely required for legacy integrations).

Architecture Pattern: MVVM (Model-View-ViewModel) with Unidirectional Data Flow (StateFlow/SharedFlow).

Browser Engine: Mozilla GeckoView (No native Android WebView).

Data Extraction: GeckoView WebExtensions (JavaScript injected into GeckoSession communicating via MessageDelegate).

Networking: Clear-net (no VPN). Future: residential proxies (Sprint 14).

Database & Authentication: Supabase (Project: FairPrice).

Backend / Strategy Engine: Railway (Project: FairPrice). The strategy engine lives on Railway. It determines which strategy to send to the app; the app executes whatever it receives. Railway also processes telemetry.

Future Analytics Dashboard: Vercel (Web front-end to be built later).

3. Core Architecture & Data Flow
Cursor must strictly adhere to the following data flow for a price check:

Ingestion: User shares a URL via the Android Share Sheet or pastes it directly into the app.

User Baseline Input (Manual): The user may enter a manual reference price. This input is optional and is not an extraction pass.

Sniffer Pass (Pre-spoof, clean session): The app performs a first extraction pass in a clean session (no counterstrategies) to capture `ExtractionResult(priceCents, tactics)` from Gecko/WebExtension.

Clean Control Pass (Fallback): If sniffer fails (timeout/challenge/extraction failure), the app performs a clean-session clean-control extraction pass. If both sniffer and clean-control fail, the run degrades terminally without spoof.

Beta Shadow Sampling (Telemetry-only): When sniffer succeeds, a 20% sampled shadow clean-control pass may run for counterfactual telemetry. Shadow pass does not trigger an additional spoof chain.

Strategy Resolution: A `StrategyResolver` returns a `StrategyResult` from `(url, preSpoofTactics)`. Tactics come from the tactic-source pass (sniffer when successful, otherwise clean-control fallback). The strategy engine lives on Railway; the app uses a Railway strategy client and, when Railway is unreachable, a local strategy fallback. See `DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD`.

Execution + Spoofed Extraction: GeckoSession loads the URL over clear-net and performs spoof extraction through a single bounded spoof chain. The app executes whatever strategy was resolved (engine profile, URL token, etc.).

Extraction Payload Contract: A JavaScript WebExtension running inside GeckoView extracts DOM data and passes a JSON payload via GeckoSession.MessageDelegate with at least `{ type, priceCents, detectedTactics[] }`. Triggering must be event/DOM driven (not fixed-time delay), resilient to latency, and send once per page load.

Comparison & Presentation: The app presents a summary card with explicit pass labels (User Baseline, Sniffer Pass, optional Clean Control Pass, Spoof Pass), detected tactics, strategy label, and tactic-source provenance. Browser visibility is controlled by `showBrowser`.

Shopping Mode Lifecycle: In Shopping Mode, the app keeps the existing Gecko session active and does not force a reconnect/reload. A non-destructive “Back to App” action hides browser content. An explicit “Done/Close” action resets UI state, and clears URL input for the next run.

Graceful Degradation: If sniffer fails, clean-control fallback is attempted. If fallback fails, the app logs degraded terminal telemetry, surfaces clear-net browsing, and shows a non-blocking error message so the user is not stranded. If fallback succeeds and spoof succeeds, run outcome is degraded-success (not full failure).

Telemetry: The app logs per-pass attempt rows with explicit pass identities (`sniffer`, `clean_control`, `spoof`) and summary provenance (`tactic_source_pass`, clean-control execution mode, shadow sampled). WAF vendor and block tactics (`vendor_*`, `block_*`) must be captured from both summary and attempt telemetry so `retailer_strategies` can retain negative-feedback data even when fallback succeeds. Legacy fields remain for compatibility.

WAF Negative Feedback Loop:
- `extractor.js` must classify common WAF vendors (PerimeterX/HUMAN, Datadome, Akamai, Cloudflare) and detect known block pages.
- Blocked pages must short-circuit extraction and emit `debugExtractionPath = "waf_block"` with detected tactics instead of waiting for generic timeout failure.
- `HomeViewModel` treats `waf_block` as a failed pre-spoof sniffer outcome and routes to clean-control fallback while preserving sniffer block telemetry.

URL Token Contract (Gecko bootstrap):
- Pass navigation URLs must include a single `fp_engine` hash token.
- No existing fragment: append `#fp_engine=<token>`.
- Existing fragment: append/replace token with `&fp_engine=<token>` inside hash params (never double-`#`).
- Current token mapping: sniffer=`sniffer_intel`, clean-control extraction=`clean_control_intel`, spoof profile=`yale_smart` or `legacy`.

3.1 Current Modular Engine Boundaries
- Strategy Resolution: `StrategyResolver` is the app-side abstraction. It returns a `StrategyResult` for `(url, tactics)`. The strategy engine lives on Railway. App implementations: `RailwayStrategyClient` (calls Railway) and `LocalStrategyFallback` (used when Railway is unreachable). See `DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD`.
- Extraction Module: `ExtractionEngine` returns structured `ExtractionResult` and remains the boundary between WebExtension payload parsing and ViewModel orchestration.
- Orchestration Boundary: `HomeViewModel` coordinates sniffer -> optional clean-control (fallback or shadow) -> strategy resolution -> spoof chain -> persistence -> shopping mode, but does not own strategy or extraction internals.
- Composition Root: `MainActivity` wires `StrategyResolver` (and other dependencies) into `HomeViewModel` via dependency injection (ViewModel factory).
- Persistence Contract: Successful price checks must persist baseline and spoofed prices. `strategyId` should be persisted when available and remains nullable during stub strategy phases.
- UX Contract: Successful checks must show summary-first and only reveal Gecko shopping content after explicit user action.

4. Database Schema Structure (Supabase)
The database utilizes Supabase Auth for user management. Application data is structured as follows (Cursor should write Kotlin data classes matching these tables):

users: Extends Supabase Auth. Contains profile preferences and total aggregate savings. (Only table editable by end-users).

retailers: Tracks known e-commerce sites (domain names) and active tracking status.

retailer_strategies: A historical log of known pricing tactics (e.g., Geolocation profiling, IP tracking) employed by specific retailers, valid from/to specific dates.

price_checks: The core transactional table. Logs user ID, retailer, original URL, original price, found price, geolocation used, timestamp, and an optional user note.

app_telemetry: Tracks the performance of the app itself (e.g., GeckoView crash logs, WebExtension extraction timeouts, strategy resolution failures).

5. Strict Development Rules for Cursor
When generating code for this project, Cursor AI must follow these constraints:

No Deprecated Views: Never use android.webkit.WebView. All web rendering and DOM interaction must be handled via org.mozilla.geckoview.

Decoupled Extraction: Do not attempt synchronous DOM scraping. Use the WebExtension message-passing bridge architecture.

Decoupled Strategy Resolution: Strategy resolution must remain behind `StrategyResolver`. Do not hardcode strategy IDs or configs in ViewModels. The strategy engine lives on Railway; the app executes whatever strategy it receives.

Multi-Pass Integrity: Price checks must run sniffer extraction before strategy resolution, optional clean-control fallback/shadow under bounded policy, then spoofed extraction using the resolved strategy. Avoid unbounded extra pulls and duplicate spoof chains.

Shopping Session Continuity: After a successful spoofed pass, preserve the existing Gecko session for checkout. Do not reconnect/reload post-success unless explicitly required by user action.

State Management: ViewModels must expose state via StateFlow. UI components must observe these flows. Do not pass business logic into Compose UI functions.

Graceful Degradation: If a price cannot be extracted automatically, the application flow must not block. The user must still be able to view the page and interact with the retailer.

UI State Integrity: All ViewModels must represent their state using a Kotlin sealed interface (e.g., Idle, Processing, Success, Error) to prevent impossible overlapping states.

Database Security: All database interactions must be authenticated. The repository must ensure a valid Supabase session (via Anonymous Auth or user login) before executing inserts, and must implement retry logic to survive network toggles.

6. Strategy Lever Governance
Strategy evolution should be tracked in a dedicated lever catalog so policy changes remain explicit and testable across phases.

Canonical documents:
- `DOCS/STRATEGY_LEVERS.MD` — strategy policy levers
- `DOCS/STRATEGY_ENGINE_TERMINOLOGY.MD` — strategy/engine mental model
- `DOCS/SPRINT_13_IMPLEMENTATION_PLAN.MD` — refactor and Railway deployment plan

Maintenance rule:
- Any new strategy behavior (rotation, config management, extraction pathing, retry/cooldown, telemetry dimensions) must update `DOCS/STRATEGY_LEVERS.MD` in the same phase.
