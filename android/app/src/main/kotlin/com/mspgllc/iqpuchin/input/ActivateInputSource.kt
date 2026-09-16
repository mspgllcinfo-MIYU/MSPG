package com.mspgllc.iqpuchin.input

import android.view.View

/**
 * STEP 5's ACTIVATE button. Only wires the click straight through to
 * [InputActionListener.onActivateRequested] -- same no-debounce, no-delay
 * philosophy as [DirectionalInputSource]/[MarkInputSource].
 */
class ActivateInputSource(private val activateButton: View) {
    fun attach(listener: InputActionListener) {
        activateButton.setOnClickListener { listener.onActivateRequested() }
    }
}
