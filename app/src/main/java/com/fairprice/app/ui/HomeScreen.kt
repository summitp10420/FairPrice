package com.fairprice.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fairprice.app.viewmodel.HomeProcessState
import com.fairprice.app.viewmodel.HomeUiState

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onUrlChanged: (String) -> Unit,
    onCheckPriceClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .fillMaxSize()
            .padding(16.dp),
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
                    text = processState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
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
}
