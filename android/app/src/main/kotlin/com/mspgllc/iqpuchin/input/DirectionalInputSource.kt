package com.mspgllc.iqpuchin.input

import android.view.View
import com.mspgllc.iqpuchin.board.Direction

/**
 * STEP 2's touch input: four directional buttons. Only wires clicks
 * straight through to [InputActionListener.onMoveRequested] -- no
 * debouncing, no delay, so a tap moves the player on the very next call.
 */
class DirectionalInputSource(
    private val upButton: View,
    private val downButton: View,
    private val leftButton: View,
    private val rightButton: View
) {
    fun attach(listener: InputActionListener) {
        upButton.setOnClickListener { listener.onMoveRequested(Direction.NORTH) }
        downButton.setOnClickListener { listener.onMoveRequested(Direction.SOUTH) }
        leftButton.setOnClickListener { listener.onMoveRequested(Direction.WEST) }
        rightButton.setOnClickListener { listener.onMoveRequested(Direction.EAST) }
    }
}
