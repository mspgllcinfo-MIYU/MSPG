package com.mspgllc.iqpuchin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
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
import com.mspgllc.iqpuchin.render.BrokenQubeVisual
import com.mspgllc.iqpuchin.render.ChuruLifeRenderer
import com.mspgllc.iqpuchin.render.IsoProjection
import com.mspgllc.iqpuchin.render.PlayerRenderer
import com.mspgllc.iqpuchin.render.PoiHitReaction
import com.mspgllc.iqpuchin.render.QubeRenderer
import com.mspgllc.iqpuchin.render.RenderConfig
import com.mspgllc.iqpuchin.render.TimedCosmeticFlag
import com.mspgllc.iqpuchin.sound.QubeSoundTracker
import com.mspgllc.iqpuchin.sound.SoundEvent
import com.mspgllc.iqpuchin.sound.SoundEventPlayer
import kotlin.math.hypot
import kotlin.math.min

/**
 * FRONT-ALIGNED-TEST-01: which camera GameView draws the board with.
 * CURRENT_ISOMETRIC is the existing small-lean camera (kept fully
 * intact and selectable, never deleted); FRONT_ALIGNED zeroes the
 * lean (see [RenderConfig.BOARD_AXIS_MINOR_PX_FRONT_ALIGNED]) so
 * screen up/down/left/right reads as board up/down/left/right with no
 * mental rotation. Purely a rendering choice -- GridCoord, BoardLogic,
 * QubeMotion, and every input-direction mapping are completely
 * unaware this exists; see [GameView.effectiveAxisMinorPx] for the
 * one place it's actually read.
 */
