package com.mspgllc.iqpuchin.render

/** Projects a (gridX, gridZ) grid-space point to 2D screen pixels using a
 * standard 2:1 isometric layout. Takes already-scaled tile sizes so the
 * projection angle (the 2:1 ratio) and the display scale stay separate
 * concerns. */
class IsoProjection(
    private val tileWidthPx: Float,
    private val tileHeightPx: Float,
    private val originX: Float,
    private val originY: Float
) {
    fun toScreen(gridX: Float, gridZ: Float): FloatArray {
        val screenX = originX + (gridX - gridZ) * (tileWidthPx / 2f)
        val screenY = originY + (gridX + gridZ) * (tileHeightPx / 2f)
        return floatArrayOf(screenX, screenY)
    }
}
