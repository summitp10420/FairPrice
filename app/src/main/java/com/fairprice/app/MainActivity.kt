package com.fairprice.app

import android.content.Intent
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fairprice.app.data.FairPriceRepository
import com.fairprice.app.data.FairPriceRepositoryImpl
import com.fairprice.app.data.SupabaseClientProvider
import com.fairprice.app.engine.GeckoExtractionEngine
import com.fairprice.app.engine.LocalStrategyFallback
import com.fairprice.app.engine.RailwayStrategyClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import com.fairprice.app.ui.HomeScreen
import com.fairprice.app.ui.theme.FairPriceTheme
import com.fairprice.app.viewmodel.HomeViewModel
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val SHADOW_CLEAN_CONTROL_SAMPLE_PERCENT = 20
        private const val INSTALLATION_PREFS = "fairprice_engine_assignment"
        private const val INSTALLATION_ID_KEY = "installation_id"
    }

    private val repository: FairPriceRepository by lazy {
        FairPriceRepositoryImpl(SupabaseClientProvider.client)
    }
    private val extractionEngine by lazy { GeckoExtractionEngine(applicationContext) }
    private val installationId: String by lazy { getOrCreateInstallationId() }
    private val httpClient by lazy { HttpClient(OkHttp) }
    private val strategyResolver by lazy {
        val localFallback = LocalStrategyFallback(installationIdProvider = { installationId })
        val endpoint = BuildConfig.RAILWAY_STRATEGY_ENDPOINT
        Log.i(TAG, "Strategy resolver init: RAILWAY_STRATEGY_ENDPOINT=${if (endpoint.isNotBlank()) endpoint else "(empty)"}")
        if (endpoint.isNotBlank()) {
            Log.i(TAG, "Using RailwayStrategyClient")
            RailwayStrategyClient(
                httpClient = httpClient,
                railwayEndpoint = endpoint,
                localFallback = localFallback,
                installationIdProvider = { installationId },
            )
        } else {
            Log.i(TAG, "Using LocalStrategyFallback (no Railway endpoint)")
            localFallback
        }
    }

    private val homeViewModel: HomeViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                    return HomeViewModel(
                        repository = repository,
                        extractionEngine = extractionEngine,
                        strategyResolver = strategyResolver,
                        isAdminUser = BuildConfig.DEBUG,
                        shadowCleanControlSampler = { inputUrl ->
                            val normalized = inputUrl.trim().lowercase(Locale.US)
                            val bucket = (normalized.hashCode() and Int.MAX_VALUE) % 100
                            bucket < SHADOW_CLEAN_CONTROL_SAMPLE_PERCENT
                        },
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "FairPrice onCreate START")  // Early log to confirm Logcat sees this app
        enableEdgeToEdge()

        handleSendIntent(intent)

        setContent {
            FairPriceTheme {
                val navController = rememberNavController()
                val uiState by homeViewModel.uiState.collectAsState()

                NavHost(
                    navController = navController,
                    startDestination = "home",
                ) {
                    composable("home") {
                        HomeScreen(
                            uiState = uiState,
                            onDirtyBaselineChanged = homeViewModel::onDirtyBaselineInputChanged,
                            onUrlChanged = homeViewModel::onUrlInputChanged,
                            onCheckPriceClicked = homeViewModel::onCheckPriceClicked,
                            onAdminEngineOverrideChanged = homeViewModel::onEngineOverrideChanged,
                            onEnterShoppingMode = homeViewModel::onEnterShoppingMode,
                            onBackToApp = homeViewModel::onBackToApp,
                            onCloseShoppingSession = homeViewModel::onCloseShoppingSession,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendIntent(intent)
    }

    override fun onDestroy() {
        if (isFinishing) {
            homeViewModel.onAppClosing()
        }
        super.onDestroy()
    }

    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            homeViewModel.onSharedTextReceived(sharedText)
        }
    }

    private fun getOrCreateInstallationId(): String {
        val prefs = getSharedPreferences(INSTALLATION_PREFS, MODE_PRIVATE)
        val existing = prefs.getString(INSTALLATION_ID_KEY, null)?.trim()
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(INSTALLATION_ID_KEY, generated).apply()
        return generated
    }
}
