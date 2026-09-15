package com.mspgllc.iqpuchin.render

/** Projects a (gridX, gridZ, worldHeight) point to 2D screen pixels using
 * a standard 2:1 isometric layout. Takes already-scaled sizes so the
 * projection angle (the 2:1 ratio) and the display scale stay separate
 * concerns. [worldHeight] defaults to 0 (ground level) -- the floor tiles
 * and player marker never pass it; only the QUBE's toppling motion does. */
class IsoProjection(
    private val tileWidthPx: Float,
    private val tileHeightPx: Float,
    private val originX: Float,
    private val originY: Float,
    private val heightScalePx: Float = 0f
) {
    fun toScreen(gridX: Float, gridZ: Float, worldHeight: Float = 0f): FloatArray {
        val screenX = originX + (gridX - gridZ) * (tileWidthPx / 2f)
        val screenY = originY + (gridX + gridZ) * (tileHeightPx / 2f) - worldHeight * heightScalePx
        return floatArrayOf(screenX, screenY)
    }
}
