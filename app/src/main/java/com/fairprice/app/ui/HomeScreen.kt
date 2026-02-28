package com.fairprice.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.KeyboardType
import com.fairprice.app.viewmodel.HomeProcessState
import com.fairprice.app.viewmodel.HomeUiState
import kotlin.math.roundToInt
import org.mozilla.geckoview.GeckoView

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onDirtyBaselineChanged: (String) -> Unit,
    onUrlChanged: (String) -> Unit,
    onCheckPriceClicked: () -> Unit,
    onEnterShoppingMode: () -> Unit,
    onBackToApp: () -> Unit,
    onCloseShoppingSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        val density = LocalDensity.current
        val maxOffsetX = with(density) { (maxWidth - 140.dp).toPx() }.coerceAtLeast(0f)
        val maxOffsetY = with(density) { (maxHeight - 120.dp).toPx() }.coerceAtLeast(0f)
        var backButtonOffsetX by remember { mutableFloatStateOf(0f) }
        var backButtonOffsetY by remember { mutableFloatStateOf(0f) }
        val keyboardController = LocalSoftwareKeyboardController.current

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            OutlinedTextField(
                value = formatRawCentsAsCurrency(uiState.dirtyBaselineInputRaw),
                onValueChange = onDirtyBaselineChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Price You See (Baseline)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { Text("$0.00") },
            )
            Spacer(modifier = Modifier.height(12.dp))
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
                onClick = {
                    keyboardController?.hide()
                    onCheckPriceClicked()
                },
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
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = "Summary",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
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
                        processState.summary.dirtyBaselinePrice?.let {
                            Text("Price You See (Manual): $it")
                        } ?: Text("Price You See (Manual): Skipped")
                        if (processState.summary.isVictory) {
                            Text(
                                text = "We beat them! Potential Savings: ${processState.summary.potentialSavings}",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text("Strategy Deployed: ${processState.summary.strategyName}")
                        Text("VPN Config Loaded: ${processState.summary.vpnConfig}")
                        Text(
                            "Attempted Config Chain: ${
                                processState.summary.attemptedConfigs.takeIf { it.isNotEmpty() }?.joinToString(" -> ")
                                    ?: "None"
                            }",
                        )
                        Text("Final Config: ${processState.summary.finalConfig}")
                        Text("Retry Count: ${processState.summary.retryCount}")
                        Text("Outcome: ${processState.summary.outcome}")
                        Text(
                            "Diagnostics: ${
                                processState.summary.diagnostics.takeIf { it.isNotEmpty() }?.joinToString(" | ")
                                    ?: "None"
                            }",
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onEnterShoppingMode,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Proceed to Shopping View")
                        }
                        OutlinedButton(
                            onClick = onCloseShoppingSession,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Done")
                        }
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

        if (uiState.showBrowser) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .offset { IntOffset(backButtonOffsetX.roundToInt(), backButtonOffsetY.roundToInt()) }
                    .pointerInput(maxOffsetX, maxOffsetY) {
                        detectDragGestures(
                            onDrag = { _, dragAmount ->
                                backButtonOffsetX =
                                    (backButtonOffsetX + dragAmount.x).coerceIn(-maxOffsetX, 0f)
                                backButtonOffsetY =
                                    (backButtonOffsetY + dragAmount.y).coerceIn(0f, maxOffsetY)
                            },
                        )
                    },
            ) {
                Button(onClick = onBackToApp) {
                    Text("Back to App")
                }
            }
        }
    }
}

private fun formatRawCentsAsCurrency(raw: String): String {
    val cents = raw.filter(Char::isDigit).toIntOrNull() ?: 0
    val dollars = cents / 100
    val remainder = cents % 100
    return "$" + dollars + "." + remainder.toString().padStart(2, '0')
}
