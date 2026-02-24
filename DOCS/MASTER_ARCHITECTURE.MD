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

Strategy & Routing: The app determines the target retailer, selects an appropriate WireGuard VPN profile (e.g., routing through a different state/country to bypass geolocation pricing), and establishes the tunnel via a Foreground Service.

Execution: GeckoSession loads the URL through the active VPN tunnel.

Extraction: A JavaScript WebExtension running inside GeckoView extracts the DOM price data and passes a JSON payload back to the native Kotlin code via GeckoSession.MessageDelegate. (Note: If extraction fails, the app must gracefully degrade and still allow the user to view the page).

Comparison & Presentation: The app compares the extracted "Found Price" to the "Original Price", displays the savings, and renders the live GeckoView tab so the user can complete the purchase if desired.

Telemetry: The app asynchronously fires the session data (original price, found price, applied strategy, location used, user notes) to the Railway API, which writes to Supabase.

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

VPN Lifecycle: The WireGuard tunnel must be managed in an isolated Android Service to prevent memory leaks or app crashes when the UI is backgrounded.

State Management: ViewModels must expose state via StateFlow. UI components must observe these flows. Do not pass business logic into Compose UI functions.

Graceful Degradation: If a price cannot be extracted automatically, the application flow must not block. The user must still be able to view the page and interact with the retailer.

UI State Integrity: All ViewModels must represent their state using a Kotlin sealed interface (e.g., Idle, Processing, Success, Error) to prevent impossible overlapping states.

Database Security: All database interactions must be authenticated. The repository must ensure a valid Supabase session (via Anonymous Auth or user login) before executing inserts, and must implement retry logic to survive VPN network toggles.
