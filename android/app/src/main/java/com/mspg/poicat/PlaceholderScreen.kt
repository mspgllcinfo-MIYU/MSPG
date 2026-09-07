package com.mspg.poicat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Placeholder for screens not yet reconstructed from the original app
 * (Home / Poi / Calendar / Memo). Only the AI chat screen has been
 * rebuilt so far; these are stand-ins so navigation between all five
 * tabs works while the rest is implemented incrementally.
 */
@Composable
fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$title 準備中")
    }
}
