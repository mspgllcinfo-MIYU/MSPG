package com.mspgllc.iqpuchin.input

import android.view.View
import com.mspgllc.iqpuchin.board.Direction

/**
 * STEP 2's touch input: four directional buttons. Only wires clicks
 * straight through to [InputActionListener.onMoveRequested] -- no
 * debouncing, no delay, so a tap moves the player on the very next call.
 *
 * The button-to-[Direction] mapping is deliberately not the "obvious"
 * left=WEST/right=EAST pairing: on the current camera (see
 * IsoProjection/RenderConfig) the board's left/right screen sense came
 * out mirrored from that naive assumption, which made the left/right
 * D-pad buttons move the player the wrong way on screen. Swapping only
 * the left/right targets here (leaving up/down alone, since those
 * already read correctly) fixes that without touching the projection,
 * GridCoord, or Direction itself -- this class is the one place device
 * screen-feel and the logical Direction enum get connected.
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
        leftButton.setOnClickListener { listener.onMoveRequested(Direction.EAST) }
        rightButton.setOnClickListener { listener.onMoveRequested(Direction.WEST) }
    }
}
