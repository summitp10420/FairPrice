Last Updated: 2026-02-26
Current Phase: Phase 5 in progress (two-pass extraction + shopping mode lifecycle).

1. Project Overview & Objective
FairPrice is an Android application designed to defeat dynamic and personalized pricing strategies employed by online retailers (referencing tactics outlined in the Jan 2025 FTC report).

The app intercepts product URLs shared by the user, dynamically routes network traffic through specific WireGuard VPN configurations to alter geolocation/IP profiling, and uses a headless GeckoView engine to load the page. A custom WebExtension extracts the rendered price, compares it to the original, and presents the savings to the user.

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

Networking/Spoofing: WireGuard Android (com.wireguard.android:tunnel).

Database & Authentication: Supabase (Project: FairPrice).

Backend / Middleware API: Railway (Project: FairPrice) for processing heavy telemetry and VPN configuration logic.

Future Analytics Dashboard: Vercel (Web front-end to be built later).

3. Core Architecture & Data Flow
Cursor must strictly adhere to the following data flow for a price check:

Ingestion: User shares a URL via the Android Share Sheet or pastes it directly into the app.

Baseline Extraction (Clear-Net): The app performs a first extraction pass without VPN and captures `ExtractionResult(priceCents, tactics)` from Gecko/WebExtension.

Strategy & Routing: The app determines routing via a dedicated pricing strategy module. `PricingStrategyEngine` resolves a strategy result from `(url, baselineTactics)`. During the current stub phase, `strategyId` may be null. The ViewModel must orchestrate this step without hardcoding strategy/config values. The resolved WireGuard config is then used to establish the tunnel via a Foreground Service.

Execution + Spoofed Extraction: GeckoSession loads the URL through the active VPN tunnel and performs a second extraction pass to capture spoofed price data.

Extraction Payload Contract: A JavaScript WebExtension running inside GeckoView extracts DOM data and passes a JSON payload via GeckoSession.MessageDelegate with at least `{ type, priceCents, detectedTactics[] }`. Triggering must be event/DOM driven (not fixed-time delay), resilient to latency, and send once per page load.

Comparison & Presentation: The app compares baseline and spoofed prices, displays outcome, and surfaces the live GeckoView tab used for the winning pass so the user can complete checkout.

Shopping Mode Lifecycle: On successful spoofed extraction + logging, the app enters Shopping Mode, keeps the existing VPN/session active, and does not force a reconnect/reload. On explicit close action (or failure before Shopping Mode activation), the app disconnects VPN and resets session UI state.

Telemetry: The app asynchronously logs session data (including baseline price, spoofed price, applied strategy ID when available, and extraction metadata/tactics) to Supabase through the repository layer. Railway remains a future/optional middleware path for heavier telemetry processing.

3.1 Current Modular Engine Boundaries
- Strategy Module: `PricingStrategyEngine` defines strategy selection as a pure Kotlin/domain-like seam. `DefaultPricingStrategyEngine` is the current stub implementation and can be replaced without changing UI orchestration flow.
- Extraction Module: `ExtractionEngine` returns structured `ExtractionResult` and remains the boundary between WebExtension payload parsing and ViewModel orchestration.
- Orchestration Boundary: `HomeViewModel` coordinates baseline extraction -> strategy -> VPN -> spoofed extraction -> persistence -> shopping mode, but does not own strategy or extraction internals.
- Composition Root: `MainActivity` wires concrete engine implementations into `HomeViewModel` via dependency injection (ViewModel factory).
- Persistence Contract: Successful price checks must persist baseline and spoofed prices. `strategyId` should be persisted when available and remains nullable during stub strategy phases.

4. Database Schema Structure (Supabase)
The database utilizes Supabase Auth for user management. Application data is structured as follows (Cursor should write Kotlin data classes matching these tables):

users: Extends Supabase Auth. Contains profile preferences and total aggregate savings. (Only table editable by end-users).

retailers: Tracks known e-commerce sites (domain names) and active tracking status.

retailer_strategies: A historical log of known pricing tactics (e.g., Geolocation profiling, IP tracking) employed by specific retailers, valid from/to specific dates.

price_checks: The core transactional table. Logs user ID, retailer, original URL, original price, found price, geolocation used, timestamp, and an optional user note.

app_telemetry: Tracks the performance of the app itself (e.g., VPN connection failures, GeckoView crash logs, WebExtension extraction timeouts).

5. Strict Development Rules for Cursor
When generating code for this project, Cursor AI must follow these constraints:

No Deprecated Views: Never use android.webkit.WebView. All web rendering and DOM interaction must be handled via org.mozilla.geckoview.

Decoupled Extraction: Do not attempt synchronous DOM scraping. Use the WebExtension message-passing bridge architecture.

Decoupled Strategy Resolution: Do not hardcode VPN strategy IDs or WireGuard configs in ViewModels. Strategy/config resolution must remain behind `PricingStrategyEngine`.

Two-Pass Integrity: Price checks must run baseline extraction before VPN strategy application, then spoofed extraction after VPN connect. Avoid collapsing back into a single-pass flow.

Shopping Session Continuity: After a successful spoofed pass, preserve the existing VPN/session for checkout. Do not reconnect/reload post-success unless explicitly required by user action.

VPN Lifecycle: The WireGuard tunnel must be managed in an isolated Android Service to prevent memory leaks or app crashes when the UI is backgrounded.

State Management: ViewModels must expose state via StateFlow. UI components must observe these flows. Do not pass business logic into Compose UI functions.

Graceful Degradation: If a price cannot be extracted automatically, the application flow must not block. The user must still be able to view the page and interact with the retailer.

UI State Integrity: All ViewModels must represent their state using a Kotlin sealed interface (e.g., Idle, Processing, Success, Error) to prevent impossible overlapping states.

Database Security: All database interactions must be authenticated. The repository must ensure a valid Supabase session (via Anonymous Auth or user login) before executing inserts, and must implement retry logic to survive VPN network toggles.
