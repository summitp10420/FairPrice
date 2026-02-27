package com.fairprice.app.viewmodel

import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.data.models.PriceCheck
import com.fairprice.app.engine.ExtractionEngine
import com.fairprice.app.engine.PricingStrategyEngine
import com.fairprice.app.engine.StrategyResult
import com.fairprice.app.engine.VpnEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
        val extractionEngine = FakeExtractionEngine()
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
        assertEquals(0, vpnEngine.connectCalls)
        assertEquals(0, vpnEngine.disconnectCalls)
        assertEquals(0, extractionEngine.loadCalls)
        assertEquals(0, repository.logCalls)

        val processState = viewModel.uiState.value.processState
        assertTrue(processState is HomeProcessState.Error)
        val errorState = processState as HomeProcessState.Error
        assertTrue(errorState.message.contains("Strategy resolution failed"))
    }

    @Test
    fun strategySuccess_usesResolvedConfigAndPersistsStrategyId() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vpnEngine = FakeVpnEngine()
        val extractionEngine = FakeExtractionEngine(extractionResult = Result.success(1299))
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
        assertEquals(1, vpnEngine.connectCalls)
        assertEquals("wg-test-config", vpnEngine.lastConnectConfig)
        assertEquals(1, vpnEngine.disconnectCalls)
        assertEquals(1, extractionEngine.loadCalls)
        assertEquals(1, repository.logCalls)
        assertNotNull(repository.lastLoggedPriceCheck)
        assertEquals("strat_test_123", repository.lastLoggedPriceCheck?.strategyId)
    }

    private class FakeStrategyEngine(
        var result: Result<StrategyResult>,
    ) : PricingStrategyEngine {
        var determineCalls: Int = 0

        override suspend fun determineStrategy(url: String): Result<StrategyResult> {
            determineCalls += 1
            return result
        }
    }

    private class FakeVpnEngine : VpnEngine {
        var connectCalls: Int = 0
        var disconnectCalls: Int = 0
        var lastConnectConfig: String? = null
        var connectResult: Result<Unit> = Result.success(Unit)
        var disconnectResult: Result<Unit> = Result.success(Unit)

        override suspend fun connect(configStr: String): Result<Unit> {
            connectCalls += 1
            lastConnectConfig = configStr
            return connectResult
        }

        override suspend fun disconnect(): Result<Unit> {
            disconnectCalls += 1
            return disconnectResult
        }
    }

    private class FakeExtractionEngine(
        var extractionResult: Result<Int> = Result.success(1099),
    ) : ExtractionEngine {
        private val sessionState = MutableStateFlow<GeckoSession?>(null)
        override val currentSession: StateFlow<GeckoSession?> = sessionState
        var loadCalls: Int = 0

        override suspend fun loadAndExtract(url: String): Result<Int> {
            loadCalls += 1
            return extractionResult
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
    }
}
