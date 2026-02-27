package com.fairprice.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = FairPricePrimary,
    onPrimary = FairPriceOnPrimary,
    primaryContainer = FairPricePrimaryContainer,
    secondary = FairPriceSecondary,
    background = FairPriceBackground,
    surface = FairPriceSurface,
    onBackground = FairPriceOnBackground,
    onSurface = FairPriceOnSurface,
    error = FairPriceError,
)

@Composable
fun FairPriceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content,
    )
}
