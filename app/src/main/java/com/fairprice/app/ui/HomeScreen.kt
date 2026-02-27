package com.fairprice.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fairprice.app.viewmodel.HomeProcessState
import com.fairprice.app.viewmodel.HomeUiState
import org.mozilla.geckoview.GeckoView

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onUrlChanged: (String) -> Unit,
    onCheckPriceClicked: () -> Unit,
    onEnterShoppingMode: () -> Unit,
    onCloseShoppingSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.statusBarsPadding().fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = "FairPrice",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(
                value = uiState.urlInput,
                onValueChange = onUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Product URL") },
                placeholder = { Text("https://example.com/product") },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onCheckPriceClicked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check Price")
            }
            when (val processState = uiState.processState) {
                is HomeProcessState.Idle -> Unit
                is HomeProcessState.Processing -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = processState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                is HomeProcessState.Success -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Summary",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Baseline Price: ${processState.summary.baselinePrice}")
                    Text("Spoofed Price: ${processState.summary.spoofedPrice}")
                    Text(
                        "Retailer Tactics Detected: ${
                            processState.summary.tactics.takeIf { it.isNotEmpty() }?.joinToString(", ")
                                ?: "None"
                        }",
                    )
                    Text("FairPrice Strategy Deployed: ${processState.summary.strategyName}")
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onEnterShoppingMode,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Checkout Securely")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onCloseShoppingSession,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Done")
                    }
                }
                is HomeProcessState.Error -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = processState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        uiState.activeSession?.let { session ->
            AndroidView(
                factory = { context ->
                    GeckoView(context).apply {
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }
                },
                update = { view ->
                    view.setSession(session)
                },
                modifier = if (uiState.showBrowser) {
                    Modifier.fillMaxSize().alpha(1f)
                } else {
                    Modifier.size(1.dp).alpha(0f)
                },
            )
        }
    }
}
