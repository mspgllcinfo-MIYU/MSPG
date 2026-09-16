package com.mspgllc.iqpuchin.input

import android.widget.Button

/**
 * STEP 7's single ACTION button, replacing the separate MARK and
 * ACTIVATE buttons. Wires the click straight through to
 * [InputActionListener.onActionRequested] -- same no-debounce, no-delay
 * philosophy as [DirectionalInputSource] -- then relabels the button
 * from [isAwaitingMark] so the next press's meaning stays visible during
 * testing.
 */
class ActionInputSource(private val actionButton: Button) {
    fun attach(listener: InputActionListener, isAwaitingMark: () -> Boolean) {
        actionButton.setOnClickListener {
            listener.onActionRequested()
            actionButton.text = if (isAwaitingMark()) "MARK" else "ACTIVATE"
        }
    }
}
