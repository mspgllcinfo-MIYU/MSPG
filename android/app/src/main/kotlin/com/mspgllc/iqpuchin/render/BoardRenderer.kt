package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.mspgllc.iqpuchin.board.GridCoord
import kotlin.math.abs

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
     * CAMERA-PROGRESSION-PHASE-2: generalized to [IsoProjection]'s own
     * gridX -> (axisXx, axisXy) / gridZ -> (axisZx, axisZy) basis, in
     * place of the old axisMajor/axisMinor pair -- by actually projecting
     * the board's own four extreme grid corners (origin-relative, i.e.
     * with originX/originY = 0) and taking their min/max, rather than a
     * formula that assumed a particular axis sign (e.g. axisMinorPx >= 0).
     * This makes no assumption about the sign of any of the four
     * coefficients, so it stays correct for any future per-Stage lean,
     * including ones where axisZx/axisXy are negative or where all four
     * are positive.
     *
     * A tile itself is not a point, so each extreme corner also needs its
     * own half-extent added outward -- the general bound for a
     * parallelogram spanned by (axisXx,axisXy) and (axisZx,axisZy) is
     * (|axisXx|+|axisZx|)/2 in X and (|axisXy|+|axisZy|)/2 in Y (half the
     * sum of the absolute basis components on each screen axis; see
     * [drawTile]'s own corner derivation for why this bound is exact for
     * this shape). Algebraically verified to reduce to the exact previous
     * formula for both axisMinorPx=0 (FRONT_ALIGNED) and any nonzero
     * axisMinorPx (CURRENT_ISOMETRIC) when axisXx=major, axisXy=minor,
     * axisZx=-minor, axisZy=major -- this round always constructs it that
     * way (see GameView), so on-screen board size/position are unchanged.
     */
    fun boardBounds(
        gridWidth: Int,
        gridDepth: Int,
        axisXx: Float,
        axisXy: Float,
        axisZx: Float,
        axisZy: Float
    ): BoardBounds {
        val qubeLiftPx = RenderConfig.QUBE_HEIGHT_SCALE_PX * RenderConfig.QUBE_MAX_LIFT_WORLD_UNITS
        // AZUSAN-PLAYER-01: was RenderConfig.PLAYER_SIZE_PX (the old
        // vector marker's diameter) -- now the sprite's own normalized
        // on-screen height, so a QUBE toppling through the back row and
        // Azusan standing there both still stay clear of the screen top.
        val topClearancePx = maxOf(RenderConfig.PLAYER_SPRITE_TARGET_HEIGHT_PX, qubeLiftPx)

        val maxGx = (gridWidth - 1).toFloat()
        val maxGz = (gridDepth - 1).toFloat()
        val cornerXs = floatArrayOf(
            0f * axisXx + 0f * axisZx,
            maxGx * axisXx + 0f * axisZx,
            0f * axisXx + maxGz * axisZx,
            maxGx * axisXx + maxGz * axisZx
        )
        val cornerYs = floatArrayOf(
            0f * axisXy + 0f * axisZy,
            maxGx * axisXy + 0f * axisZy,
            0f * axisXy + maxGz * axisZy,
            maxGx * axisXy + maxGz * axisZy
        )
        val minX = cornerXs.min()
        val maxX = cornerXs.max()
        val minY = cornerYs.min()
        val maxY = cornerYs.max()

        val marginX = (abs(axisXx) + abs(axisZx)) / 2f
        val marginY = (abs(axisXy) + abs(axisZy)) / 2f

        return BoardBounds(
            leftPx = -(minX - marginX),
            rightPx = maxX + marginX,
            topPx = -(minY - marginY) + topClearancePx,
            bottomPx = maxY + marginY
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
     * CAMERA-PROGRESSION-PHASE-2: [axisXx]/[axisXy]/[axisZx]/[axisZy]
     * (unscaled -- multiplied by [scale] below, same as before) replace
     * the old single [axisMinorPx]; GameView passes the same per-frame
     * coefficients here as it used to build [projection] itself, so each
     * tile's own drawn shape (see [drawTile]) never drifts out of sync
     * with where [projection] actually places that tile's center.
     */
    fun draw(
        canvas: Canvas,
        projection: IsoProjection,
        gridWidth: Int,
        gridDepth: Int,
        markedCoord: GridCoord?,
        scale: Float,
        axisXx: Float,
        axisXy: Float,
        axisZx: Float,
        axisZy: Float
    ) {
        val sXx = axisXx * scale
        val sXy = axisXy * scale
        val sZx = axisZx * scale
        val sZy = axisZy * scale

        for (x in 0 until gridWidth) {
            for (z in 0 until gridDepth) {
                val marked = markedCoord != null && markedCoord.x == x && markedCoord.z == z
                drawTile(canvas, projection, x.toFloat(), z.toFloat(), sXx, sXy, sZx, sZy, marked)
            }
        }
    }

    /**
     * A floor tile's screen shape is the parallelogram spanned by
     * [IsoProjection]'s two basis vectors, gridX -> (axisXx, axisXy) and
     * gridZ -> (axisZx, axisZy) -- its four corners sit at the tile's
     * projected center plus/minus half of each basis vector independently
     * (per this round's own "center ± Xbasis/2 ± Zbasis/2" spec), traced
     * in the cyclic order (-X-Z) -> (+X-Z) -> (+X+Z) -> (-X+Z) so each
     * consecutive corner differs by exactly one full basis vector (a
     * non-self-intersecting loop for any [axisXx]/[axisXy]/[axisZx]/
     * [axisZy], not just the previous symmetric-perpendicular case).
     * Algebraically verified to reduce to the exact previous halfSum/
     * halfDiff corners when axisXx=major, axisXy=minor, axisZx=-minor,
     * axisZy=major (this round's only actual construction).
     */
    private fun drawTile(
        canvas: Canvas,
        projection: IsoProjection,
        gx: Float,
        gz: Float,
        axisXx: Float,
        axisXy: Float,
        axisZx: Float,
        axisZy: Float,
        marked: Boolean
    ) {
        val p = projection.toScreen(gx, gz)
        val halfXx = axisXx / 2f
        val halfXy = axisXy / 2f
        val halfZx = axisZx / 2f
        val halfZy = axisZy / 2f
        val path = Path().apply {
            moveTo(p[0] - halfXx - halfZx, p[1] - halfXy - halfZy)
            lineTo(p[0] + halfXx - halfZx, p[1] + halfXy - halfZy)
            lineTo(p[0] + halfXx + halfZx, p[1] + halfXy + halfZy)
            lineTo(p[0] - halfXx + halfZx, p[1] - halfXy + halfZy)
            close()
        }
        canvas.drawPath(path, if (marked) markedFloorPaint else floorPaint)
        canvas.drawPath(path, floorOutline)
    }
}
