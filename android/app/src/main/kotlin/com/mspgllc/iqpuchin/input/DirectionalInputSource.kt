package com.mspgllc.iqpuchin.input

import android.view.View
import com.mspgllc.iqpuchin.board.Direction

/**
 * STEP 2's touch input: four directional buttons. Only wires clicks
 * straight through to [InputActionListener.onMoveRequested] -- no
 * debouncing, no delay, so a tap moves the player on the very next call.
 *
 * The button-to-[Direction] mapping is not necessarily the "obvious"
 * left=WEST/right=EAST pairing -- it is whatever real-device review of
 * the current camera (see IsoProjection/RenderConfig) says actually
 * moves the player left/right on screen. Left/right were swapped once
 * already (to EAST/WEST) after an earlier camera change, and confirmed
 * on-device to have overshot back to backwards, so they are swapped
 * again here, landing back on left=WEST/right=EAST. Up/down are left
 * alone throughout, since those have read correctly since STEP 2. This
 * class stays the one place device screen-feel and the logical
 * Direction enum get connected -- the projection, GridCoord, and
 * Direction itself are never touched to fix this.
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
