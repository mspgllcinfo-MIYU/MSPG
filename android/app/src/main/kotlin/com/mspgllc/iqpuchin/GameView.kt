package com.mspgllc.iqpuchin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.mspgllc.iqpuchin.board.BoardConfig
import com.mspgllc.iqpuchin.board.BoardLogic
import com.mspgllc.iqpuchin.board.CaptureSystem
import com.mspgllc.iqpuchin.board.Direction
import com.mspgllc.iqpuchin.board.GridCoord
import com.mspgllc.iqpuchin.board.MarkController
import com.mspgllc.iqpuchin.board.Qube
import com.mspgllc.iqpuchin.board.QubeMotion
import com.mspgllc.iqpuchin.input.InputActionListener
import com.mspgllc.iqpuchin.render.BoardRenderer
import com.mspgllc.iqpuchin.render.IsoProjection
import com.mspgllc.iqpuchin.render.QubeRenderer
import com.mspgllc.iqpuchin.render.RenderConfig
import kotlin.math.min

/**
 * Owns the board's logical state (player + the one QUBE) and draws it.
 * Player movement stays purely event-driven (see [onMoveRequested]) with
 * zero added delay, but the QUBE's toppling is time-based, so this now
 * also runs a per-frame [Choreographer] loop that advances
 * [qubeMotion] and redraws every frame regardless of input.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), InputActionListener {

    private val boardLogic = BoardLogic()
    private val markController = MarkController()
    private val captureSystem = CaptureSystem()
    private val boardRenderer = BoardRenderer()
    private val qubeRenderer = QubeRenderer()

    // The one NORMAL QUBE for STEP 3: enters from the far (back) edge in
    // the center column and advances toward the player. STEP 3 does not
    // yet handle a QUBE reaching the player's cell -- see BoardLogic.
    // STEP 5: nullable so a successful CAPTURE can remove it from game
    // state entirely (Qube/QubeMotion themselves are unmodified).
    private var qube: Qube? = Qube(
        startCoord = GridCoord(BoardConfig.GRID_WIDTH / 2, 0),
        direction = Direction.SOUTH
    )
    private var qubeMotion: QubeMotion? = qube?.let { QubeMotion(it) }

    private var originX = 0f
    private var originY = 0f
    private var displayScale = 1f

    private var lastFrameTimeNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val deltaMs = if (lastFrameTimeNanos == 0L) 0L else (frameTimeNanos - lastFrameTimeNanos) / 1_000_000L
            lastFrameTimeNanos = frameTimeNanos

            qubeMotion?.update(deltaMs)

            invalidate()
            if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

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
        val bounds = boardRenderer.boardBounds(BoardConfig.GRID_WIDTH, BoardConfig.GRID_DEPTH)

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

        // One projection per frame, shared by both renderers, so the
        // floor grid and the QUBE are always perfectly aligned.
        val tileW = RenderConfig.TILE_WIDTH_PX * displayScale
        val tileH = RenderConfig.TILE_HEIGHT_PX * displayScale
        val heightScale = RenderConfig.QUBE_HEIGHT_SCALE_PX * displayScale
        val projection = IsoProjection(tileW, tileH, originX, originY, heightScale)

        boardRenderer.draw(
            canvas,
            projection,
            BoardConfig.GRID_WIDTH,
            BoardConfig.GRID_DEPTH,
            boardLogic.playerPosition,
            markController.markedCoord,
            displayScale
        )

        val currentQube = qube
        val currentMotion = qubeMotion
        if (currentQube != null && currentMotion != null) {
            qubeRenderer.draw(canvas, currentQube, currentMotion, projection)
        }
    }

    override fun onMoveRequested(direction: Direction) {
        if (boardLogic.movePlayer(direction)) {
            invalidate()
        }
    }

    override fun onMarkRequested() {
        markController.markAt(boardLogic.playerPosition)
        invalidate()
    }

    override fun onActivateRequested() {
        val currentQube = qube ?: return
        if (captureSystem.isCaptured(markController.markedCoord, currentQube.coord)) {
            qube = null
            qubeMotion = null
            invalidate()
        }
    }
}
