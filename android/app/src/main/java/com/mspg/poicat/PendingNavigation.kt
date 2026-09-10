package com.mspg.poicat

import androidx.compose.runtime.mutableStateOf

/**
 * A one-shot navigation request from outside the Compose tree (currently:
 * MainActivity's incoming SEND-intent handling) into AppRoot, mirroring the
 * requestAlbumTab pattern PoiScreen already uses for Home's アルバム entry.
 * AppRoot observes these, applies them, then resets both back to null so
 * the request fires exactly once.
 */
object PendingNavigation {
    var requestedTab by mutableStateOf<AppTab?>(null)
    var requestedRoom by mutableStateOf<ChatRoom?>(null)
}
