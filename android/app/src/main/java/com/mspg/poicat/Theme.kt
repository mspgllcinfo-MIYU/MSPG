package com.mspg.poicat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PoiCatColorScheme = lightColorScheme(
    primary = Color(0xFFD98A9C),
    background = Color(0xFFF7F3EF),
    surface = Color(0xFFF7F3EF),
)

@Composable
fun PoiCatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PoiCatColorScheme, content = content)
}
