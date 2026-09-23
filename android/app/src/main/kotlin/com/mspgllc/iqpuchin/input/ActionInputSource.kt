package com.mspgllc.iqpuchin.input

import android.util.Log

/**
 * STEP 7's single ACTION button, replacing the separate MARK and
 * ACTIVATE buttons. CONTROL-SIMPLE-01: [PawActionButtonView] now reports
 * two distinct gestures (see its class doc) instead of one generic
 * click -- a plain TAP wires straight through to
 * [InputActionListener.onActionRequested] (MARK/ACTIVATE only), and the
 * downward slide wires to [InputActionListener.onPunchGestureRequested]
 * (CAT_PUNCH only) -- same no-debounce, no-delay philosophy as
 * [VirtualStickView] for both. Either path then updates the paw's
 * gold/glow state from [isAwaitingMark] so the next press's meaning
 * stays visible without any text (UI-CUTE-01 replaced the old
 * MARK/ACTIVATE label with a paw glyph whose glow carries that same
 * information) -- CAT_PUNCH never itself changes MARK state, but this
 * keeps the glow refresh symmetric/defensive rather than assuming that.
 */
class ActionInputSource(private val actionButton: PawActionButtonView) {
    fun attach(listener: InputActionListener, isAwaitingMark: () -> Boolean) {
        actionButton.setAwaitingActivate(!isAwaitingMark())
        actionButton.setOnTapClick {
            listener.onActionRequested()
            actionButton.setAwaitingActivate(!isAwaitingMark())
        }
        actionButton.setOnPunchGesture {
            // CAT-PUNCH-TRACE-01: diagnostic-only log confirming the punch
            // gesture was actually received from the view layer, before
            // listener.onPunchGestureRequested() (unchanged below) runs.
            // Tag literal duplicated from GameView.CAT_PUNCH_TRACE_TAG since
            // that constant is private to GameView and this is a separate
            // class/package.
            Log.d("CAT_PUNCH_TRACE", "PUNCH_INPUT_RECEIVED")
            listener.onPunchGestureRequested()
            actionButton.setAwaitingActivate(!isAwaitingMark())
        }
    }
}
