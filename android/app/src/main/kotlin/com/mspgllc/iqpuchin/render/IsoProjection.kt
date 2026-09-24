package com.mspgllc.iqpuchin.render

/**
 * Projects a (gridX, gridZ, worldHeight) point to 2D screen pixels.
 * Takes already-scaled sizes so the projection's shape (the two basis
 * vectors below) and the display scale stay separate concerns.
 * [worldHeight] defaults to 0 (ground level) -- the floor tiles and
 * player marker never pass it; only the QUBE's toppling motion does.
 *
 * CAMERA-PROGRESSION-PHASE-1: gridX and gridZ each now get their own
 * fully independent (screenX, screenY) coefficient pair --
 * gridX -> (axisXx, axisXy) and gridZ -> (axisZx, axisZy) -- generalizing
 * the previous single (axisMajorPx, axisMinorPx) pair (which forced the
 * two basis vectors to always be perpendicular and equal-length, i.e. a
 * plain rotation) so a future round can express a true non-square
 * dimetric lean per Stage without touching this formula again. This
 * round only ever constructs it with axisXx=axisMajorPx, axisXy=
 * axisMinorPx, axisZx=-axisMinorPx, axisZy=axisMajorPx (see GameView's
 * two call sites), which is *exactly* the old formula -- screenX =
 * gridX*axisMajorPx - gridZ*axisMinorPx, screenY = gridX*axisMinorPx +
 * gridZ*axisMajorPx -- algebraically substituted in, so every existing
 * caller's output is unchanged.
 *
 * The small secondary ("minor"-equivalent) contribution on each axis is
 * not optional polish: a QUBE's RIGHT face only varies in gridZ and
 * worldHeight, and its FRONT/BACK faces only vary in gridX and
 * worldHeight (see QubeRenderer) -- a face collapses to a zero-width
 * line in screen space unless its two varying axes map to non-parallel
 * screen vectors, so gridZ's screen-X term (and gridX's screen-Y term)
 * must stay non-zero whenever a lean is in effect.
 */
class IsoProjection(
    private val axisXx: Float,
    private val axisXy: Float,
    private val axisZx: Float,
    private val axisZy: Float,
    private val originX: Float,
    private val originY: Float,
    private val heightScalePx: Float = 0f
) {
    fun toScreen(gridX: Float, gridZ: Float, worldHeight: Float = 0f): FloatArray {
        val screenX = originX + gridX * axisXx + gridZ * axisZx
        val screenY = originY + gridX * axisXy + gridZ * axisZy - worldHeight * heightScalePx
        return floatArrayOf(screenX, screenY)
    }
}
