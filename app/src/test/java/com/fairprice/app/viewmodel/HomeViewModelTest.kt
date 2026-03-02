package com.fairprice.app.viewmodel

import android.content.Intent
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.data.RunLogResult
import com.fairprice.app.data.models.PriceCheck
import com.fairprice.app.data.models.PriceCheckAttempt
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.ExtractionRequest
import com.fairprice.app.engine.ExtractionResult
import com.fairprice.app.engine.CleanSessionPreparationException
import com.fairprice.app.engine.EngineProfile
import com.fairprice.app.engine.PricingStrategyEngine
import com.fairprice.app.engine.StrategyResult
import com.fairprice.app.engine.VpnConfigRecord
import com.fairprice.app.engine.VpnConfigStore
import com.fairprice.app.engine.VpnEngine
import com.fairprice.app.engine.VpnPermissionRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mozilla.geckoview.GeckoSession
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    companion object {
        private const val TOKEN_KEY = "fp_engine"
        private const val TOKEN_SNIFFER_INTEL = "sniffer_intel"
        private const val TOKEN_CLEAN_CONTROL_INTEL = "clean_control_intel"
        private const val TOKEN_YALE_SMART = "yale_smart"
        private const val TOKEN_CLEAN_CONTROL_V1 = "clean_control_v1"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun strategyFailure_setsErrorAndSkipsVpnAndExtraction() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(
                    ExtractionResult(
                        priceCents = 1500,
                        tactics = listOf("hidden_canvas"),
                    ),
                ),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.failure(IllegalStateException("no strategy match")),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            vpnConfigStore = FakeVpnConfigStore(
                baselineConfigId = "user:baseline",
                configs = listOf(
                    VpnConfigRecord(
                        id = "user:baseline",
                        source = com.fairprice.app.engine.VpnConfigSource.USER,
                        displayName = "Baseline",
                        providerHint = "proton",
                        enabled = true,
                    ),
                ),
            ),
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(1, strategyEngine.determineCalls)
        assertEquals(listOf("hidden_canvas"), strategyEngine.lastBaselineTactics)
        assertEquals(0, vpnEngine.connectCalls)
        assertEquals(0, vpnEngine.disconnectCalls)
        assertEquals(1, extractionEngine.loadCalls)
        assertEquals(1, repository.logCalls)

        val processState = viewModel.uiState.value.processState
        assertTrue(processState is HomeProcessState.Error)
        val errorState = processState as HomeProcessState.Error
        assertTrue(errorState.message.contains("Strategy resolution failed"))
    }

    @Test
    fun success_showsSummaryAndKeepsBrowserHiddenUntilCheckout() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(
                    ExtractionResult(
                        priceCents = 1999,
                        tactics = listOf("cookie_tracking"),
                    ),
                ),
                Result.success(
                    ExtractionResult(
                        priceCents = 1299,
                        tactics = listOf("hidden_canvas"),
                    ),
                ),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(
                    strategyId = "strat_test_123",
                    wireguardConfig = "wg-test-config",
                ),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            vpnConfigStore = FakeVpnConfigStore(
                baselineConfigId = "user:baseline",
                configs = listOf(
                    VpnConfigRecord(
                        id = "user:baseline",
                        source = com.fairprice.app.engine.VpnConfigSource.USER,
                        displayName = "Baseline",
                        providerHint = "proton",
                        enabled = true,
                    ),
                ),
            ),
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(1, strategyEngine.determineCalls)
        assertEquals(listOf("cookie_tracking"), strategyEngine.lastBaselineTactics)
        assertEquals(1, vpnEngine.connectCalls)
        assertEquals("wg-test-config", vpnEngine.lastConnectConfig)
        assertEquals(0, vpnEngine.disconnectCalls)
        assertEquals(2, extractionEngine.loadCalls)
        assertEquals(1, repository.logCalls)
        assertNotNull(repository.lastLoggedPriceCheck)
        assertEquals("strat_test_123", repository.lastLoggedPriceCheck?.strategyId)
        assertEquals(1999, repository.lastLoggedPriceCheck?.baselinePriceCents)
        assertEquals(1299, repository.lastLoggedPriceCheck?.foundPriceCents)
        val persistedTactics =
            repository.lastLoggedPriceCheck?.rawExtractionData
                ?.get("detected_tactics")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
        assertEquals(listOf("cookie_tracking"), persistedTactics)
        assertTrue(!viewModel.uiState.value.showBrowser)
        val processState = viewModel.uiState.value.processState
        assertTrue(processState is HomeProcessState.Success)
        val summary = (processState as HomeProcessState.Success).summary
        assertEquals("$19.99", summary.snifferPrice)
        assertEquals(null, summary.cleanControlPrice)
        assertEquals("$12.99", summary.spoofedPrice)
        assertEquals(null, summary.dirtyBaselinePrice)
        assertEquals(null, summary.potentialSavings)
        assertEquals(false, summary.isVictory)
        assertEquals(listOf("cookie_tracking"), summary.tactics)
        assertEquals("clean_strategy_v1.0", summary.strategyName)
        assertEquals("wg-test-config", summary.vpnConfig)
        assertEquals(listOf("wg-test-config"), summary.attemptedConfigs)
        assertEquals("wg-test-config", summary.finalConfig)
        assertEquals(0, summary.retryCount)
        assertEquals("success", summary.outcome)
        assertEquals("https://example.com/p/123", strategyEngine.lastUrl)
        assertEquals(
            expectedPassUrls(
                "https://example.com/p/123" to TOKEN_SNIFFER_INTEL,
                "https://example.com/p/123" to TOKEN_YALE_SMART,
            ),
            extractionEngine.loadedUrls,
        )
    }

    @Test
    fun shortAmazonUrl_resolutionSuccess_usesCanonicalUrlAcrossFlow() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2599, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 2299, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val canonicalUrl = "https://www.amazon.com/dp/B0TEST1234"
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
            shortUrlResolver = { canonicalUrl },
        )

        viewModel.onUrlInputChanged("https://a.co/d/01Ral6wt")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(canonicalUrl, strategyEngine.lastUrl)
        assertEquals(
            expectedPassUrls(
                canonicalUrl to TOKEN_SNIFFER_INTEL,
                canonicalUrl to TOKEN_YALE_SMART,
            ),
            extractionEngine.loadedUrls,
        )
        assertEquals(canonicalUrl, repository.lastLoggedPriceCheck?.productUrl)
    }

    @Test
    fun shortAmazonUrl_resolutionFailure_fallsBackToOriginalUrl() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2599, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 2299, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val originalShortUrl = "https://a.co/d/01Ral6wt"
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
            shortUrlResolver = { null },
        )

        viewModel.onUrlInputChanged(originalShortUrl)
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(originalShortUrl, strategyEngine.lastUrl)
        assertEquals(
            expectedPassUrls(
                originalShortUrl to TOKEN_SNIFFER_INTEL,
                originalShortUrl to TOKEN_YALE_SMART,
            ),
            extractionEngine.loadedUrls,
        )
        assertEquals(originalShortUrl, repository.lastLoggedPriceCheck?.productUrl)
    }

    @Test
    fun tokenContract_noFragment_appendsHashToken() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 1800, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 1500, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(StrategyResult(strategyId = null, wireguardConfig = "wg-test-config")),
        )
        val inputUrl = "https://example.com/p/123"
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
            shadowCleanControlSampler = { false },
        )

        viewModel.onUrlInputChanged(inputUrl)
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(
            expectedPassUrls(
                inputUrl to TOKEN_SNIFFER_INTEL,
                inputUrl to TOKEN_YALE_SMART,
            ),
            extractionEngine.loadedUrls,
        )
    }

    @Test
    fun tokenContract_existingFpEngine_replacesTokenWithoutDuplicatingKey() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2000, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 1800, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(StrategyResult(strategyId = null, wireguardConfig = "wg-test-config")),
        )
        val inputUrl = "https://example.com/p/123#details&fp_engine=legacy_token"
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
            shortUrlResolver = { it },
            shadowCleanControlSampler = { false },
        )

        viewModel.onUrlInputChanged(inputUrl)
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        val snifferUrl = extractionEngine.loadedUrls.first()
        assertTrue(snifferUrl.contains("#details&$TOKEN_KEY=$TOKEN_SNIFFER_INTEL"))
        assertTrue(!snifferUrl.contains("legacy_token"))
        assertEquals(1, Regex("""fp_engine=""").findAll(snifferUrl).count())
    }

    @Test
    fun spoofPass_sanitizesTrackingParams_andLogsExecutionUrlLeverTelemetry() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2599, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 2299, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val inputUrl = "https://example.com/p/123?utm_source=ad&gclid=abc123&sku=99#details"
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
            shortUrlResolver = { it },
        )

        viewModel.onUrlInputChanged(inputUrl)
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(
            expectedPassUrls(
                "https://example.com/p/123?utm_source=ad&gclid=abc123&sku=99#details" to TOKEN_SNIFFER_INTEL,
                "https://example.com/p/123?sku=99#details" to TOKEN_YALE_SMART,
            ),
            extractionEngine.loadedUrls,
        )
        val attempts = repository.lastLoggedAttempts
        assertEquals(2, attempts.size)
        assertEquals(inputUrl, attempts[0].executionUrl)
        assertEquals(
            "false",
            attempts[0].appliedLevers?.jsonObject?.get("url_sanitized")?.jsonPrimitive?.content,
        )
        assertEquals("https://example.com/p/123?sku=99#details", attempts[1].executionUrl)
        assertEquals(
            "true",
            attempts[1].appliedLevers?.jsonObject?.get("url_sanitized")?.jsonPrimitive?.content,
        )
        assertEquals(
            "true",
            attempts[1].appliedLevers?.jsonObject?.get("amnesia_protocol")?.jsonPrimitive?.content,
        )
        assertEquals(
            "strict",
            attempts[1].appliedLevers?.jsonObject?.get("tracking_protection")?.jsonPrimitive?.content,
        )
        assertEquals(
            "yale_smart",
            attempts[1].appliedLevers?.jsonObject?.get("engine_profile")?.jsonPrimitive?.content,
        )
        assertEquals(
            "11.5a",
            attempts[1].appliedLevers?.jsonObject?.get("engine_version")?.jsonPrimitive?.content,
        )
        assertEquals(
            "local-dev",
            attempts[1].appliedLevers?.jsonObject?.get("engine_build_id")?.jsonPrimitive?.content,
        )
        assertEquals(
            "strategy",
            attempts[1].appliedLevers?.jsonObject?.get("engine_selection_source")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun spoofPass_withoutTrackingParams_logsUrlSanitizedFalse() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2599, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 2299, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val inputUrl = "https://example.com/p/123?sku=99"
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
            shortUrlResolver = { it },
        )

        viewModel.onUrlInputChanged(inputUrl)
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(
            expectedPassUrls(
                inputUrl to TOKEN_SNIFFER_INTEL,
                inputUrl to TOKEN_YALE_SMART,
            ),
            extractionEngine.loadedUrls,
        )
        val attempts = repository.lastLoggedAttempts
        assertEquals(2, attempts.size)
        assertEquals(
            "false",
            attempts[1].appliedLevers?.jsonObject?.get("url_sanitized")?.jsonPrimitive?.content,
        )
        assertEquals(
            "true",
            attempts[1].appliedLevers?.jsonObject?.get("amnesia_protocol")?.jsonPrimitive?.content,
        )
        assertEquals(
            "strict",
            attempts[1].appliedLevers?.jsonObject?.get("tracking_protection")?.jsonPrimitive?.content,
        )
        assertEquals(
            "yale_smart",
            attempts[1].appliedLevers?.jsonObject?.get("engine_profile")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun adminOverride_forceLegacy_disablesSpoofSanitizationAndStrictTracking() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2599, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 2399, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(
                    strategyId = "s_legacy",
                    wireguardConfig = "wg-test-config",
                    engineProfile = EngineProfile.YALE_SMART,
                ),
            ),
        )
        val inputUrl = "https://example.com/p/123?utm_source=ad&sku=99"
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
            isAdminUser = true,
            shortUrlResolver = { it },
        )
        viewModel.onEngineOverrideChanged(EngineOverride.FORCE_LEGACY)
        viewModel.onUrlInputChanged(inputUrl)
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(
            expectedPassUrls(
                inputUrl to TOKEN_SNIFFER_INTEL,
                inputUrl to TOKEN_CLEAN_CONTROL_V1,
            ),
            extractionEngine.loadedUrls,
        )
        val spoofRequest = extractionEngine.requests[1]
        assertEquals(false, spoofRequest.strictTrackingProtection)
        val spoofAttempt = repository.lastLoggedAttempts.last()
        assertEquals(
            "false",
            spoofAttempt.appliedLevers?.jsonObject?.get("url_sanitized")?.jsonPrimitive?.content,
        )
        assertEquals(
            "off",
            spoofAttempt.appliedLevers?.jsonObject?.get("tracking_protection")?.jsonPrimitive?.content,
        )
        assertEquals(
            "clean_control_v1",
            spoofAttempt.appliedLevers?.jsonObject?.get("engine_profile")?.jsonPrimitive?.content,
        )
        assertEquals(
            "admin_override",
            spoofAttempt.appliedLevers?.jsonObject?.get("engine_selection_source")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun onEnterShoppingMode_setsBrowserVisibleAfterSummary() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 1800, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 1500, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(
                    strategyId = null,
                    wireguardConfig = "wg-test-config",
                ),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            vpnConfigStore = FakeVpnConfigStore(
                baselineConfigId = "user:baseline",
                configs = listOf(
                    VpnConfigRecord(
                        id = "user:baseline",
                        source = com.fairprice.app.engine.VpnConfigSource.USER,
                        displayName = "Baseline",
                        providerHint = "proton",
                        enabled = true,
                    ),
                ),
            ),
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()
        assertTrue(!viewModel.uiState.value.showBrowser)

        viewModel.onEnterShoppingMode()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showBrowser)
    }

    @Test
    fun dirtyBaselineInput_sanitizesToDigitsOnly() = runTest(dispatcher) {
        val viewModel = HomeViewModel(
            repository = FakeRepository(),
            vpnEngine = FakeVpnEngine(),
            extractionEngine = FakeExtractionEngine(),
            strategyEngine = FakeStrategyEngine(
                result = Result.success(
                    StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
                ),
            ),
        )

        viewModel.onDirtyBaselineInputChanged("$44.95abc")
        advanceUntilIdle()

        assertEquals("4495", viewModel.uiState.value.dirtyBaselineInputRaw)
    }

    @Test
    fun success_withDirtyBaseline_calculatesSavingsAndPersistsTelemetry() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = FakeVpnEngine(),
            extractionEngine = FakeExtractionEngine(
                extractionResults = mutableListOf(
                    Result.success(ExtractionResult(priceCents = 2500, tactics = emptyList())),
                    Result.success(ExtractionResult(priceCents = 1299, tactics = emptyList())),
                ),
            ),
            strategyEngine = FakeStrategyEngine(
                result = Result.success(
                    StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
                ),
            ),
        )

        viewModel.onDirtyBaselineInputChanged("4495")
        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        val success = viewModel.uiState.value.processState as HomeProcessState.Success
        assertEquals("$44.95", success.summary.dirtyBaselinePrice)
        assertEquals("$31.96", success.summary.potentialSavings)
        assertEquals(true, success.summary.isVictory)
        assertEquals(4495, repository.lastLoggedPriceCheck?.dirtyBaselinePriceCents)
    }

    @Test
    fun closeShoppingSession_revertsToBaselineAndResetsState() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2000, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 1500, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(
                    strategyId = null,
                    wireguardConfig = "wg-test-config",
                ),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            vpnConfigStore = FakeVpnConfigStore(
                baselineConfigId = "user:baseline",
                configs = listOf(
                    VpnConfigRecord(
                        id = "user:baseline",
                        source = com.fairprice.app.engine.VpnConfigSource.USER,
                        displayName = "Baseline",
                        providerHint = "proton",
                        enabled = true,
                    ),
                ),
            ),
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onDirtyBaselineInputChanged("4495")
        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()
        assertTrue(!viewModel.uiState.value.showBrowser)

        viewModel.onCloseShoppingSession()
        advanceUntilIdle()

        assertEquals(2, vpnEngine.connectCalls)
        assertEquals("user:baseline", vpnEngine.lastConnectConfig)
        assertEquals(0, vpnEngine.disconnectCalls)
        assertTrue(viewModel.uiState.value.processState is HomeProcessState.Idle)
        assertEquals("", viewModel.uiState.value.dirtyBaselineInputRaw)
        assertTrue(!viewModel.uiState.value.showBrowser)
    }

    @Test
    fun closeShoppingSession_whenBaselineReconnectFails_setsError() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine().apply {
            connectResults = mutableListOf(
                Result.success(Unit),
                Result.failure(IllegalStateException("baseline reconnect failed")),
            )
        }
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            vpnConfigStore = FakeVpnConfigStore(
                baselineConfigId = "user:baseline",
                configs = listOf(
                    VpnConfigRecord(
                        id = "user:baseline",
                        source = com.fairprice.app.engine.VpnConfigSource.USER,
                        displayName = "Baseline",
                        providerHint = "proton",
                        enabled = true,
                    ),
                ),
            ),
            extractionEngine = FakeExtractionEngine(
                extractionResults = mutableListOf(
                    Result.success(ExtractionResult(priceCents = 2000, tactics = emptyList())),
                    Result.success(ExtractionResult(priceCents = 1500, tactics = emptyList())),
                ),
            ),
            strategyEngine = FakeStrategyEngine(
                result = Result.success(
                    StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
                ),
            ),
        )

        viewModel.onDirtyBaselineInputChanged("4495")
        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        viewModel.onCloseShoppingSession()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.processState is HomeProcessState.Error)
    }

    @Test
    fun appClosing_revertsToBaselineWhenShoppingVpnIsActive() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2000, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 1500, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(
                    strategyId = null,
                    wireguardConfig = "wg-test-config",
                ),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            vpnConfigStore = FakeVpnConfigStore(
                baselineConfigId = "user:baseline",
                configs = listOf(
                    VpnConfigRecord(
                        id = "user:baseline",
                        source = com.fairprice.app.engine.VpnConfigSource.USER,
                        displayName = "Baseline",
                        providerHint = "proton",
                        enabled = true,
                    ),
                ),
            ),
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()
        assertEquals(1, vpnEngine.connectCalls)

        viewModel.onAppClosing()
        advanceUntilIdle()

        assertEquals(2, vpnEngine.connectCalls)
        assertEquals("user:baseline", vpnEngine.lastConnectConfig)
        assertEquals(0, vpnEngine.disconnectCalls)
    }

    @Test
    fun baselineFailure_logsFallbackAndAllowsClearNetBrowsing() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.failure(IllegalStateException("baseline timeout")),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(
                    strategyId = null,
                    wireguardConfig = "wg-test-config",
                ),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(0, strategyEngine.determineCalls)
        assertEquals(0, vpnEngine.connectCalls)
        assertEquals(0, vpnEngine.disconnectCalls)
        assertEquals(2, extractionEngine.loadCalls)
        assertEquals(1, repository.logCalls)
        assertEquals(false, repository.lastLoggedPriceCheck?.extractionSuccessful)
        assertEquals(0, repository.lastLoggedPriceCheck?.baselinePriceCents)
        assertEquals(0, repository.lastLoggedPriceCheck?.foundPriceCents)
        assertEquals("degraded_pre_spoof_failed", repository.lastLoggedPriceCheck?.outcome)
        val persistedTactics =
            repository.lastLoggedPriceCheck?.rawExtractionData
                ?.get("detected_tactics")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
        assertEquals(emptyList<String>(), persistedTactics)
        assertTrue(viewModel.uiState.value.showBrowser)
        val processState = viewModel.uiState.value.processState
        assertTrue(processState is HomeProcessState.Error)
        assertTrue((processState as HomeProcessState.Error).message.contains("Sniffer Pass failed"))
    }

    @Test
    fun snifferFail_cleanControlFallback_success_thenSpoof_success_degradedButNotFailed() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.failure(IllegalStateException("sniffer blocked")),
                Result.success(ExtractionResult(priceCents = 2050, tactics = listOf("challenge_wall"))),
                Result.success(ExtractionResult(priceCents = 1800, tactics = listOf("challenge_wall"))),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = "s_fallback", wireguardConfig = "wg-test-config"),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
            shadowCleanControlSampler = { false },
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(3, extractionEngine.loadCalls)
        assertEquals(
            expectedPassUrls(
                "https://example.com/p/123" to TOKEN_SNIFFER_INTEL,
                "https://example.com/p/123" to TOKEN_CLEAN_CONTROL_INTEL,
                "https://example.com/p/123" to TOKEN_YALE_SMART,
            ),
            extractionEngine.loadedUrls,
        )
        assertEquals(listOf("sniffer", "clean_control", "spoof"), repository.lastLoggedAttempts.map { it.phase })
        assertEquals("degraded_sniffer_fallback_success", repository.lastLoggedPriceCheck?.outcome)
        assertEquals(true, repository.lastLoggedPriceCheck?.extractionSuccessful)
        val tacticSource =
            repository.lastLoggedPriceCheck?.rawExtractionData?.get("tactic_source_pass")?.jsonPrimitive?.content
        assertEquals("clean_control", tacticSource)
        assertTrue(viewModel.uiState.value.processState is HomeProcessState.Success)
    }

    @Test
    fun snifferSuccess_shadowSampled_runsCleanControlTelemetry_onlySingleSpoofChain() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2500, tactics = listOf("cookie_tracking"))),
                Result.success(ExtractionResult(priceCents = 2450, tactics = listOf("control_signal"))),
                Result.success(ExtractionResult(priceCents = 1900, tactics = listOf("cookie_tracking"))),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = "s_shadow", wireguardConfig = "wg-test-config"),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
            shadowCleanControlSampler = { true },
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(3, extractionEngine.loadCalls)
        assertEquals(
            expectedPassUrls(
                "https://example.com/p/123" to TOKEN_SNIFFER_INTEL,
                "https://example.com/p/123" to TOKEN_CLEAN_CONTROL_INTEL,
                "https://example.com/p/123" to TOKEN_YALE_SMART,
            ),
            extractionEngine.loadedUrls,
        )
        assertEquals(1, vpnEngine.connectCalls)
        assertEquals(listOf("sniffer", "clean_control", "spoof"), repository.lastLoggedAttempts.map { it.phase })
        val summary = (viewModel.uiState.value.processState as HomeProcessState.Success).summary
        assertEquals("sniffer", summary.tacticSourcePass)
        assertEquals("shadow", summary.cleanControlExecutionMode)
        assertEquals(true, summary.shadowSampled)
        assertEquals("$25.00", summary.snifferPrice)
        assertEquals("$24.50", summary.cleanControlPrice)
    }

    @Test
    fun spoofedExtractionFailure_disconnectsVpnAndShowsError() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2000, tactics = emptyList())),
                Result.failure(IllegalStateException("spoofed failed")),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(
                    strategyId = null,
                    wireguardConfig = "wg-test-config",
                ),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(2, vpnEngine.connectCalls)
        assertEquals(1, vpnEngine.disconnectCalls)
        assertTrue(!viewModel.uiState.value.showBrowser)
        assertTrue(viewModel.uiState.value.processState is HomeProcessState.Error)
    }

    @Test
    fun spoofedExtraction_retriesOnceOnLikelyGeckoLifecycleFailure() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2000, tactics = emptyList())),
                Result.failure(IllegalStateException("WindowEventDispatcher win is null")),
                Result.success(ExtractionResult(priceCents = 1500, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(3, extractionEngine.loadCalls)
        assertEquals(1, repository.logCalls)
        assertEquals(0, vpnEngine.disconnectCalls)
        assertTrue(viewModel.uiState.value.processState is HomeProcessState.Success)
    }

    @Test
    fun spoofedExtraction_retryIsBoundedToSingleRetry() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2200, tactics = emptyList())),
                Result.failure(IllegalStateException("WindowEventDispatcher win is null")),
                Result.failure(IllegalStateException("WindowEventDispatcher win is null")),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(3, extractionEngine.loadCalls)
        assertEquals(1, repository.logCalls)
        assertEquals(1, vpnEngine.disconnectCalls)
        assertTrue(viewModel.uiState.value.processState is HomeProcessState.Error)
    }

    @Test
    fun spoofedExtraction_requestsCleanSessionPolicy() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2200, tactics = emptyList())),
                Result.success(ExtractionResult(priceCents = 1800, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(2, extractionEngine.loadCalls)
        val baselineRequest = extractionEngine.requests[0]
        val spoofRequest = extractionEngine.requests[1]
        assertEquals(true, baselineRequest.cleanSessionRequired)
        assertEquals(false, baselineRequest.strictTrackingProtection)
        assertEquals(true, spoofRequest.cleanSessionRequired)
        assertEquals(true, spoofRequest.strictTrackingProtection)
        assertEquals("spoof", spoofRequest.phase)
    }

    @Test
    fun cleanSessionPreparationFailure_failsClosedWithRetryGuidance() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2200, tactics = emptyList())),
                Result.failure(CleanSessionPreparationException("wipe failed")),
                Result.failure(CleanSessionPreparationException("wipe failed again")),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals(3, extractionEngine.loadCalls)
        assertEquals(1, vpnEngine.disconnectCalls)
        val processState = viewModel.uiState.value.processState
        assertTrue(processState is HomeProcessState.Error)
        assertTrue(
            (processState as HomeProcessState.Error)
                .message
                .contains("bounded retry", ignoreCase = true),
        )
        val diagnostics =
            repository.lastLoggedPriceCheck?.rawExtractionData
                ?.get("diagnostics")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                .orEmpty()
        assertTrue(
            diagnostics.any { it.contains("clean session", ignoreCase = true) },
        )
        val spoofAttempts = repository.lastLoggedAttempts.filter { it.phase == "spoof" }
        assertTrue(spoofAttempts.isNotEmpty())
        assertTrue(
            spoofAttempts.all {
                it.appliedLevers?.jsonObject?.get("tracking_protection")?.jsonPrimitive?.content == "strict"
            },
        )
        assertTrue(
            spoofAttempts.all {
                it.appliedLevers?.jsonObject?.get("amnesia_protocol")?.jsonPrimitive?.content == "false"
            },
        )
    }

    @Test
    fun normalFlow_waitsForStabilizationGateBeforeSpoofExtraction() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2500, tactics = listOf("cookie_tracking"))),
                Result.success(ExtractionResult(priceCents = 1900, tactics = listOf("cookie_tracking"))),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        runCurrent()

        assertEquals(1, extractionEngine.loadCalls)
        val processState = viewModel.uiState.value.processState
        assertTrue(processState is HomeProcessState.Processing)
        assertEquals(
            "Stabilizing secure tunnel...",
            (processState as HomeProcessState.Processing).message,
        )

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, extractionEngine.loadCalls)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(2, extractionEngine.loadCalls)
        assertTrue(viewModel.uiState.value.processState is HomeProcessState.Success)
    }

    @Test
    fun permissionRequired_emitsPermissionRequestAndWaitsForResult() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine().apply {
            connectResults = mutableListOf(
                Result.failure(VpnPermissionRequiredException(Intent("vpn.permission"))),
            )
        }
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2000, tactics = listOf("cookie_tracking"))),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )
        val emittedIntentDeferred = backgroundScope.async {
            viewModel.vpnPermissionRequests.first()
        }

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()
        val requestIntent = emittedIntentDeferred.await()

        assertEquals(1, extractionEngine.loadCalls)
        assertEquals(1, strategyEngine.determineCalls)
        assertEquals(1, vpnEngine.connectCalls)
        assertEquals(0, repository.logCalls)
        assertEquals("vpn.permission", requestIntent.action)
        assertTrue(viewModel.uiState.value.processState is HomeProcessState.Processing)
    }

    @Test
    fun permissionGranted_resumesFromVpnStepWithoutRerunningBaseline() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine().apply {
            connectResults = mutableListOf(
                Result.failure(VpnPermissionRequiredException(Intent("vpn.permission"))),
                Result.success(Unit),
            )
        }
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2100, tactics = listOf("hidden_canvas"))),
                Result.success(ExtractionResult(priceCents = 1700, tactics = listOf("hidden_canvas"))),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()
        assertEquals(1, extractionEngine.loadCalls)
        assertEquals(1, strategyEngine.determineCalls)
        assertEquals(1, vpnEngine.connectCalls)

        viewModel.onVpnPermissionResult(granted = true)
        runCurrent()

        assertEquals(1, extractionEngine.loadCalls)
        val processState = viewModel.uiState.value.processState
        assertTrue(processState is HomeProcessState.Processing)
        assertEquals(
            "Stabilizing secure tunnel...",
            (processState as HomeProcessState.Processing).message,
        )

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, extractionEngine.loadCalls)

        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(2, extractionEngine.loadCalls)
        assertEquals(1, strategyEngine.determineCalls)
        assertEquals(2, vpnEngine.connectCalls)
        assertEquals(1, repository.logCalls)
        assertTrue(viewModel.uiState.value.processState is HomeProcessState.Success)
    }

    @Test
    fun permissionDenied_fallsBackToClearNetWithMessage() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine().apply {
            connectResults = mutableListOf(
                Result.failure(VpnPermissionRequiredException(Intent("vpn.permission"))),
            )
        }
        val extractionEngine = FakeExtractionEngine(
            extractionResults = mutableListOf(
                Result.success(ExtractionResult(priceCents = 2100, tactics = emptyList())),
            ),
        )
        val strategyEngine = FakeStrategyEngine(
            result = Result.success(
                StrategyResult(strategyId = null, wireguardConfig = "wg-test-config"),
            ),
        )
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = vpnEngine,
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        viewModel.onVpnPermissionResult(granted = false)
        advanceUntilIdle()

        assertEquals(1, extractionEngine.loadCalls)
        assertEquals(1, strategyEngine.determineCalls)
        assertEquals(1, vpnEngine.connectCalls)
        assertEquals(1, repository.logCalls)
        assertTrue(viewModel.uiState.value.showBrowser)
        val processState = viewModel.uiState.value.processState
        assertTrue(processState is HomeProcessState.Error)
        assertTrue((processState as HomeProcessState.Error).message.contains("permission denied"))
    }

    @Test
    fun partialAttemptLogFailure_marksOutcomeAndAddsDiagnostics() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            logResult = Result.success(
                RunLogResult(
                    attemptsInserted = false,
                    attemptInsertError = "request timeout",
                    retailerIntelInserted = true,
                ),
            )
        }
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = FakeVpnEngine(),
            extractionEngine = FakeExtractionEngine(
                extractionResults = mutableListOf(
                    Result.success(ExtractionResult(priceCents = 2500, tactics = emptyList())),
                    Result.success(ExtractionResult(priceCents = 2000, tactics = emptyList())),
                ),
            ),
            strategyEngine = FakeStrategyEngine(
                result = Result.success(StrategyResult(strategyId = null, wireguardConfig = "wg-test-config")),
            ),
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        assertEquals("partial_log_failure", repository.lastLoggedPriceCheck?.outcome)
        val processState = viewModel.uiState.value.processState as HomeProcessState.Success
        assertEquals("partial_log_failure", processState.summary.outcome)
        assertTrue(processState.summary.diagnostics.any { it.contains("Partial telemetry") })
    }

    @Test
    fun retailerIntelWriteFailure_surfacesDiagnosticsWithoutFailingRun() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            logResult = Result.success(
                RunLogResult(
                    attemptsInserted = true,
                    retailerIntelInserted = false,
                    retailerIntelError = "retailer conflict",
                ),
            )
        }
        val viewModel = HomeViewModel(
            repository = repository,
            vpnEngine = FakeVpnEngine(),
            extractionEngine = FakeExtractionEngine(
                extractionResults = mutableListOf(
                    Result.success(ExtractionResult(priceCents = 3000, tactics = listOf("cookie_tracking"))),
                    Result.success(ExtractionResult(priceCents = 2500, tactics = listOf("cookie_tracking"))),
                ),
            ),
            strategyEngine = FakeStrategyEngine(
                result = Result.success(StrategyResult(strategyId = null, wireguardConfig = "wg-test-config")),
            ),
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()

        val processState = viewModel.uiState.value.processState as HomeProcessState.Success
        assertEquals("success", processState.summary.outcome)
        assertTrue(processState.summary.diagnostics.any { it.contains("Retailer intel write skipped") })
    }

    private class FakeStrategyEngine(
        var result: Result<StrategyResult>,
    ) : PricingStrategyEngine {
        var determineCalls: Int = 0
        var lastBaselineTactics: List<String> = emptyList()
        var lastUrl: String? = null

        override suspend fun determineStrategy(
            url: String,
            baselineTactics: List<String>,
        ): Result<StrategyResult> {
            determineCalls += 1
            lastUrl = url
            lastBaselineTactics = baselineTactics
            return result
        }
    }

    private class FakeVpnEngine : VpnEngine {
        var connectCalls: Int = 0
        var disconnectCalls: Int = 0
        var lastConnectConfig: String? = null
        var connectResults: MutableList<Result<Unit>> = mutableListOf(Result.success(Unit))
        var disconnectResult: Result<Unit> = Result.success(Unit)

        override suspend fun connect(configStr: String): Result<Unit> {
            connectCalls += 1
            lastConnectConfig = configStr
            return if (connectResults.isEmpty()) {
                Result.success(Unit)
            } else {
                connectResults.removeAt(0)
            }
        }

        override suspend fun disconnect(): Result<Unit> {
            disconnectCalls += 1
            return disconnectResult
        }
    }

    private class FakeExtractionEngine(
        var extractionResults: MutableList<Result<ExtractionResult>> = mutableListOf(
            Result.success(ExtractionResult(priceCents = 1099, tactics = emptyList())),
        ),
    ) : ExtractionEngine {
        private val sessionState = MutableStateFlow<GeckoSession?>(null)
        override val currentSession: StateFlow<GeckoSession?> = sessionState
        var loadCalls: Int = 0
        val loadedUrls: MutableList<String> = mutableListOf()
        val requests: MutableList<ExtractionRequest> = mutableListOf()

        override suspend fun loadAndExtract(
            url: String,
            request: ExtractionRequest,
        ): Result<ExtractionResult> {
            loadCalls += 1
            loadedUrls += url
            requests += request
            return if (extractionResults.isEmpty()) {
                Result.failure(IllegalStateException("No extraction result configured"))
            } else {
                extractionResults.removeAt(0)
            }
        }
    }

    private class FakeRepository : FairPriceRepository {
        var logCalls: Int = 0
        var lastLoggedPriceCheck: PriceCheck? = null
        var lastLoggedAttempts: List<PriceCheckAttempt> = emptyList()
        var logResult: Result<RunLogResult> = Result.success(
            RunLogResult(
                attemptsInserted = true,
                retailerIntelInserted = true,
            ),
        )
        var lifetimeSavingsCents: Int = 0

        override suspend fun logPriceCheck(priceCheck: PriceCheck): Result<RunLogResult> {
            logCalls += 1
            lastLoggedPriceCheck = priceCheck
            return logResult
        }

        override suspend fun logPriceCheckRun(
            priceCheck: PriceCheck,
            attempts: List<PriceCheckAttempt>,
        ): Result<RunLogResult> {
            lastLoggedAttempts = attempts
            val payload = if (logResult.getOrNull()?.attemptsInserted == false) {
                priceCheck.copy(outcome = "partial_log_failure")
            } else {
                priceCheck
            }
            return logPriceCheck(payload)
        }

        override suspend fun fetchLifetimePotentialSavingsCents(): Result<Int> {
            return Result.success(lifetimeSavingsCents)
        }
    }

    private class FakeVpnConfigStore(
        private val baselineConfigId: String? = null,
        private val configs: List<VpnConfigRecord> = emptyList(),
    ) : VpnConfigStore {
        override fun listUserConfigs(): List<VpnConfigRecord> = configs
        override fun listEnabledUserConfigs(): List<VpnConfigRecord> = configs.filter { it.enabled }
        override fun readUserConfigText(configId: String): Result<String> = Result.success("")
        override fun importUserConfig(
            displayName: String,
            rawConfigText: String,
        ): Result<VpnConfigRecord> = Result.failure(IllegalStateException("Not implemented for tests"))
        override fun setUserConfigEnabled(configId: String, enabled: Boolean): Result<Unit> = Result.success(Unit)
        override fun getBaselineConfigId(): String? = baselineConfigId
        override fun setBaselineConfigId(configId: String): Result<Unit> = Result.success(Unit)
    }

    private fun expectedPassUrls(vararg entries: Pair<String, String>): List<String> {
        return entries.map { (url, token) -> expectedUrlWithToken(url, token) }
    }

    private fun expectedUrlWithToken(url: String, token: String): String {
        val hashIndex = url.indexOf('#')
        if (hashIndex < 0) {
            return "$url#$TOKEN_KEY=$token"
        }
        val base = url.substring(0, hashIndex)
        val existingHash = url.substring(hashIndex + 1)
        val hashParts = existingHash
            .split("&")
            .filter { it.isNotBlank() }
            .filterNot { part ->
                part.substringBefore('=').trim().equals(TOKEN_KEY, ignoreCase = true)
            }
            .toMutableList()
        hashParts += "$TOKEN_KEY=$token"
        return "$base#${hashParts.joinToString("&")}"
    }
}
