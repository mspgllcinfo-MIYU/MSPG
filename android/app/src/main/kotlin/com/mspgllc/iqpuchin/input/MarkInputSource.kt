package com.mspgllc.iqpuchin.input

import android.view.View

/**
 * STEP 4's MARK button. Only wires the click straight through to
 * [InputActionListener.onMarkRequested] -- same no-debounce, no-delay
 * philosophy as [DirectionalInputSource].
 */
class MarkInputSource(private val markButton: View) {
    fun attach(listener: InputActionListener) {
        markButton.setOnClickListener { listener.onMarkRequested() }
    }
}
