package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.mspgllc.iqpuchin.board.GridCoord

/**
 * Pure presentation: draws the board and player wherever GameView tells
 * it to. Never touches game state and never decides where things go on
 * screen -- that's GameView's job (see recomputeLayout there). Flat,
 * placeholder shapes only, per STEP 2 scope (no polish/effects yet).
 */
class BoardRenderer {

    /** Distance (in baseline/unscaled px) from the drawing origin to each
     * edge of the full board, including the player marker's height above
     * the tile plane. Used by GameView to compute a scale/position that
     * fits the whole board on screen. */
    data class BoardBounds(val leftPx: Float, val rightPx: Float, val topPx: Float, val bottomPx: Float)

    fun boardBounds(gridWidth: Int, gridDepth: Int): BoardBounds {
        val halfW = RenderConfig.TILE_WIDTH_PX / 2f
        val halfH = RenderConfig.TILE_HEIGHT_PX / 2f
        return BoardBounds(
            leftPx = gridDepth * halfW,
            rightPx = gridWidth * halfW,
            topPx = halfH + RenderConfig.PLAYER_SIZE_PX,
            bottomPx = (gridWidth + gridDepth - 1) * halfH
        )
    }

    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 46, 56)
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

    fun draw(
        canvas: Canvas,
        gridWidth: Int,
        gridDepth: Int,
        playerPosition: GridCoord,
        originX: Float,
        originY: Float,
        scale: Float
    ) {
        val tileW = RenderConfig.TILE_WIDTH_PX * scale
        val tileH = RenderConfig.TILE_HEIGHT_PX * scale
        val projection = IsoProjection(tileW, tileH, originX, originY)

        for (x in 0 until gridWidth) {
            for (z in 0 until gridDepth) {
                drawTile(canvas, projection, x.toFloat(), z.toFloat(), tileW, tileH)
            }
        }

        drawPlayer(canvas, projection, playerPosition, scale)
    }

    private fun drawTile(canvas: Canvas, projection: IsoProjection, gx: Float, gz: Float, tileW: Float, tileH: Float) {
        val p = projection.toScreen(gx, gz)
        val hw = tileW / 2f
        val hh = tileH / 2f
        val path = Path().apply {
            moveTo(p[0], p[1] - hh)
            lineTo(p[0] + hw, p[1])
            lineTo(p[0], p[1] + hh)
            lineTo(p[0] - hw, p[1])
            close()
        }
        canvas.drawPath(path, floorPaint)
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
