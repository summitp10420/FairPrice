package com.fairprice.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    }
}
