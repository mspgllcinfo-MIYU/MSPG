package com.mspgllc.iqpuchin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import com.mspgllc.iqpuchin.board.BoardConfig
import com.mspgllc.iqpuchin.board.BoardLogic
import com.mspgllc.iqpuchin.board.Direction
import com.mspgllc.iqpuchin.input.InputActionListener
import com.mspgllc.iqpuchin.render.BoardRenderer
import com.mspgllc.iqpuchin.render.RenderConfig
import kotlin.math.min

/**
 * Owns the board's logical state ([boardLogic]) and draws it. There is no
 * per-frame loop this step -- with no QUBE movement or animation, the
 * board only needs to redraw when the player actually moves, which
 * [onMoveRequested] triggers directly and synchronously (tap -> logical
 * move -> invalidate(), nothing queued or delayed in between).
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), InputActionListener {

    private val boardLogic = BoardLogic()
    private val renderer = BoardRenderer()

    private var originX = 0f
    private var originY = 0f
    private var displayScale = 1f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeLayout(w, h)
    }

    /**
     * Picks the largest scale that still fits the whole board (all
     * [BoardConfig.GRID_WIDTH] x [BoardConfig.GRID_DEPTH] cells, plus the
     * player marker's height) inside the view, then positions it with
     * only a small top margin so the play area uses as much of a Galaxy
     * portrait screen as possible instead of leaving a large blank band
     * at the top.
     */
    private fun recomputeLayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val bounds = renderer.boardBounds(BoardConfig.GRID_WIDTH, BoardConfig.GRID_DEPTH)

        val topMargin = h * RenderConfig.TOP_MARGIN_FRACTION
        val bottomMargin = h * RenderConfig.BOTTOM_MARGIN_FRACTION
        val sideMargin = w * RenderConfig.SIDE_MARGIN_FRACTION

        val availableWidth = w - 2f * sideMargin
        val availableHeight = h - topMargin - bottomMargin

        val totalWidthUnscaled = bounds.leftPx + bounds.rightPx
        val totalHeightUnscaled = bounds.topPx + bounds.bottomPx

        val scaleForWidth = availableWidth / totalWidthUnscaled
        val scaleForHeight = availableHeight / totalHeightUnscaled
        displayScale = min(scaleForWidth, scaleForHeight)

        val boardWidthPx = totalWidthUnscaled * displayScale
        val horizontalMargin = (w - boardWidthPx) / 2f
        originX = horizontalMargin + bounds.leftPx * displayScale
        originY = topMargin + bounds.topPx * displayScale
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        renderer.draw(
            canvas,
            BoardConfig.GRID_WIDTH,
            BoardConfig.GRID_DEPTH,
            boardLogic.playerPosition,
            originX,
            originY,
            displayScale
        )
    }

    override fun onMoveRequested(direction: Direction) {
        if (boardLogic.movePlayer(direction)) {
            invalidate()
        }
    }
}