private enum class RenderMode { CURRENT_ISOMETRIC, FRONT_ALIGNED }

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

    private companion object {
        /** SCORE-SYSTEM-01: MARK/ACTIVATE scores higher than CAT_PUNCH's
         * [SCORE_PUNCH_DESTROY] -- reading the QUBE's approach and timing
         * an ACTIVATE is deliberately worth more than directly punching it
         * twice, per this round's own "reading beats brute force" design
         * intent. Both are awarded exactly once per QUBE, only at the
         * instant it is actually removed from `qubes` -- see
         * [onActionRequested]/[performPunch]. */
        const val SCORE_ACTIVATE_CAPTURE = 100
        const val SCORE_PUNCH_DESTROY = 50

        // RESTART-SYSTEM-01
        /** Durations for the three TimedCosmeticFlag windows -- named here
         * (rather than only inline at each `= TimedCosmeticFlag(...)`)
         * purely so [restartGame] can reconstruct fresh instances with
         * the exact same durations without duplicating a bare numeric
         * literal in two places. */
        const val WALK_VISUAL_DURATION_MS = 220L
        const val PUNCH_VISUAL_DURATION_MS = 180L
        const val SCORE_POPUP_DURATION_MS = 400L

        /** How long after entering GAME_OVER before [onTouchEvent] will
         * accept a RETRY tap -- see that method and the frame loop's
         * [gameOverElapsedMs] tracking. Short on purpose (this round's own
         * "don't make it wait unnecessarily long" instruction), just
         * enough to guarantee the tap/gesture that caused GAME OVER (on a
         * completely different sibling view, SwipeInputView or
         * PawActionButtonView) has already fully resolved before a new
         * ACTION_DOWN on GameView itself can count. */
        const val RETRY_INPUT_LOCKOUT_MS = 400L

        /** Same tap-vs-drag displacement idea as PawActionButtonView's own
         * GESTURE_THRESHOLD_DP, sized the same, so a RETRY tap uses the
         * same "how far is still a tap" feel already established
         * elsewhere in this app rather than a new invented value. */
        const val RETRY_TAP_SLOP_DP = 24f
    }

    /** Pairs a [Qube] with the [QubeMotion] that advances it and the
     * [QubeSoundTracker] that watches both for SE purposes. QubeMotion
     * keeps its own Qube reference private, so GameView needs to hold
     * these together to hand each QUBE to the renderer -- this is a
     * plain data holder for a list element, not a per-QUBE variable. */
    private data class QubeInstance(val qube: Qube, val motion: QubeMotion, val soundTracker: QubeSoundTracker)

    // FRONT-ALIGNED-TEST-01 default -- flip to RenderMode.CURRENT_ISOMETRIC
    // to restore the original diagonal camera. Read only by
    // effectiveAxisMinorPx() below.
    private val renderMode = RenderMode.FRONT_ALIGNED

    // RESTART-SYSTEM-01: boardLogic/gameStateController are `var`, not
    // `val`, so restartGame() can swap in a freshly-constructed instance
    // of each -- neither class exposes a public reset of its own, and
    // both BoardLogic.kt and GameStateController's own collision-
    // judgement logic are on this round's fixed-spec list, so this is
    // deliberately never done by adding a reset method to either file;
    // only GameView's own reference is ever replaced. markController
    // stays `val` -- it already exposes a safe public clear() (see
    // restartGame), so no reassignment is needed for it.
    private var boardLogic: BoardLogic = BoardLogic()
    private val markController = MarkController()
    private val captureSystem = CaptureSystem()
    private var gameStateController: GameStateController = GameStateController()
    private val boardRenderer = BoardRenderer()
    private val playerRenderer = PlayerRenderer(context)
    private val qubeRenderer = QubeRenderer()

    // VISUAL-01/SOUND-01/SOUND-02: Poi's brief HIT flinch and the shared
    // SE funnel (SOUND-02: now backed by real SoundPool playback, see
    // SoundEventPlayer). Both are purely cosmetic/presentational --
    // neither is consulted by any game-logic check below, only
    // triggered once a logic result (a new HIT, a MARK placed, a
    // CAPTURE, a QUBE's own roll/land) is observed.
    // RESTART-SYSTEM-01: `var`, reconstructed fresh by restartGame() --
    // PoiHitReaction has no public reset of its own. soundEventPlayer
    // stays `val`/never reconstructed -- it wraps a SoundPool that should
    // keep its already-loaded samples across a restart, and it holds no
    // per-QUBE or per-run state that a restart would need to clear.
    private var hitReaction: PoiHitReaction = PoiHitReaction()
    private val soundEventPlayer = SoundEventPlayer(context)

    // AZUSAN-PLAYER-01: purely cosmetic sprite-selection state, read only
    // by playerRenderer.draw() below -- none of it feeds back into any
    // game-logic check. lastMoveDirection starts at SOUTH (facing the
    // camera) simply as a harmless idle default before the player's
    // first move; it's never read except while walkVisual is active.
    private var lastMoveDirection: Direction = Direction.SOUTH
    // RESTART-SYSTEM-01: `var`, reconstructed fresh by restartGame() --
    // TimedCosmeticFlag has no public reset of its own.
    private var walkVisual: TimedCosmeticFlag = TimedCosmeticFlag(durationMs = WALK_VISUAL_DURATION_MS)
    private var punchVisual: TimedCosmeticFlag = TimedCosmeticFlag(durationMs = PUNCH_VISUAL_DURATION_MS)
    // Tracks elapsed time within hitReaction's own active window (see
    // checkCollision/frame loop below) purely so PlayerRenderer can pick
    // HIT vs RECOVER -- never changes hitReaction's own DURATION_MS or
    // GameStateController's HIT_DURATION_MS.
    private var hitVisualElapsedMs = 0L
    // CATPUNCH-01: purely cosmetic, like hitReaction above -- reads
    // gameStateController.life each frame, never written back to it.
    private val churuRenderer = ChuruLifeRenderer()

    // SCORE-SYSTEM-01: the entire score system is this one Int plus the
    // two `score +=` call sites in onActionRequested/performPunch below
    // -- per this round's own "avoid over-engineering" instruction, a
    // dedicated ScoreController class would add indirection with nothing
    // for it to own yet (no combo/multiplier/high-score exists). Starts
    // at 0, which is also this app's only "new game" moment: there is no
    // in-game restart path anywhere in this codebase (confirmed by
    // reading every file under input/ and this class -- MainActivity
    // constructs exactly one GameView per process launch and never
    // recreates it), so a fresh process launch is currently the only time
    // this field's initial value is what matters.
    private var score: Int = 0

    // SCORE-SYSTEM-01: a brief "+100"/"+50" popup, same TimedCosmeticFlag
    // shape as walkVisual/punchVisual above (no fade, just present-then-
    // gone -- matching this codebase's existing convention for these
    // short cosmetic windows) plus the text to show while active. Purely
    // decorative: never read by any score/game-logic check, only by
    // onDraw below.
    private var scorePopup: TimedCosmeticFlag = TimedCosmeticFlag(durationMs = SCORE_POPUP_DURATION_MS)
    private var scorePopupText: String = ""

    // STEP 6: every NORMAL QUBE lives in this one collection -- no
    // qube1/qube2/qube3 style variables. Each entry owns its own GridCoord
    // (via its Qube) and its own rotation/timing state (via its
    // QubeMotion), so they advance completely independently even though
    // they currently share the same direction and timing constants.
    // Starts with 3 QUBEs across the back row (STEP 6 initial layout).
    // A successful CAPTURE removes exactly the matching entry from this
    // list (see onActionRequested) -- Qube.kt/QubeMotion.kt themselves
    // are unmodified.
    // RESTART-SYSTEM-01: `var`, not `val` -- restartGame() reassigns this
    // to a brand new createInitialQubes() result (the exact same factory
    // this field's own initial value already uses), so every QUBE after
    // a restart is a fresh Qube/QubeMotion/QubeSoundTracker with no
    // leftover reference to anything from the previous run.
    private var qubes: MutableList<QubeInstance> = createInitialQubes()

    // QUBE-BREAK-VISUAL-01: completely separate from `qubes` above on
    // purpose -- a QUBE is moved here (see performPunch) at the exact
    // moment its second punch destroys it, and that move already *is*
    // its removal from `qubes`. Every existing game-logic read
    // (GameStateController.checkCollision, isPunchRange,
    // captureSystem.isCaptured, MarkController, the "how many QUBEs
    // remain" question) only ever iterates `qubes`, so nothing here can
    // be hit by the player, marked, activated, or counted as a live QUBE
    // -- this list exists solely so GameView's draw loop can render a
    // short (~200ms) break flash for something that, logically, is
    // already gone.
    private val brokenQubes: MutableList<BrokenQubeVisual> = mutableListOf()

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

    // RESTART-SYSTEM-01: tracks how long GameView has continuously been
    // in GAME_OVER (see the frame loop), gating both [onTouchEvent]'s
    // RETRY tap and the "TAP TO RETRY" prompt's own visibility on
    // [RETRY_INPUT_LOCKOUT_MS] -- see that constant's doc for why.
    private var wasGameOver = false
    private var gameOverElapsedMs = 0L
    private var retryDownX = 0f
    private var retryDownY = 0f
    private val retryTapSlopPx = RETRY_TAP_SLOP_DP * density
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
    // SCORE-SYSTEM-01: gold-on-black, matching the paw button's existing
    // gold (Color.rgb(255, 205, 60), see PawActionButtonView) -- this
    // round's own "black/pink/gold/white" palette instruction reuses a
    // color already established in this game's world rather than
    // inventing a new one. Right-aligned so it sits in the header row
    // opposite churuRenderer's left-aligned icons (see onDraw) without
    // ever needing to know how wide the digits are.
    private val scoreTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
        textSize = 28f * density
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }
    // SCORE-SYSTEM-01: the brief "+100"/"+50" popup -- pink, per the same
    // palette instruction, so it reads as a distinct transient event next
    // to the steady gold SCORE line rather than a duplicate of it.
    private val scorePopupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 90, 150)
        textSize = 24f * density
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }
    // SCORE-SYSTEM-01: same gold as scoreTextPaint, smaller and centered,
    // for the one line added to the existing GAME OVER overlay.
    private val gameOverScorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
        textSize = 32f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    // RESTART-SYSTEM-01: pink, matching scorePopupPaint's palette choice,
    // smaller than gameOverTextPaint/gameOverScorePaint per this round's
    // own "not too big" instruction.
    private val retryPromptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 90, 150)
        textSize = 26f * density
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
            hitVisualElapsedMs = 0L
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
                if (hitReaction.progress() > 0f) hitVisualElapsedMs += deltaMs
                walkVisual.update(deltaMs)
                punchVisual.update(deltaMs)
                scorePopup.update(deltaMs)
                // QUBE-BREAK-VISUAL-01: plain elapsed-time bookkeeping
                // only, the same shape TimedCosmeticFlag.update already
                // uses elsewhere -- BrokenQubeVisual has no game-logic
                // meaning, so pruning finished entries here never affects
                // anything checkCollision/gameStateController.update read.
                for (broken in brokenQubes) broken.elapsedMs += deltaMs
                brokenQubes.removeAll { it.finished() }
                gameStateController.update(deltaMs)
                checkCollision()
                wasGameOver = false
            } else {
                // RESTART-SYSTEM-01: gameOverElapsedMs resets to 0 the
                // instant GAME_OVER is first observed (the `!wasGameOver`
                // branch below), then counts up every frame after that --
                // see RETRY_INPUT_LOCKOUT_MS/onTouchEvent.
                if (!wasGameOver) {
                    wasGameOver = true
                    gameOverElapsedMs = 0L
                } else {
                    gameOverElapsedMs += deltaMs
                }
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
    /** FRONT-ALIGNED-TEST-01: the one place [renderMode] is actually
     * read -- everything downstream (both IsoProjection instances,
     * BoardRenderer's tile shape, and its boardBounds screen-fit
     * calculation) takes this same unscaled value, so they can never
     * drift out of sync with each other within one frame. */
    private fun effectiveAxisMinorPx(): Float = when (renderMode) {
        RenderMode.CURRENT_ISOMETRIC -> RenderConfig.BOARD_AXIS_MINOR_PX
        RenderMode.FRONT_ALIGNED -> RenderConfig.BOARD_AXIS_MINOR_PX_FRONT_ALIGNED
    }

    private fun recomputeLayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val bounds = boardRenderer.boardBounds(BoardConfig.GRID_WIDTH, BoardConfig.GRID_DEPTH, effectiveAxisMinorPx())

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
        // FRONT-ALIGNED-TEST-01: axisMinor now comes from
        // effectiveAxisMinorPx() (see its own doc) instead of reading
        // RenderConfig.BOARD_AXIS_MINOR_PX directly, so both projections
        // -- and boardRenderer.draw's own tile-shape copy of this same
        // value, passed explicitly below -- always agree on which camera
        // is active this frame.
        val axisMajor = RenderConfig.BOARD_AXIS_MAJOR_PX * displayScale
        val axisMinor = effectiveAxisMinorPx() * displayScale
        val projection = IsoProjection(axisMajor, axisMinor, originX, originY)
        val qubeHeightScale = RenderConfig.QUBE_VISUAL_HEIGHT_SCALE_PX * displayScale
        val qubeProjection = IsoProjection(axisMajor, axisMinor, originX, originY, qubeHeightScale)

        boardRenderer.draw(
            canvas,
            projection,
            BoardConfig.GRID_WIDTH,
            BoardConfig.GRID_DEPTH,
            markController.markedCoord,
            displayScale,
            effectiveAxisMinorPx()
        )

        // Painter's algorithm across QUBEs and the player together:
        // farther-back cells (smaller z) drawn first. gridZ is the
        // dominant screen-Y contributor (see IsoProjection), so z alone
        // is the accurate depth-ordering key -- same principle
        // QubeRenderer already applies to a single QUBE's own faces.
        // AZUSAN-PLAYER-01: the player is now interleaved into this same
        // sorted pass (by playerPosition.z) rather than always drawn
        // first -- with the old, small vector marker, drawing it before
        // every QUBE never visibly mattered, but the sprite artwork is
        // large enough that a QUBE in a farther-back row could otherwise
        // incorrectly paint over Azusan, or Azusan could incorrectly
        // paint over a nearer QUBE.
        val playerZ = boardLogic.playerPosition.z
        var playerDrawn = false
        fun drawPlayer() {
            playerRenderer.draw(
                canvas,
                projection,
                boardLogic.playerPosition,
                displayScale,
                lastMoveDirection,
                walkVisual.active,
                punchVisual.active,
                hitReaction.progress() > 0f,
                hitVisualElapsedMs
            )
            playerDrawn = true
        }
        // QUBE-BREAK-VISUAL-01: brokenQubes is merged into the same
        // depth-sorted pass by its own frozen coordZ, so a break flash
        // never pops in front of/behind a QUBE or Azusan it shouldn't --
        // no change to how `qubes`/player are sorted or drawn among
        // themselves, this only adds a second, short-lived source of
        // entries to the same single sorted-by-z draw pass.
        val depthEntries: List<Pair<Int, () -> Unit>> =
            qubes.map { instance -> instance.qube.coord.z to { qubeRenderer.draw(canvas, instance.qube, instance.motion, qubeProjection) } } +
                brokenQubes.map { broken -> broken.coordZ to { qubeRenderer.drawBroken(canvas, broken, qubeProjection) } }
        for ((z, drawEntry) in depthEntries.sortedBy { it.first }) {
            if (!playerDrawn && z >= playerZ) drawPlayer()
            drawEntry()
        }
        if (!playerDrawn) drawPlayer()

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

        // SCORE-SYSTEM-01: right-aligned at the same y as churuRenderer's
        // left-aligned icons above -- same established header row/overlay
        // band this game already draws HUD elements in (churu itself
        // draws directly over the board, not in a separate reserved
        // margin), just the opposite horizontal side, so it can never
        // overlap the life icons.
        canvas.drawText("SCORE ${scoreText()}", width - 16f * density, 48f * density, scoreTextPaint)
        if (scorePopup.active) {
            canvas.drawText(scorePopupText, width - 16f * density, 80f * density, scorePopupPaint)
        }

        if (gameStateController.state == GameState.GAME_OVER) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gameOverDimPaint)
            canvas.drawText("GAME OVER", width / 2f, height / 2f, gameOverTextPaint)
            // SCORE-SYSTEM-01: the run's final score, minimal addition to
            // the existing overlay rather than a GAME OVER redesign.
            canvas.drawText("SCORE ${scoreText()}", width / 2f, height / 2f + 56f * density, gameOverScorePaint)
            // RESTART-SYSTEM-01: only shown once onTouchEvent will
            // actually accept the tap (see RETRY_INPUT_LOCKOUT_MS) -- so
            // the prompt never invites a tap that the lockout would then
            // silently ignore.
            if (gameOverElapsedMs >= RETRY_INPUT_LOCKOUT_MS) {
                canvas.drawText("TAP TO RETRY", width / 2f, height / 2f + 100f * density, retryPromptPaint)
            }
        }
    }

    /**
     * RESTART-SYSTEM-01: the only touch handling GameView itself does --
     * everywhere else, movement/MARK/ACTIVATE/CAT_PUNCH come from the
     * separate SwipeInputView/PawActionButtonView sibling views (see
     * MainActivity), completely untouched by this round; this override
     * never affects them and is a no-op (returns false, exactly as if it
     * didn't exist) outside GAME_OVER. Note SwipeInputView/
     * PawActionButtonView are positioned on top of GameView and
     * unconditionally claim touches inside their own bounds, so this
     * only ever actually receives a touch that lands outside both of
     * those zones -- in practice this still covers most of the screen
     * (including, on typical/wider Galaxy widths, the centered "TAP TO
     * RETRY" text itself), but on some narrower screens the exact center
     * can sit inside SwipeInputView's own left-side zone; see this
     * round's completion report for why this was implemented as-is
     * (entirely within GameView.kt, touching none of CONTROL-SIMPLE-02's
     * own files) rather than widened into those sibling views.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gameStateController.state != GameState.GAME_OVER) return false
        if (gameOverElapsedMs < RETRY_INPUT_LOCKOUT_MS) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                retryDownX = event.x
                retryDownY = event.y
                true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - retryDownX
                val dy = event.y - retryDownY
                if (hypot(dx, dy) <= retryTapSlopPx) {
                    restartGame()
                    invalidate()
                }
                true
            }
            else -> true
        }
    }

    /**
     * RESTART-SYSTEM-01: returns every piece of per-run state to exactly
     * what a fresh process launch starts with. Reconstructs a brand new
     * instance of any class with no public reset API of its own
     * (BoardLogic, GameStateController, PoiHitReaction, every
     * TimedCosmeticFlag) rather than adding a reset method to those
     * classes -- BoardLogic and GameStateController's own collision-
     * judgement logic are both on this round's fixed-spec list, so
     * neither file is ever touched, only GameView's own reference to
     * each is replaced. `qubes` is rebuilt via the same
     * [createInitialQubes] factory the very first launch already uses,
     * so durability/rotation/movement progress and every QubeSoundTracker
     * are back to a genuinely fresh state with zero references to any
     * pre-restart QUBE. MarkController is the one exception to "replace
     * the whole object" -- it already exposes a safe public [MarkController.clear],
     * so that's used directly instead, per this round's own "use the
     * existing API, don't touch MarkController internals" instruction.
     */
    private fun restartGame() {
        boardLogic = BoardLogic()
        gameStateController = GameStateController()
        qubes = createInitialQubes()
        brokenQubes.clear()
        markController.clear()

        hitReaction = PoiHitReaction()
        walkVisual = TimedCosmeticFlag(durationMs = WALK_VISUAL_DURATION_MS)
        punchVisual = TimedCosmeticFlag(durationMs = PUNCH_VISUAL_DURATION_MS)
        scorePopup = TimedCosmeticFlag(durationMs = SCORE_POPUP_DURATION_MS)
        scorePopupText = ""

        lastMoveDirection = Direction.SOUTH
        hitVisualElapsedMs = 0L
        score = 0

        wasGameOver = false
        gameOverElapsedMs = 0L
    }

    /** SCORE-SYSTEM-01: zero-padded to at least 6 digits for display only
     * -- [score] itself stays a plain Int, never reformatted/stored as a
     * String anywhere else. */
    private fun scoreText(): String = score.toString().padStart(6, '0')

    override fun onMoveRequested(direction: Direction) {
        if (gameStateController.state == GameState.GAME_OVER) return
        if (boardLogic.movePlayer(direction)) {
            // AZUSAN-PLAYER-01: cosmetic only -- a brief WALK_<direction>
            // sprite window, purely reflecting a move that already
            // happened (movePlayer's own Boolean result is unchanged).
            lastMoveDirection = direction
            walkVisual.trigger()
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
     * CONTROL-SIMPLE-01: this is now fired by a plain TAP only (see
     * [PawActionButtonView]/[ActionInputSource]) and is unconditionally
     * MARK/ACTIVATE -- CAT_PUNCH is decided entirely by input method now
     * (the ACTION paw's distinct downward-slide gesture, see
     * [onPunchGestureRequested]), never by which QUBEs happen to be
     * nearby when TAP fires. This removes the CATPUNCH-01-era "a QUBE
     * happening to be in punch range can steal the tap meant to place a
     * MARK" interaction entirely, by construction: this method never
     * looks at punch range at all anymore.
     */
    override fun onActionRequested() {
        if (gameStateController.state == GameState.GAME_OVER) return
        val currentMark = markController.markedCoord
        if (currentMark != null) {
            // Logical grid coordinates are unique per QUBE, so at most
            // one entry can ever match -- one MARK captures at most one
            // QUBE. Unchanged from before CONTROL-SIMPLE-01: an ACTIVATE
            // attempt against an empty marked cell still clears the mark
            // with no capture and no sound -- this existing rule is
            // deliberately preserved as-is, per this round's explicit
            // "don't change MARK-clear behavior" instruction.
            val index = qubes.indexOfFirst { captureSystem.isCaptured(currentMark, it.qube.coord) }
            if (index >= 0) {
                qubes.removeAt(index)
                soundEventPlayer.play(SoundEvent.CAPTURE_SUCCESS)
                // SCORE-SYSTEM-01: scored exactly once, right here -- this
                // branch only ever runs when isCaptured just matched and
                // the QUBE was just removed from `qubes`, i.e. exactly the
                // "QUBE actually processed via MARK/ACTIVATE" instant the
                // spec requires. An ACTIVATE against an empty marked cell
                // (index < 0, existing behavior, unchanged below) never
                // reaches this line.
                awardScore(SCORE_ACTIVATE_CAPTURE, "+$SCORE_ACTIVATE_CAPTURE")
            }
            markController.clear()
        } else {
            markController.markAt(boardLogic.playerPosition)
            soundEventPlayer.play(SoundEvent.MARK_SET)
        }
        invalidate()
    }

    /**
     * CONTROL-SIMPLE-01: fired only by the ACTION paw's downward-slide
     * gesture (see [PawActionButtonView]/[ActionInputSource]) -- never
     * by a plain tap, and never both in the same gesture (see
     * [PawActionButtonView.onTouchEvent]). Always attempts CAT_PUNCH via
     * the same, unmodified [isPunchRange]/[performPunch] CATPUNCH-01
     * already established; unlike the old shared-tap dispatch, this
     * never falls back to placing/judging a MARK -- if nothing is in
     * punch range, this is simply a no-op.
     */
    override fun onPunchGestureRequested() {
        if (gameStateController.state == GameState.GAME_OVER) return
        val punchIndex = qubes.indexOfFirst { isPunchRange(boardLogic.playerPosition, it.qube.coord) }
        if (punchIndex >= 0) {
            performPunch(punchIndex)
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
        // AZUSAN-PLAYER-01: cosmetic only -- a brief CAT_PUNCH sprite
        // window alongside the existing SE calls below, which are
        // otherwise unchanged. Fires regardless of destroyed/dented,
        // matching how PUNCH_HIT itself already always plays.
        punchVisual.trigger()
        val instance = qubes[index]
        val destroyed = instance.qube.punch()
        if (destroyed) {
            // QUBE-BREAK-VISUAL-01: capture the exact pose (previous/
            // current cell, direction, and the toppling rotation the
            // instant this hit landed) into a render-only snapshot
            // *before* removeAt below -- durability itself already hit 0
            // one line above, so the QUBE is already logically destroyed
            // (Qube.punch() already applied that; this call never re-reads
            // or changes durability, coord, or collision in any way).
            // Once removeAt runs, `qubes` -- and therefore every existing
            // collision/MARK/ACTIVATE/movement check that only iterates
            // `qubes` -- has no record of this QUBE at all; brokenQubes is
            // purely a second, separate list the draw loop also reads.
            brokenQubes.add(
                BrokenQubeVisual(
                    previousCoordX = instance.qube.previousCoord.x,
                    previousCoordZ = instance.qube.previousCoord.z,
                    coordX = instance.qube.coord.x,
                    coordZ = instance.qube.coord.z,
                    direction = instance.qube.direction,
                    rotationProgressAtBreak = instance.motion.rotationProgress()
                )
            )
            qubes.removeAt(index)
            soundEventPlayer.play(SoundEvent.QUBE_BREAK)
            // SCORE-SYSTEM-01: scored exactly once, only in this
            // `destroyed` branch (durability just hit 0 above) -- the
            // first punch (the `else` branch below, durability 2->1)
            // never reaches this line, so it is always 0 points, per the
            // spec's explicit "1st hit = 0, 2nd hit = +50" requirement.
            // BrokenQubeVisual (added above) is never consulted for
            // scoring -- this line runs before that object even exists on
            // screen for a single frame.
            awardScore(SCORE_PUNCH_DESTROY, "+$SCORE_PUNCH_DESTROY")
        } else {
            soundEventPlayer.play(SoundEvent.PUNCH_HIT)
        }
    }

    /** SCORE-SYSTEM-01: the one place [score] is ever mutated -- both
     * scoring call sites above go through this so "add points" and "show
     * the brief popup" can never drift apart. */
    private fun awardScore(points: Int, popupText: String) {
        score += points
        scorePopupText = popupText
        scorePopup.trigger()
    }

    /** Read by [com.mspgllc.iqpuchin.input.ActionInputSource] to decide
     * whether the ACTION button should currently read "MARK" (no mark
     * pending) or "ACTIVATE" (a mark is pending). */
    fun isAwaitingMark(): Boolean = markController.markedCoord == null
}
