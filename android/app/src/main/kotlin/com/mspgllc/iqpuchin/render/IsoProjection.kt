package com.mspgllc.iqpuchin.render

/**
 * Projects a (gridX, gridZ, worldHeight) point to 2D screen pixels.
 * Takes already-scaled sizes so the projection's shape (the two basis
 * vectors below) and the display scale stay separate concerns.
 * [worldHeight] defaults to 0 (ground level) -- the floor tiles and
 * player marker never pass it; only the QUBE's toppling motion does.
 *
 * gridZ (the board's depth axis, and the direction every NORMAL QUBE
 * travels) is the *dominant* contributor to screen Y, with only a small
 * secondary contribution to screen X; gridX (the board's width axis) is
 * the dominant contributor to screen X, with only a small secondary
 * contribution to screen Y. In other words the two basis vectors are
 * gridX -> (+axisMajorPx, +axisMinorPx) and gridZ -> (-axisMinorPx,
 * +axisMajorPx). This is what makes a QUBE advancing in +gridZ move
 * almost straight down the screen (a vertical corridor) instead of the
 * 45-degree diagonal a classic mirrored 2:1 dimetric projection (both
 * axes weighted equally in both screen dimensions) produces.
 *
 * The small secondary ("minor") contribution on each axis is not
 * optional polish: a QUBE's RIGHT face only varies in gridZ and
 * worldHeight, and its FRONT/BACK faces only vary in gridX and
 * worldHeight (see QubeRenderer) -- a face collapses to a zero-width
 * line in screen space unless its two varying axes map to non-parallel
 * screen vectors, so gridZ's screen-X term (and gridX's screen-Y term)
 * must stay non-zero.
 */
class IsoProjection(
    private val axisMajorPx: Float,
    private val axisMinorPx: Float,
    private val originX: Float,
    private val originY: Float,
    private val heightScalePx: Float = 0f
) {
    fun toScreen(gridX: Float, gridZ: Float, worldHeight: Float = 0f): FloatArray {
        val screenX = originX + gridX * axisMajorPx - gridZ * axisMinorPx
        val screenY = originY + gridX * axisMinorPx + gridZ * axisMajorPx - worldHeight * heightScalePx
        return floatArrayOf(screenX, screenY)
    }
}
