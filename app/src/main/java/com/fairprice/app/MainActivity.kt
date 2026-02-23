package com.fairprice.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fairprice.app.ui.HomeScreen
import com.fairprice.app.ui.theme.FairPriceTheme
import com.fairprice.app.viewmodel.HomeViewModel

class MainActivity : ComponentActivity() {
    private val homeViewModel: HomeViewModel by viewModels()

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
