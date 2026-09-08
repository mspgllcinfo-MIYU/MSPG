package com.mspg.poicat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

// Step2: bottom nav design tokens, matching HomeScreen's local palette by value
// (HomeScreen.kt itself is not touched — these are duplicated on purpose until
// Step0 promotes a shared set into Theme.kt).
private val NavInk = Color(0xFF201E1D) // 墨色
private val NavPink = Color(0xFFD98A9C) // existing app pink, used only as a soft selection pill
private val NavGold = Color(0xFFC9A66B) // restrained accent — the top hairline only

private fun iconFor(tab: AppTab): ImageVector = when (tab) {
    AppTab.HOME -> Icons.Default.Home
    AppTab.POI -> Icons.Default.CheckCircle
    AppTab.CAL -> Icons.Default.DateRange
    AppTab.MEMO -> Icons.Default.Edit
    AppTab.AI -> Icons.Default.Face
}

@Composable
fun AppRoot() {
    var selectedTab by remember { mutableStateOf(AppTab.AI) }
    var showAlbum by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op: reminders just won't show if denied */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (showAlbum) {
                AlbumScreen(onBack = { showAlbum = false })
            } else {
                when (selectedTab) {
                    AppTab.HOME -> HomeScreen(onNavigate = { selectedTab = it }, onOpenAlbum = { showAlbum = true })
                    AppTab.POI -> PoiScreen()
                    AppTab.CAL -> CalendarScreen()
                    AppTab.MEMO -> MemoScreen()
                    AppTab.AI -> AiChatScreen()
                }
            }
        }
        BottomTabBar(selectedTab = selectedTab, onSelect = { showAlbum = false; selectedTab = it })
    }
}

@Composable
private fun BottomTabBar(selectedTab: AppTab, onSelect: (AppTab) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // A hairline, not a heavy divider — just enough to separate the nav from
        // whatever screen (including Home's dark hero) sits above it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NavGold.copy(alpha = 0.25f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 3.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 10.dp, horizontal = 6.dp),
        ) {
            AppTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(if (selected) NavPink.copy(alpha = 0.22f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = iconFor(tab),
                            contentDescription = tab.label,
                            tint = NavInk.copy(alpha = if (selected) 1f else 0.5f),
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            color = NavInk.copy(alpha = if (selected) 1f else 0.5f),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
