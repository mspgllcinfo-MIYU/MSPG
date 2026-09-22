package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.mspgllc.iqpuchin.board.GridCoord

/**
 * Pure presentation: draws the floor tiles wherever GameView tells it
 * to. Never touches game state and never decides where things go on
 * screen -- that's GameView's job (see recomputeLayout there). See
 * PlayerRenderer for Poi and QubeRenderer for the QUBEs themselves.
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
     *
     * FRONT-ALIGNED-TEST-01: [axisMinorPx] defaults to the isometric
     * camera's own constant so every existing caller is unaffected;
     * GameView passes [RenderConfig.BOARD_AXIS_MINOR_PX_FRONT_ALIGNED]
     * (0f) instead when its RenderMode is FRONT_ALIGNED, so these bounds
     * always match whatever axis lean the projection GameView actually
     * builds this frame is using -- otherwise the screen-fit scale/
     * origin computed from this would assume a lean that isn't there.
     */
    fun boardBounds(gridWidth: Int, gridDepth: Int, axisMinorPx: Float = RenderConfig.BOARD_AXIS_MINOR_PX): BoardBounds {
        val major = RenderConfig.BOARD_AXIS_MAJOR_PX
        val minor = axisMinorPx
        val tileHalfExtentPx = (major + minor) / 2f
        val qubeLiftPx = RenderConfig.QUBE_HEIGHT_SCALE_PX * RenderConfig.QUBE_MAX_LIFT_WORLD_UNITS
        // AZUSAN-PLAYER-01: was RenderConfig.PLAYER_SIZE_PX (the old
        // vector marker's diameter) -- now the sprite's own normalized
        // on-screen height, so a QUBE toppling through the back row and
        // Azusan standing there both still stay clear of the screen top.
        val topClearancePx = maxOf(RenderConfig.PLAYER_SPRITE_TARGET_HEIGHT_PX, qubeLiftPx)
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

    /**
     * [projection] is built once per frame by GameView and shared with
     * PlayerRenderer/QubeRenderer, so the floor grid and everything on
     * it are guaranteed to line up -- this never builds its own.
     *
     * FRONT-ALIGNED-TEST-01: [axisMinorPx] (unscaled -- multiplied by
     * [scale] below, same as the major axis already was) defaults to the
     * isometric constant so existing callers are unaffected; GameView
     * passes the same per-frame value here as it used to build
     * [projection] itself, so each tile's own drawn shape (see
     * [drawTile]) never drifts out of sync with where [projection]
     * actually places that tile's center.
     */
    fun draw(
        canvas: Canvas,
        projection: IsoProjection,
        gridWidth: Int,
        gridDepth: Int,
        markedCoord: GridCoord?,
        scale: Float,
        axisMinorPx: Float = RenderConfig.BOARD_AXIS_MINOR_PX
    ) {
        val axisMajor = RenderConfig.BOARD_AXIS_MAJOR_PX * scale
        val axisMinor = axisMinorPx * scale

        for (x in 0 until gridWidth) {
            for (z in 0 until gridDepth) {
                val marked = markedCoord != null && markedCoord.x == x && markedCoord.z == z
                drawTile(canvas, projection, x.toFloat(), z.toFloat(), axisMajor, axisMinor, marked)
            }
        }
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
}
