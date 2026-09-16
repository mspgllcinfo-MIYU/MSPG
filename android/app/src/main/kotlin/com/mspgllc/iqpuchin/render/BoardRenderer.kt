package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.mspgllc.iqpuchin.board.GridCoord

/**
 * Pure presentation: draws the floor tiles and player wherever GameView
 * tells it to. Never touches game state and never decides where things
 * go on screen -- that's GameView's job (see recomputeLayout there).
 * Flat, placeholder shapes only (no polish/effects yet). See
 * QubeRenderer for the QUBE itself.
 */
class BoardRenderer {

    /** Distance (in baseline/unscaled px) from the drawing origin to each
     * edge of the full board, including the player marker's height above
     * the tile plane and a toppling QUBE's peak height (reached mid-arc
     * while crossing the board's back row, the closest it ever gets to
     * the top of the screen). Used by GameView to compute a scale/
     * position that fits everything -- floor, player, and QUBE -- on
     * screen. */
    data class BoardBounds(val leftPx: Float, val rightPx: Float, val topPx: Float, val bottomPx: Float)

    /**
     * Mirrors [IsoProjection]'s basis vectors: gridX -> (+MAJOR, +MINOR)
     * is the rightmost/bottommost contributor, gridZ -> (-MINOR, +MAJOR)
     * is the leftmost (via its negative X term) and bottommost
     * contributor. A floor tile's own on-screen half-extent (in either
     * screen direction) works out to (MAJOR+MINOR)/2 -- see the class doc
     * on [drawTile] for the corner derivation -- which is added at each
     * extreme cell so nothing is clipped.
     */
    fun boardBounds(gridWidth: Int, gridDepth: Int): BoardBounds {
        val major = RenderConfig.BOARD_AXIS_MAJOR_PX
        val minor = RenderConfig.BOARD_AXIS_MINOR_PX
        val tileHalfExtentPx = (major + minor) / 2f
        val qubeLiftPx = RenderConfig.QUBE_HEIGHT_SCALE_PX * RenderConfig.QUBE_MAX_LIFT_WORLD_UNITS
        val topClearancePx = maxOf(RenderConfig.PLAYER_SIZE_PX, qubeLiftPx)
        return BoardBounds(
            leftPx = (gridDepth - 1) * minor + tileHalfExtentPx,
            rightPx = (gridWidth - 1) * major + tileHalfExtentPx,
            topPx = tileHalfExtentPx + topClearancePx,
            bottomPx = (gridWidth - 1) * minor + (gridDepth - 1) * major + tileHalfExtentPx
        )
    }

    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 46, 56)
        style = Paint.Style.FILL
    }
    /** STEP 4 placeholder color for the single MARKed cell. */
    private val markedFloorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 195, 60)
        style = Paint.Style.FILL
    }
    private val floorOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(96, 106, 122)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val playerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 51)
        style = Paint.Style.FILL
    }
    private val playerOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /**
     * [projection] is built once per frame by GameView and shared with
     * QubeRenderer, so the floor grid and the QUBE are guaranteed to line
     * up -- this never builds its own.
     */
    fun draw(
        canvas: Canvas,
        projection: IsoProjection,
        gridWidth: Int,
        gridDepth: Int,
        playerPosition: GridCoord,
        markedCoord: GridCoord?,
        scale: Float
    ) {
        val axisMajor = RenderConfig.BOARD_AXIS_MAJOR_PX * scale
        val axisMinor = RenderConfig.BOARD_AXIS_MINOR_PX * scale

        for (x in 0 until gridWidth) {
            for (z in 0 until gridDepth) {
                val marked = markedCoord != null && markedCoord.x == x && markedCoord.z == z
                drawTile(canvas, projection, x.toFloat(), z.toFloat(), axisMajor, axisMinor, marked)
            }
        }

        drawPlayer(canvas, projection, playerPosition, scale)
    }

    /**
     * A floor tile's screen shape is the parallelogram spanned by
     * [IsoProjection]'s two basis vectors, gridX -> (+axisMajor,
     * +axisMinor) and gridZ -> (-axisMinor, +axisMajor) -- i.e. its four
     * corners sit at the tile's projected center plus/minus half of
     * (exVec+ezVec) and half of (exVec-ezVec). Working that out reduces
     * to a rhombus at halfSum=(axisMajor+axisMinor)/2 and
     * halfDiff=(axisMajor-axisMinor)/2 from center: top, right, bottom,
     * left corners in that cyclic order, same drawing order as before
     * this axis change.
     */
    private fun drawTile(
        canvas: Canvas,
        projection: IsoProjection,
        gx: Float,
        gz: Float,
        axisMajor: Float,
        axisMinor: Float,
        marked: Boolean
    ) {
        val p = projection.toScreen(gx, gz)
        val halfSum = (axisMajor + axisMinor) / 2f
        val halfDiff = (axisMajor - axisMinor) / 2f
        val path = Path().apply {
            moveTo(p[0] - halfDiff, p[1] - halfSum)
            lineTo(p[0] + halfSum, p[1] - halfDiff)
            lineTo(p[0] + halfDiff, p[1] + halfSum)
            lineTo(p[0] - halfSum, p[1] + halfDiff)
            close()
        }
        canvas.drawPath(path, if (marked) markedFloorPaint else floorPaint)
        canvas.drawPath(path, floorOutline)
    }

    private fun drawPlayer(canvas: Canvas, projection: IsoProjection, position: GridCoord, scale: Float) {
        val p = projection.toScreen(position.x.toFloat(), position.z.toFloat())
        val radius = (RenderConfig.PLAYER_SIZE_PX * scale) / 2f
        // Lifted above the tile plane, purely so it doesn't visually
        // blend into the floor outline it's standing on.
        val centerY = p[1] - radius
        canvas.drawCircle(p[0], centerY, radius, playerPaint)
        canvas.drawCircle(p[0], centerY, radius, playerOutline)
    }
}
