package com.fairprice.app.viewmodel

import android.content.Intent
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.data.models.PriceCheck
import com.fairprice.app.data.models.PriceCheckAttempt
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.ExtractionResult
import com.fairprice.app.engine.PricingStrategyEngine
import com.fairprice.app.engine.StrategyResult
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
        assertEquals("$19.99", summary.baselinePrice)
        assertEquals("$12.99", summary.spoofedPrice)
        assertEquals(listOf("cookie_tracking"), summary.tactics)
        assertEquals("Default Strategy (stub)", summary.strategyName)
        assertEquals("wg-test-config", summary.vpnConfig)
        assertEquals(listOf("wg-test-config"), summary.attemptedConfigs)
        assertEquals("wg-test-config", summary.finalConfig)
        assertEquals(0, summary.retryCount)
        assertEquals("success", summary.outcome)
        assertEquals("https://example.com/p/123", strategyEngine.lastUrl)
        assertEquals(
            listOf("https://example.com/p/123", "https://example.com/p/123"),
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
        assertEquals(listOf(canonicalUrl, canonicalUrl), extractionEngine.loadedUrls)
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
        assertEquals(listOf(originalShortUrl, originalShortUrl), extractionEngine.loadedUrls)
        assertEquals(originalShortUrl, repository.lastLoggedPriceCheck?.productUrl)
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
            extractionEngine = extractionEngine,
            strategyEngine = strategyEngine,
        )

        viewModel.onUrlInputChanged("https://example.com/p/123")
        viewModel.onCheckPriceClicked()
        advanceUntilIdle()
        assertTrue(!viewModel.uiState.value.showBrowser)

        viewModel.onCloseShoppingSession()
        advanceUntilIdle()

        assertEquals(2, vpnEngine.connectCalls)
        assertEquals("baseline_saltlake_ut-US-UT-137.conf", vpnEngine.lastConnectConfig)
        assertEquals(0, vpnEngine.disconnectCalls)
        assertTrue(viewModel.uiState.value.processState is HomeProcessState.Idle)
        assertTrue(!viewModel.uiState.value.showBrowser)
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
        assertEquals("baseline_saltlake_ut-US-UT-137.conf", vpnEngine.lastConnectConfig)
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
        assertEquals(1, extractionEngine.loadCalls)
        assertEquals(1, repository.logCalls)
        assertEquals(false, repository.lastLoggedPriceCheck?.extractionSuccessful)
        assertEquals(0, repository.lastLoggedPriceCheck?.baselinePriceCents)
        assertEquals(0, repository.lastLoggedPriceCheck?.foundPriceCents)
        val persistedTactics =
            repository.lastLoggedPriceCheck?.rawExtractionData
                ?.get("detected_tactics")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
        assertEquals(emptyList<String>(), persistedTactics)
        assertTrue(viewModel.uiState.value.showBrowser)
        val processState = viewModel.uiState.value.processState
        assertTrue(processState is HomeProcessState.Error)
        assertTrue((processState as HomeProcessState.Error).message.contains("continue shopping normally"))
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

        override suspend fun loadAndExtract(url: String): Result<ExtractionResult> {
            loadCalls += 1
            loadedUrls += url
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
        var logResult: Result<Unit> = Result.success(Unit)

        override suspend fun logPriceCheck(priceCheck: PriceCheck): Result<Unit> {
            logCalls += 1
            lastLoggedPriceCheck = priceCheck
            return logResult
        }

        override suspend fun logPriceCheckRun(
            priceCheck: PriceCheck,
            attempts: List<PriceCheckAttempt>,
        ): Result<Unit> {
            return logPriceCheck(priceCheck)
        }
    }
}
