package com.fairprice.app

import android.content.Intent
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
import com.fairprice.app.engine.DefaultPricingStrategyEngine
import com.fairprice.app.engine.GeckoExtractionEngine
import com.fairprice.app.engine.WireguardVpnEngine
import com.fairprice.app.ui.HomeScreen
import com.fairprice.app.ui.theme.FairPriceTheme
import com.fairprice.app.viewmodel.HomeViewModel

class MainActivity : ComponentActivity() {
    private val repository: FairPriceRepository by lazy {
        FairPriceRepositoryImpl(SupabaseClientProvider.client)
    }
    private val vpnEngine by lazy { WireguardVpnEngine() }
    private val extractionEngine by lazy { GeckoExtractionEngine(applicationContext) }
    private val strategyEngine by lazy { DefaultPricingStrategyEngine() }

    private val homeViewModel: HomeViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                    return HomeViewModel(
                        repository = repository,
                        vpnEngine = vpnEngine,
                        extractionEngine = extractionEngine,
                        strategyEngine = strategyEngine,
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            onUrlChanged = homeViewModel::onUrlInputChanged,
                            onCheckPriceClicked = homeViewModel::onCheckPriceClicked,
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

    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            homeViewModel.onSharedTextReceived(sharedText)
        }
    }
}
