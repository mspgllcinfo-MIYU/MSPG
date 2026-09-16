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
import com.mspgllc.iqpuchin.render.ChuruLifeRenderer
import com.mspgllc.iqpuchin.render.IsoProjection
import com.mspgllc.iqpuchin.render.PlayerRenderer
import com.mspgllc.iqpuchin.render.PoiHitReaction
import com.mspgllc.iqpuchin.render.QubeRenderer
import com.mspgllc.iqpuchin.render.RenderConfig
import com.mspgllc.iqpuchin.sound.QubeSoundTracker
import com.mspgllc.iqpuchin.sound.SoundEvent
import com.mspgllc.iqpuchin.sound.SoundEventPlayer
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

    /** Pairs a [Qube] with the [QubeMotion] that advances it and the
     * [QubeSoundTracker] that watches both for SE purposes. QubeMotion
     * keeps its own Qube reference private, so GameView needs to hold
     * these together to hand each QUBE to the renderer -- this is a
     * plain data holder for a list element, not a per-QUBE variable. */
    private data class QubeInstance(val qube: Qube, val motion: QubeMotion, val soundTracker: QubeSoundTracker)

    private val boardLogic = BoardLogic()
    private val markController = MarkController()
    private val captureSystem = CaptureSystem()
    private val gameStateController = GameStateController()
    private val boardRenderer = BoardRenderer()
    private val playerRenderer = PlayerRenderer()
    private val qubeRenderer = QubeRenderer()

    // VISUAL-01/SOUND-01/SOUND-02: Poi's brief HIT flinch and the shared
    // SE funnel (SOUND-02: now backed by real SoundPool playback, see
    // SoundEventPlayer). Both are purely cosmetic/presentational --
    // neither is consulted by any game-logic check below, only
    // triggered once a logic result (a new HIT, a MARK placed, a
    // CAPTURE, a QUBE's own roll/land) is observed.
    private val hitReaction = PoiHitReaction()
    private val soundEventPlayer = SoundEventPlayer(context)
    // CATPUNCH-01: purely cosmetic, like hitReaction above -- reads
    // gameStateController.life each frame, never written back to it.
    private val churuRenderer = ChuruLifeRenderer()

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
            val motion = QubeMotion(qube)
            QubeInstance(qube, motion, QubeSoundTracker(qube, motion))
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

    // CATPUNCH-01: GAME_OVER is a full, non-reverting overlay -- unlike
    // the "HIT x{n}" debug text above, which is a brief informational
    // banner that never blocks anything.
    private val gameOverDimPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val gameOverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 56f * density
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
     *
     * GameStateController.checkCollision now does its own new-hit edge
     * detection internally (see its inContact tracking) and reports the
     * result directly, so this just forwards that Boolean into the
     * cosmetic reaction/SE hook -- neither of which ever feeds back into
     * the judgement itself.
     */
    private fun checkCollision() {
        val isNewHit = gameStateController.checkCollision(boardLogic.playerPosition, qubes.map { it.qube.coord })
        if (isNewHit) {
            hitReaction.trigger()
            soundEventPlayer.play(SoundEvent.POI_HIT)
        }
    }

    private var lastFrameTimeNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val deltaMs = if (lastFrameTimeNanos == 0L) 0L else (frameTimeNanos - lastFrameTimeNanos) / 1_000_000L
            lastFrameTimeNanos = frameTimeNanos

            // CATPUNCH-01: GAME_OVER freezes the board (QUBE motion, SE
            // tracking, collision checks) instead of continuing to play
            // out underneath the overlay. Nothing else in this block's
            // own timing/order changed.
            if (gameStateController.state != GameState.GAME_OVER) {
                for (instance in qubes) {
                    instance.motion.update(deltaMs)
                    instance.soundTracker.update(soundEventPlayer, boardLogic.playerPosition)
                }
                hitReaction.update(deltaMs)
                gameStateController.update(deltaMs)
                checkCollision()
            }

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

        // Same axisMajor/axisMinor/origin for every renderer this frame,
        // so the floor grid, Poi, and every QUBE are always perfectly
        // aligned to the same cells. Floor tiles and the player marker
        // never pass a worldHeight, so `projection`'s own height value is
        // irrelevant to them; QUBEs get a second instance built with
        // RenderConfig.QUBE_VISUAL_HEIGHT_SCALE_PX (a cosmetic-only,
        // *shorter* height than the true QUBE_HEIGHT_SCALE_PX -- see that
        // constant's doc) purely so a QUBE reads as a cube rather than an
        // elongated slab under this camera, without touching the shared
        // axis geometry (board footprint / floor tile size) at all.
        val axisMajor = RenderConfig.BOARD_AXIS_MAJOR_PX * displayScale
        val axisMinor = RenderConfig.BOARD_AXIS_MINOR_PX * displayScale
        val projection = IsoProjection(axisMajor, axisMinor, originX, originY)
        val qubeHeightScale = RenderConfig.QUBE_VISUAL_HEIGHT_SCALE_PX * displayScale
        val qubeProjection = IsoProjection(axisMajor, axisMinor, originX, originY, qubeHeightScale)

        boardRenderer.draw(
            canvas,
            projection,
            BoardConfig.GRID_WIDTH,
            BoardConfig.GRID_DEPTH,
            markController.markedCoord,
            displayScale
        )

        playerRenderer.draw(
            canvas,
            projection,
            boardLogic.playerPosition,
            displayScale,
            hitReaction.progress(),
            angerLevel()
        )

        // Painter's algorithm across QUBEs too: farther-back cells
        // (smaller z) drawn first. gridZ is now the dominant screen-Y
        // contributor (see IsoProjection), so z alone is the accurate
        // depth-ordering key -- same ordering principle QubeRenderer
        // already applies to a single QUBE's own faces.
        for (instance in qubes.sortedBy { it.qube.coord.z }) {
            qubeRenderer.draw(canvas, instance.qube, instance.motion, qubeProjection)
        }

        if (gameStateController.state == GameState.HIT) {
            canvas.drawText("HIT x${gameStateController.hitCount}", width / 2f, 60f * density, hitTextPaint)
        }

        // CATPUNCH-01: life shown as churu count, not a heart/number HUD.
        churuRenderer.draw(
            canvas,
            gameStateController.life,
            GameStateController.STARTING_LIFE,
            16f * density,
            40f * density,
            40f * density
        )

        if (gameStateController.state == GameState.GAME_OVER) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gameOverDimPaint)
            canvas.drawText("GAME OVER", width / 2f, height / 2f, gameOverTextPaint)
        }
    }

    /** CATPUNCH-01: 0 (life 3) / 1 (life 2) / 2 (life 1-0) -- cosmetic
     * only, read by PlayerRenderer to swap Azusan's expression. Never
     * consulted by anything gameplay-affecting. */
    private fun angerLevel(): Int = when {
        gameStateController.life <= 1 -> 2
        gameStateController.life == 2 -> 1
        else -> 0
    }

    override fun onMoveRequested(direction: Direction) {
        if (gameStateController.state == GameState.GAME_OVER) return
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
     *
     * CATPUNCH-01: adds a third branch, but the priority order the user
     * asked for is preserved exactly -- a pending MARK always resolves
     * as ACTIVATE first (unchanged from before), and only when no MARK
     * is pending does a QUBE within punch range pre-empt placing a new
     * MARK. No new button/gesture: this is still the single existing
     * ACTION press, one punch per press -- a 2-hit combo is just two
     * separate presses while still in range, never anything automatic
     * from Virtual Stick movement.
     */
    override fun onActionRequested() {
        if (gameStateController.state == GameState.GAME_OVER) return
        val currentMark = markController.markedCoord
        if (currentMark != null) {
            // Logical grid coordinates are unique per QUBE, so at most
            // one entry can ever match -- one MARK captures at most one
            // QUBE.
            val index = qubes.indexOfFirst { captureSystem.isCaptured(currentMark, it.qube.coord) }
            if (index >= 0) {
                qubes.removeAt(index)
                soundEventPlayer.play(SoundEvent.CAPTURE_SUCCESS)
            }
            markController.clear()
        } else {
            val punchIndex = qubes.indexOfFirst { isPunchRange(boardLogic.playerPosition, it.qube.coord) }
            if (punchIndex >= 0) {
                performPunch(punchIndex)
            } else {
                markController.markAt(boardLogic.playerPosition)
                soundEventPlayer.play(SoundEvent.MARK_SET)
            }
        }
        invalidate()
    }

    /** CATPUNCH-01: orthogonally adjacent (one cell north/south/east/west,
     * never diagonal or the same cell) -- deliberately not read from
     * anywhere else, so it can never change collision/HIT semantics. */
    private fun isPunchRange(a: GridCoord, b: GridCoord): Boolean {
        val dx = kotlin.math.abs(a.x - b.x)
        val dz = kotlin.math.abs(a.z - b.z)
        return (dx == 1 && dz == 0) || (dx == 0 && dz == 1)
    }

    /** Applies exactly one punch to the QUBE at [index]. Never touches
     * QubeMotion, so the QUBE keeps advancing on its own schedule
     * whether this punch breaks it, dents it, or the player walks away
     * -- there is no stall and no invented safe window either way. */
    private fun performPunch(index: Int) {
        val destroyed = qubes[index].qube.punch()
        if (destroyed) {
            qubes.removeAt(index)
            soundEventPlayer.play(SoundEvent.QUBE_BREAK)
        } else {
            soundEventPlayer.play(SoundEvent.PUNCH_HIT)
        }
    }

    /** Read by [com.mspgllc.iqpuchin.input.ActionInputSource] to decide
     * whether the ACTION button should currently read "MARK" (no mark
     * pending) or "ACTIVATE" (a mark is pending). */
    fun isAwaitingMark(): Boolean = markController.markedCoord == null
}
