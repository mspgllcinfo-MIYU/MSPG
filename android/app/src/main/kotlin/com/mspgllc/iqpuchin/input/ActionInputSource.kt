package com.mspgllc.iqpuchin.input

/**
 * STEP 7's single ACTION button, replacing the separate MARK and
 * ACTIVATE buttons. Wires the tap straight through to
 * [InputActionListener.onActionRequested] -- same no-debounce, no-delay
 * philosophy as [VirtualStickView] -- then updates the paw's
 * gold/glow state from [isAwaitingMark] so the next press's meaning
 * stays visible without any text (UI-CUTE-01 replaced the old
 * MARK/ACTIVATE label with a paw glyph whose glow carries that same
 * information).
 */
class ActionInputSource(private val actionButton: PawActionButtonView) {
    fun attach(listener: InputActionListener, isAwaitingMark: () -> Boolean) {
        actionButton.setAwaitingActivate(!isAwaitingMark())
        actionButton.setOnActionClick {
            listener.onActionRequested()
            actionButton.setAwaitingActivate(!isAwaitingMark())
        }
    }
}
