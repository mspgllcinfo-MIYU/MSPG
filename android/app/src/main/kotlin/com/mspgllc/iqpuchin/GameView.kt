package com.mspgllc.iqpuchin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.mspgllc.iqpuchin.board.BoardConfig
import com.mspgllc.iqpuchin.board.BoardLogic
import com.mspgllc.iqpuchin.board.CaptureSystem
import com.mspgllc.iqpuchin.board.Direction
import com.mspgllc.iqpuchin.board.GameState
import com.mspgllc.iqpuchin.board.GameStateController
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
 * Owns the board's logical state (player + every NORMAL QUBE) and draws
 * it. Player movement stays purely event-driven (see [onMoveRequested])
 * with zero added delay, but each QUBE's toppling is time-based, so this
 * also runs a per-frame [Choreographer] loop that advances every QUBE's
 * motion and redraws every frame regardless of input.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), InputActionListener {

    /** Pairs a [Qube] with the [QubeMotion] that advances it. QubeMotion
     * keeps its own Qube reference private, so GameView needs to hold
     * both together to hand each QUBE to the renderer -- this is a plain
     * data holder for a list element, not a per-QUBE variable. */
    private data class QubeInstance(val qube: Qube, val motion: QubeMotion)

    private val boardLogic = BoardLogic()
    private val markController = MarkController()
    private val captureSystem = CaptureSystem()
    private val gameStateController = GameStateController()
    private val boardRenderer = BoardRenderer()
    private val qubeRenderer = QubeRenderer()

    // STEP 6: every NORMAL QUBE lives in this one collection -- no
    // qube1/qube2/qube3 style variables. Each entry owns its own GridCoord
    // (via its Qube) and its own rotation/timing state (via its
    // QubeMotion), so they advance completely independently even though
    // they currently share the same direction and timing constants.
    // Starts with 3 QUBEs across the back row (STEP 6 initial layout).
    // A successful CAPTURE removes exactly the matching entry from this
    // list (see onActionRequested) -- Qube.kt/QubeMotion.kt themselves
    // are unmodified.
    private val qubes: MutableList<QubeInstance> = createInitialQubes()

    private fun createInitialQubes(): MutableList<QubeInstance> {
        val startXs = listOf(1, 3, 5)
        return startXs.map { x ->
            val qube = Qube(startCoord = GridCoord(x, 0), direction = Direction.SOUTH)
            QubeInstance(qube, QubeMotion(qube))
        }.toMutableList()
    }

    private var originX = 0f
    private var originY = 0f
    private var displayScale = 1f

    private val density = resources.displayMetrics.density
    // STEP 7 placeholder-only "HIT" banner -- not part of any real HUD.
    private val hitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 40f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    /**
     * Judges collision purely from logical GridCoords -- PLAYER's current
     * cell against every QUBE's current cell -- never anything about how
     * they're currently animated/drawn. Called after any event that can
     * change either side's logical coordinate (a player move, or a QUBE
     * motion tick landing on a new cell), so a HIT is caught the instant
     * it becomes true regardless of which side moved into the other.
     */
    private fun checkCollision() {
        gameStateController.checkCollision(boardLogic.playerPosition, qubes.map { it.qube.coord })
    }

    private var lastFrameTimeNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val deltaMs = if (lastFrameTimeNanos == 0L) 0L else (frameTimeNanos - lastFrameTimeNanos) / 1_000_000L
            lastFrameTimeNanos = frameTimeNanos

            for (instance in qubes) instance.motion.update(deltaMs)
            checkCollision()

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
        val axisMajor = RenderConfig.BOARD_AXIS_MAJOR_PX * displayScale
        val axisMinor = RenderConfig.BOARD_AXIS_MINOR_PX * displayScale
        val heightScale = RenderConfig.QUBE_HEIGHT_SCALE_PX * displayScale
        val projection = IsoProjection(axisMajor, axisMinor, originX, originY, heightScale)

        boardRenderer.draw(
            canvas,
            projection,
            BoardConfig.GRID_WIDTH,
            BoardConfig.GRID_DEPTH,
            boardLogic.playerPosition,
            markController.markedCoord,
            displayScale
        )

        // Painter's algorithm across QUBEs too: farther-back cells
        // (smaller z) drawn first. gridZ is now the dominant screen-Y
        // contributor (see IsoProjection), so z alone is the accurate
        // depth-ordering key -- same ordering principle QubeRenderer
        // already applies to a single QUBE's own faces.
        for (instance in qubes.sortedBy { it.qube.coord.z }) {
            qubeRenderer.draw(canvas, instance.qube, instance.motion, projection)
        }

        if (gameStateController.state == GameState.HIT) {
            canvas.drawText("HIT", width / 2f, 60f * density, hitTextPaint)
        }
    }

    override fun onMoveRequested(direction: Direction) {
        if (boardLogic.movePlayer(direction)) {
            checkCollision()
            invalidate()
        }
    }

    /**
     * STEP 7: MARK and ACTIVATE stay two separate systems underneath
     * (MarkController / CaptureSystem, both unmodified in their own
     * judging logic) -- only the single ACTION button's dispatch is
     * unified here, based on whether a mark is currently pending.
     */
    override fun onActionRequested() {
        val currentMark = markController.markedCoord
        if (currentMark == null) {
            markController.markAt(boardLogic.playerPosition)
        } else {
            // Logical grid coordinates are unique per QUBE, so at most
            // one entry can ever match -- one MARK captures at most one
            // QUBE.
            val index = qubes.indexOfFirst { captureSystem.isCaptured(currentMark, it.qube.coord) }
            if (index >= 0) {
                qubes.removeAt(index)
            }
            markController.clear()
        }
        invalidate()
    }

    /** Read by [com.mspgllc.iqpuchin.input.ActionInputSource] to decide
     * whether the ACTION button should currently read "MARK" (no mark
     * pending) or "ACTIVATE" (a mark is pending). */
    fun isAwaitingMark(): Boolean = markController.markedCoord == null
}
