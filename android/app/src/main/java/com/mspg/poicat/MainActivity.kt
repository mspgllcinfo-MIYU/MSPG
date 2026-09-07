package com.mspg.poicat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ChatRepository.init(applicationContext)

        lifecycleScope.launch {
            runCatching { AuthRepository.ensureSignedIn() }
        }

        setContent {
            PoiCatTheme {
                AppRoot()
            }
        }
    }
}
