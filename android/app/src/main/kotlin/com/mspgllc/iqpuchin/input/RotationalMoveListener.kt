package com.mspgllc.iqpuchin.input

/**
 * VIRTUAL-STICK-ROTATIONAL-PROTOTYPE-01: a second, separate listener
 * interface for the 360-degree analog stick -- deliberately NOT added to
 * [InputActionListener] itself, so [SwipeInputView]/[VirtualStickView]/
 * [DirectionalPadView] and their existing discrete
 * [InputActionListener.onMoveRequested] contract are untouched by this
 * round. [RotationalStickView] reports a continuous angle rather than a
 * quantized [com.mspgllc.iqpuchin.board.Direction] -- GameView (which
 * implements both interfaces) is the only place that turns this into
 * actual grid movement, one cardinal step at a time, via the existing,
 * unmodified BoardLogic.movePlayer.
 */
interface RotationalMoveListener {
    /** Fired continuously while the stick is held outside its dead zone.
     * [angleRad] is the raw touch angle (atan2(dy, dx) in screen space,
     * dy/dx measured from the stick's center) -- never quantized here. */
    fun onStickVector(angleRad: Float)

    /** Fired once the stick returns to its dead zone, or is released --
     * GameView stops advancing the continuous glide and snaps the visual
     * position back onto the current logical cell. */
    fun onStickIdle()
}
