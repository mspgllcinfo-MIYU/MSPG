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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

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

        // STAGE-DESIGN-01
        /**
         * This release's own current final stage -- deliberately NOT the
         * game system's hard-coded absolute ceiling (this round's own
         * explicit instruction): [resolveWaveBoundary]/onTouchEvent/onDraw
         * all compare `stageNumber` against this one named constant, never
         * a bare literal `10`, so a future round can extend [STAGE_WAVES]
         * to Stage 20 and raise this single value -- nothing else about
         * the NEXT/ALL-CLEAR logic needs to change.
         */
        const val CURRENT_RELEASE_FINAL_STAGE = 10

        /**
         * STAGE-LAYOUT-01: the board's 7 columns (x=0..6), named left-to-
         * right exactly as this round's own spec requests. Confirmed from
         * the actual projection math, not guessed: under FRONT_ALIGNED
         * (this app's active [RenderMode]), IsoProjection's screenX =
         * originX + gridX * axisMajorPx (axisMinorPx is 0 in this mode),
         * so increasing x moves strictly rightward on screen with no
         * other axis mixed in -- x=0 is the leftmost column, x=6 the
         * rightmost. C.x=3 lines up with BoardLogic's own default player
         * spawn column (`GridCoord(BoardConfig.GRID_WIDTH / 2, ...)` =
         * x=3, since GRID_WIDTH=7), confirming C really is the board's
         * logical center column, not an assumption.
         */
        private enum class Lane(val x: Int) {
            L3(0), L2(1), L1(2), C(3), R1(4), R2(5), R3(6)
        }

        /**
         * Per-stage wave layout, index 0 == Stage 1. Each stage is a list
         * of waves in spawn order; each wave is the list of [Lane]s its
         * QUBEs spawn in, so e.g. `listOf(Lane.L1, Lane.C)` reads directly
         * as "a two-QUBE group in the L1 and C columns" -- per this
         * round's own "the data should show the layout, not a large if/
         * when dispatcher" instruction. A stage's total QUBE count is the
         * sum of its own waves' sizes; every QUBE within one wave uses a
         * distinct Lane, so none ever start on the same cell (the same
         * guarantee STAGE-DESIGN-01's old count-based WAVE_COLUMNS gave).
         *
         * STAGE-LAYOUT-01 replaces STAGE-DESIGN-01's count-only waves with
         * this round's own hand-authored per-stage layouts (Stage 1-3:
         * single QUBEs only; Stage 4 introduces the first 2-wide wave;
         * Stage 9 introduces the first L3+R3 both-edge wave). Every
         * stage's own total QUBE count is unchanged from STAGE-DESIGN-01/
         * 01B/01C -- verified by summing each list below: 3/4/5/6/8/9/11/
         * 13/15/17.
         *
         * Stage 10 is the one exception: it keeps STAGE-DESIGN-01C's
         * original wave shape rather than this round's own newly-specified
         * layout. This round's own itemized Stage 10 wave list sums to 18
         * QUBEs, not the 17 this round's own "don't change any stage's
         * total QUBE count" rule requires stay unchanged -- see this
         * round's completion report for the exact discrepancy. Guessing
         * which single QUBE to drop to reconcile that would be inventing
         * a design decision neither given nor asked for, so Stage 10's
         * layout is left exactly as it already shipped and was already
         * real-device-verified (STAGE-DESIGN-01C, commit d38dc26) instead.
         */
        val STAGE_WAVES: List<List<List<Lane>>> = listOf(
            // Stage 1 -- 3, single QUBEs only
            listOf(listOf(Lane.L1), listOf(Lane.R1), listOf(Lane.L2)),
            // Stage 2 -- 4, single QUBEs only, center kept clear
            listOf(listOf(Lane.R2), listOf(Lane.L2), listOf(Lane.R1), listOf(Lane.L1)),
            // Stage 3 -- 5, single QUBEs only
            listOf(listOf(Lane.L1), listOf(Lane.R1), listOf(Lane.L2), listOf(Lane.C), listOf(Lane.R2)),
            // Stage 4 -- 6, first 2-wide wave (L1+C), right side left wide open
            listOf(
                listOf(Lane.L1, Lane.C), listOf(Lane.R2), listOf(Lane.L2),
                listOf(Lane.R1), listOf(Lane.L1)
            ),
            // Stage 5 -- 8, left cluster then right cluster
            listOf(
                listOf(Lane.L2, Lane.L1), listOf(Lane.R1), listOf(Lane.R2, Lane.R3),
                listOf(Lane.L1), listOf(Lane.L2), listOf(Lane.C)
            ),
            // Stage 6 -- 9
            listOf(
                listOf(Lane.L1, Lane.C), listOf(Lane.L2, Lane.R2), listOf(Lane.C),
                listOf(Lane.R1, Lane.R2), listOf(Lane.L2), listOf(Lane.R1)
            ),
            // Stage 7 -- 11
            listOf(
                listOf(Lane.L2, Lane.L1), listOf(Lane.R1), listOf(Lane.L1, Lane.C),
                listOf(Lane.R2), listOf(Lane.L2, Lane.R2), listOf(Lane.C, Lane.R1), listOf(Lane.L1)
            ),
            // Stage 8 -- 13, left -> center -> right -> left sweep
            listOf(
                listOf(Lane.L3, Lane.L2), listOf(Lane.L1), listOf(Lane.C, Lane.R1),
                listOf(Lane.R2), listOf(Lane.R1, Lane.R2), listOf(Lane.L2),
                listOf(Lane.L1, Lane.C), listOf(Lane.R2), listOf(Lane.C)
            ),
            // Stage 9 -- 15, first L3+R3 both-edge wave (center kept clear)
            listOf(
                listOf(Lane.L2, Lane.L1), listOf(Lane.C), listOf(Lane.R1, Lane.R2),
                listOf(Lane.L3, Lane.R3), listOf(Lane.L1, Lane.C), listOf(Lane.L2, Lane.R2),
                listOf(Lane.C, Lane.R1), listOf(Lane.L1), listOf(Lane.R1)
            ),
            // Stage 10 -- 17, kept as STAGE-DESIGN-01C's original shape --
            // see this constant's own doc for why.
            listOf(
                listOf(Lane.L3, Lane.L1, Lane.R1, Lane.R3),
                listOf(Lane.L3, Lane.L1, Lane.R1, Lane.R3),
                listOf(Lane.L2, Lane.C, Lane.R2),
                listOf(Lane.L2, Lane.C, Lane.R2),
                listOf(Lane.L2, Lane.C, Lane.R2)
            )
        )

        /**
         * STAGE-DESIGN-01B: once every QUBE currently on board has
         * traveled at least this many cells past the spawn row (z=0), the
         * frame loop pre-spawns the stage's next wave -- see the frame
         * loop's own early-spawn check. This is the entire fix for "waves
         * felt like sudden respawns": the next group now becomes visible
         * at the board's own back edge *while the current wave is still
         * in play*, since the whole 12-row board is already always fully
         * on screen (this camera never scrolls -- confirmed via
         * IsoProjection/recomputeLayout, both untouched), rather than only
         * after `qubes` goes empty.
         *
         * The empty gap this leaves between the old wave's front (at
         * z=[EARLY_SPAWN_DEPTH_THRESHOLD]) and the new wave's spawn row
         * (z=0) is [EARLY_SPAWN_DEPTH_THRESHOLD]-1 rows (the strictly-
         * between rows z=1..threshold-1). STAGE-DESIGN-01B originally used
         * 4 (a 3-row gap); a first STAGE-DESIGN-01C pass lowered it to 3
         * (a 2-row gap). Real-device feedback wanted tighter still: a
         * *default* 0-row gap (the two waves visibly adjacent, reading as
         * one continuous column), with any larger gap only as a fallback
         * if 0 were ever provably unsafe. It never is, so this now sits at
         * the lowest value that still guarantees zero coordinate overlap:
         * 1 -- gap = 1-1 = 0 rows. The reasoning: every QUBE in one wave
         * moves in perfect lockstep (same spawn instant, same
         * QubeConfig.CYCLE_DURATION_MS pacing -- unchanged), so
         * `qubes.all { z >= 1 }` only ever becomes true the instant the
         * *entire* current wave has completed its first topple and moved
         * off z=0 onto z=1 -- at that exact moment z=0 is guaranteed
         * empty, so the new wave can occupy it with zero risk of landing
         * on an existing QUBE. Threshold 0 would be unsafe by contrast
         * (a wave's own just-spawned members already satisfy z>=0
         * trivially, which would let the very same frame's spawn
         * immediately trigger *another* spawn on top of it); 1 is the
         * smallest threshold that avoids that. The new wave still gets a
         * full traverse of the board's own front half before reaching the
         * player, so individual reaction time is unaffected -- only how
         * soon the *next* wave becomes visible changes.
         */
        const val EARLY_SPAWN_DEPTH_THRESHOLD = 1

        // PRESENTATION-01
        /** How long the centered "STAGE n / READY" display freezes play
         * for at the start of every stage -- fresh launch, RETRY, PLAY
         * AGAIN, and every NEXT all go through the single shared
         * [beginStageWaves] call site, so this one constant covers all
         * four. Chosen within this round's own 0.6-1.0s spec range; play
         * is fully frozen for this whole window (see the frame loop's
         * `stageStartActive` branch), so QUBE speed/Wave timing/
         * EARLY_SPAWN_DEPTH_THRESHOLD are all completely unaffected --
         * this just delays when the frozen board starts moving, never
         * how it moves once it does. */
        const val STAGE_START_DISPLAY_MS = 800L

        /** STAGE CLEAR/ALL CLEAR's brief scale-in-to-fixed-position text
         * animation -- within this round's own ~100-200ms spec. Drives a
         * canvas.scale() pivoted at screen center, wrapped only around the
         * result text itself (never the dim rect), so the existing TAP TO
         * NEXT/TAP TO PLAY AGAIN input logic in onTouchEvent -- untouched
         * by this round -- has nothing to do with how the text is drawn. */
        const val RESULT_TEXT_ANIM_DURATION_MS = 160L
        const val RESULT_TEXT_ANIM_START_SCALE = 0.85f

        /** GAME OVER's own dim-overlay fade -- deliberately slower and
         * monotonic (a single fade-in, never a pulse/flash) than
         * [RESULT_TEXT_ANIM_DURATION_MS]'s snappier scale-in, so GAME OVER
         * reads calmer than STAGE CLEAR per this round's spec, without
         * touching the overlay's judgment or RETRY_INPUT_LOCKOUT_MS's own
         * mis-tap guard at all. */
        const val GAME_OVER_DIM_FADE_MS = 350L
        const val GAME_OVER_DIM_MAX_ALPHA = 170

        /** ALL CLEAR's small gold QUBE-like particle burst -- brief and
         * self-terminating (never loops, never re-triggers), purely
         * additive drawing with zero effect on restartGame()/the TAP TO
         * PLAY AGAIN tap. */
        const val ALL_CLEAR_PARTICLE_DURATION_MS = 700L
        const val ALL_CLEAR_PARTICLE_START_RADIUS_DP = 40f
        const val ALL_CLEAR_PARTICLE_END_RADIUS_DP = 140f
        const val ALL_CLEAR_PARTICLE_SIZE_DP = 10f
    }

    /** ALL CLEAR's particle burst: a fixed ring of small gold squares, each
     * with a slight stagger (delayMs) so they don't all pop/fade in
     * perfect unison -- positions/timing are deterministic (derived from
     * [stageClearElapsedMs] in onDraw), never random-per-frame, so nothing
     * flickers. */
    private data class AllClearParticle(val angleDeg: Float, val delayMs: Long)

    private val allClearParticles: List<AllClearParticle> =
        List(8) { i -> AllClearParticle(angleDeg = i * 45f, delayMs = (i % 4) * 40L) }

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

    // STAGE-SYSTEM-01: starts at 1 (fresh launch == Stage 1), bumped only
    // by advanceToNextStage() -- restartGame() resets it back to 1 (RETRY
    // always returns to Stage 1, even from Stage 3+, per this round's own
    // spec). Never read by any game-logic check (QUBE count/speed/
    // durability are unaffected by it this round), purely HUD + the
    // NEXT-transition's own increment.
    private var stageNumber: Int = 1

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
    // RESTART-SYSTEM-01: `var`, not `val` -- restartGame()/advanceToNextStage()
    // reassign this via [beginStageWaves]/[spawnWave], so every QUBE after
    // a restart or a new stage's first wave is a fresh Qube/QubeMotion/
    // QubeSoundTracker with no leftover reference to anything from the
    // previous run/stage. STAGE-DESIGN-01: starts empty -- the very first
    // wave is spawned by the init block below, not by this initializer.
    private var qubes: MutableList<QubeInstance> = mutableListOf()

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

    /**
     * STAGE-LAYOUT-01: adds one wave's worth of fresh QUBEs to `qubes`,
     * one per [Lane] in [lanes] -- same [Qube]/[QubeMotion]/
     * [QubeSoundTracker] construction STAGE-DESIGN-01's version used
     * (default durability, direction SOUTH, z=0 -- the board's own
     * existing spawn row, completely unchanged; BoardConfig/BoardLogic/
     * IsoProjection are never touched by this round), just taking each
     * QUBE's column directly from [STAGE_WAVES]'s own data instead of a
     * count-keyed lookup. [STAGE_WAVES] never repeats a Lane within one
     * wave, so no two QUBEs spawned by the same call ever land on the
     * same cell.
     */
    private fun spawnWave(lanes: List<Lane>) {
        for (lane in lanes) {
            val qube = Qube(startCoord = GridCoord(lane.x, 0), direction = Direction.SOUTH)
            val motion = QubeMotion(qube)
            qubes.add(QubeInstance(qube, motion, QubeSoundTracker(qube, motion)))
        }
    }

    /** STAGE-DESIGN-01: [stageNumber]'s own wave layout. Stages beyond
     * [STAGE_WAVES]'s defined range (not currently reachable -- ALL CLEAR
     * stops progression at [CURRENT_RELEASE_FINAL_STAGE]) fall back to the
     * last defined stage's layout rather than crashing. */
    private fun currentStageWaves(): List<List<Lane>> =
        STAGE_WAVES.getOrElse(stageNumber - 1) { STAGE_WAVES.last() }

    /**
     * STAGE-DESIGN-01: the single place that decides "has this stage's
     * QUBE supply run out yet." Called whenever `qubes` has just become
     * empty -- immediately for MARK/ACTIVATE's last QUBE (onActionRequested),
     * or once brokenQubes has fully drained for CAT_PUNCH's last QUBE (the
     * frame loop's own pendingStageClearAfterBreak resolution, unchanged
     * from STAGE-CLEAR-01 otherwise).
     *
     * If the current stage still has an unspawned wave, spawns it -- more
     * QUBEs are "still coming," entirely through spawn timing/count. This
     * is a deliberate design choice: rather than literally extending the
     * board's depth past what the camera shows (which this round's own
     * spec forbids -- no shrinking the board to fit a longer one on
     * screen, no camera pull-back), waves that haven't spawned yet are
     * conceptually "still off-screen" purely because they don't exist as
     * Qube objects yet, not because they sit at an off-screen GridCoord.
     * BoardConfig.GRID_DEPTH/IsoProjection/BoardRenderer/recomputeLayout
     * are completely unchanged by this round.
     *
     * Once every wave for this stage has spawned and `qubes` is empty,
     * this is the true "all QUBEs for this stage processed" instant --
     * STAGE-CLEAR-01's own `stageClear` flag, unchanged in what it does
     * once true (freezes play, shows the CLEAR overlay). Previously (pre-
     * STAGE-DESIGN-01) this was set directly wherever `qubes.isEmpty()`
     * was observed; now both of those call sites go through this function
     * instead, so a mid-stage wave boundary can no longer be mistaken for
     * the stage's actual end.
     */
    private fun resolveWaveBoundary() {
        if (!spawnNextWaveIfAny()) {
            stageClear = true
        }
    }

    /** STAGE-DESIGN-01B: spawns [stageNumber]'s next not-yet-spawned wave,
     * if any, and advances [stageWaveIndex] -- returns whether it did.
     * Shared by [resolveWaveBoundary] (the stage's true exhaustion check,
     * called once `qubes` actually empties) and the frame loop's own
     * early pre-spawn check below -- both need the same "spawn the next
     * wave" action, just triggered at different times. */
    private fun spawnNextWaveIfAny(): Boolean {
        val waves = currentStageWaves()
        if (stageWaveIndex >= waves.size) return false
        spawnWave(waves[stageWaveIndex])
        stageWaveIndex++
        return true
    }

    /** STAGE-DESIGN-01: (re)starts wave spawning for whatever [stageNumber]
     * currently is -- empties `qubes`, resets the wave index, and spawns
     * wave 0 via [resolveWaveBoundary] (the same function every later
     * wave boundary during play goes through). Called both by
     * [resetBoardAndVisuals] (RETRY/NEXT) and once from the init block
     * below, for Stage 1's own very first spawn -- replacing the old
     * createInitialQubes() field initializer. */
    private fun beginStageWaves() {
        qubes = mutableListOf()
        stageWaveIndex = 0
        stageClear = false
        pendingStageClearAfterBreak = false
        // PRESENTATION-01: the single shared "a stage's wave 0 just
        // started" instant -- fresh launch (init below), RETRY/PLAY AGAIN
        // (restartGame -> resetBoardAndVisuals), and NEXT
        // (advanceToNextStage -> resetBoardAndVisuals) all reach this line,
        // so one flag flip here covers all four required occasions.
        stageStartActive = true
        stageStartElapsedMs = 0L
        resolveWaveBoundary()
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

    // STAGE-CLEAR-01: a second, GameView-only result state, deliberately
    // never added to GameState/GameStateController (that file's own
    // collision-judgement logic is fixed-spec this round) -- `stageClear`
    // is checked alongside `gameStateController.state == GAME_OVER`
    // everywhere a freeze/input-gate is needed, exactly the same pattern
    // wasGameOver/gameOverElapsedMs already established for GAME_OVER,
    // reused here rather than duplicated into a new mechanism.
    private var stageClear = false
    private var wasStageClear = false
    private var stageClearElapsedMs = 0L
    // Set the instant a CAT_PUNCH destroys the last QUBE; only flips
    // `stageClear` true once brokenQubes has fully drained on its own
    // (see the frame loop) -- this reuses BrokenQubeVisual's own
    // DURATION_MS/finished() lifecycle as the wait, rather than a second,
    // duplicate ~200ms timer. Never set for the MARK/ACTIVATE path, which
    // has no BrokenQubeVisual and transitions immediately.
    private var pendingStageClearAfterBreak = false

    // STAGE-DESIGN-01: how many of currentStageWaves()'s waves have
    // already been spawned into `qubes` (0 = none yet). Reset to 0 by
    // [beginStageWaves] at the start of every stage.
    private var stageWaveIndex = 0

    // PRESENTATION-01: a third GameView-only freeze state, same pattern as
    // stageClear/wasGameOver above -- never merged into GameState/
    // GameStateController. Set true by [beginStageWaves] (the one shared
    // call site fresh launch/RETRY/PLAY AGAIN/NEXT all already go
    // through), cleared by the frame loop once STAGE_START_DISPLAY_MS has
    // elapsed. While true, the frame loop's normal-update branch (QUBE
    // motion, collision, every cosmetic timer) doesn't run at all -- see
    // that branch's own guard -- and onMoveRequested/onActionRequested/
    // onPunchGestureRequested all early-return, so the player can't move,
    // MARK, ACTIVATE, or CAT_PUNCH while "STAGE n / READY" is on screen.
    private var stageStartActive = false
    private var stageStartElapsedMs = 0L

    // STAGE-DESIGN-01: spawns Stage 1's own wave 0 -- replaces the old
    // `qubes = createInitialQubes()` field initializer. Placed here
    // (rather than as qubes' own initializer) because it needs
    // stageNumber/qubes/stageWaveIndex/stageClear/pendingStageClearAfterBreak
    // already declared, which they all are by this point in the class body.
    init {
        beginStageWaves()
    }

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
    // STAGE-SYSTEM-01: small, white, left-aligned just under churuRenderer's
    // icons -- mirrors scorePopupPaint sitting just under scoreTextPaint on
    // the opposite side, so the HUD reads as two small symmetric stacks
    // (life+stage on the left, score+popup on the right) rather than
    // crowding either existing corner.
    private val stageTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * density
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    // PRESENTATION-01: "STAGE n" (gold, large, matching scoreTextPaint's
    // gold) plus a smaller white "READY" line underneath -- same overall
    // shape as the GAME OVER/STAGE CLEAR overlays' two-line layout, but
    // deliberately no dim rect behind it (see onDraw), since this is a
    // brief transition, not a results screen.
    private val stageStartTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
        textSize = 52f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val stageStartReadyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    // PRESENTATION-01: ALL CLEAR's small gold QUBE-like particle squares --
    // alpha is set per-particle per-frame in onDraw (see
    // allClearParticles), so only the base color is fixed here.
    private val allClearParticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
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
            // own timing/order changed. STAGE-CLEAR-01: `stageClear`
            // freezes the same way -- by construction it can only ever
            // become true while `qubes` is already empty (see
            // onActionRequested/performPunch), so it can never coincide
            // with a GAME_OVER-causing collision (which requires a
            // non-empty qubes list) -- the two conditions are mutually
            // exclusive, not prioritized against each other.
            val isGameOver = gameStateController.state == GameState.GAME_OVER
            // PRESENTATION-01: stageStartActive freezes play before either
            // of the other two checks even run -- QUBE motion, collision,
            // and every cosmetic timer below are completely skipped for
            // STAGE_START_DISPLAY_MS, so the player can never be hit or
            // lose a QUBE while "STAGE n / READY" is on screen. Nothing
            // about QUBE speed/Wave timing/EARLY_SPAWN_DEPTH_THRESHOLD
            // changes -- this only delays when the already-frozen board
            // starts advancing.
            if (stageStartActive) {
                stageStartElapsedMs += deltaMs
                if (stageStartElapsedMs >= STAGE_START_DISPLAY_MS) {
                    stageStartActive = false
                }
            } else if (!isGameOver && !stageClear) {
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
                // STAGE-CLEAR-01: only flips once the last QUBE's break
                // flash has fully drained -- see pendingStageClearAfterBreak's
                // own doc.
                if (pendingStageClearAfterBreak && brokenQubes.isEmpty()) {
                    pendingStageClearAfterBreak = false
                    // STAGE-DESIGN-01: decides wave-vs-stage-end instead of
                    // always meaning "this was the stage's last QUBE."
                    resolveWaveBoundary()
                }
                // STAGE-DESIGN-01B: pre-spawns the stage's next wave once
                // every QUBE currently on board has cleared
                // EARLY_SPAWN_DEPTH_THRESHOLD -- see that constant's own
                // doc for why this is the fix for "waves felt like sudden
                // respawns." Only ever fires while `qubes` is still
                // non-empty (the current wave is still genuinely in play);
                // resolveWaveBoundary's own qubes.isEmpty()-triggered
                // spawn above remains the safety net for a player who
                // clears a wave fast enough that it never crosses this
                // threshold at all.
                if (qubes.isNotEmpty() && qubes.all { it.qube.coord.z >= EARLY_SPAWN_DEPTH_THRESHOLD }) {
                    spawnNextWaveIfAny()
                }
                wasGameOver = false
                wasStageClear = false
            } else if (isGameOver) {
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
            } else {
                // STAGE-CLEAR-01: identical lockout bookkeeping, reusing
                // RETRY_INPUT_LOCKOUT_MS -- see onTouchEvent.
                if (!wasStageClear) {
                    wasStageClear = true
                    stageClearElapsedMs = 0L
                } else {
                    stageClearElapsedMs += deltaMs
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
        // STAGE-SYSTEM-01: small, non-intrusive, always visible during
        // play (and dimmed-but-visible under the GAME OVER/STAGE CLEAR
        // overlays below, same as churu/SCORE already are).
        canvas.drawText("STAGE $stageNumber", 16f * density, 66f * density, stageTextPaint)

        if (gameStateController.state == GameState.GAME_OVER) {
            // PRESENTATION-01: a calm, monotonic dim fade-in (never a
            // pulse/flash) -- deliberately slower than STAGE CLEAR/ALL
            // CLEAR's snappier text scale-in below, per this round's own
            // "calmer than STAGE CLEAR" spec. Judgment, RETRY_INPUT_LOCKOUT_MS,
            // and the TAP TO RETRY prompt/tap logic below are all untouched.
            val dimFadeProgress = (gameOverElapsedMs.toFloat() / GAME_OVER_DIM_FADE_MS).coerceIn(0f, 1f)
            gameOverDimPaint.alpha = (GAME_OVER_DIM_MAX_ALPHA * dimFadeProgress).toInt()
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

        // STAGE-CLEAR-01: same overlay shape as GAME OVER above, reusing
        // the same Paints (white/gold/pink) rather than new ones, per
        // this round's own "don't change the look-and-feel" instruction.
        // STAGE-DESIGN-01/GAME-FLOW-01: Stage CURRENT_RELEASE_FINAL_STAGE's
        // own clear shows "ALL CLEAR"/"FINAL SCORE"/"TAP TO PLAY AGAIN"
        // instead of STAGE CLEAR/SCORE/TAP TO NEXT -- see onTouchEvent for
        // where the tap goes (restartGame(), same as GAME OVER's RETRY).
        if (stageClear) {
            // PRESENTATION-01: STAGE CLEAR/ALL CLEAR's dim rect stays at
            // full alpha immediately (only GAME OVER's fades in -- see
            // above), so the scale-in below is this overlay's whole "polish"
            // signature.
            gameOverDimPaint.alpha = GAME_OVER_DIM_MAX_ALPHA
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gameOverDimPaint)

            // PRESENTATION-01: brief scale-in-to-fixed-position, pivoted at
            // screen center -- wraps only the text below, never the dim
            // rect just drawn above, and never touches onTouchEvent's
            // existing TAP TO NEXT/TAP TO PLAY AGAIN tap logic at all.
            val textAnimProgress = (stageClearElapsedMs.toFloat() / RESULT_TEXT_ANIM_DURATION_MS).coerceIn(0f, 1f)
            val textScale = RESULT_TEXT_ANIM_START_SCALE + (1f - RESULT_TEXT_ANIM_START_SCALE) * textAnimProgress
            canvas.save()
            canvas.scale(textScale, textScale, width / 2f, height / 2f)

            if (stageNumber >= CURRENT_RELEASE_FINAL_STAGE) {
                canvas.drawText("ALL CLEAR", width / 2f, height / 2f, gameOverTextPaint)
                canvas.drawText("FINAL SCORE ${scoreText()}", width / 2f, height / 2f + 56f * density, gameOverScorePaint)
                if (stageClearElapsedMs >= RETRY_INPUT_LOCKOUT_MS) {
                    canvas.drawText("TAP TO PLAY AGAIN", width / 2f, height / 2f + 100f * density, retryPromptPaint)
                }
            } else {
                canvas.drawText("STAGE CLEAR", width / 2f, height / 2f, gameOverTextPaint)
                canvas.drawText("SCORE ${scoreText()}", width / 2f, height / 2f + 56f * density, gameOverScorePaint)
                if (stageClearElapsedMs >= RETRY_INPUT_LOCKOUT_MS) {
                    canvas.drawText("TAP TO NEXT", width / 2f, height / 2f + 100f * density, retryPromptPaint)
                }
            }
            canvas.restore()

            // PRESENTATION-01: ALL CLEAR's own brief gold particle burst --
            // makes it read as "slightly more special than STAGE CLEAR"
            // per this round's spec, self-terminating after
            // ALL_CLEAR_PARTICLE_DURATION_MS with zero effect on the tap
            // logic above.
            if (stageNumber >= CURRENT_RELEASE_FINAL_STAGE) {
                drawAllClearParticles(canvas)
            }
        }

        // PRESENTATION-01: brief centered "STAGE n / READY" at the start
        // of every stage -- deliberately drawn last (on top of everything
        // else, including the board underneath) and with no dim rect,
        // since it's a short transition rather than a results screen. The
        // frame loop's own stageStartActive branch is what actually keeps
        // play frozen for this whole window; this is purely the display.
        if (stageStartActive) {
            canvas.drawText("STAGE $stageNumber", width / 2f, height / 2f, stageStartTextPaint)
            canvas.drawText("READY", width / 2f, height / 2f + 56f * density, stageStartReadyPaint)
        }
    }

    /** PRESENTATION-01: draws [allClearParticles] as small gold squares
     * radiating outward from screen center and fading out, each on its own
     * [AllClearParticle.delayMs]-staggered schedule driven by
     * [stageClearElapsedMs] -- deterministic, not random-per-frame, so
     * nothing flickers, and it simply stops drawing once every particle's
     * own window has elapsed rather than looping. */
    private fun drawAllClearParticles(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val half = ALL_CLEAR_PARTICLE_SIZE_DP * density / 2f
        for (particle in allClearParticles) {
            val localElapsedMs = stageClearElapsedMs - particle.delayMs
            if (localElapsedMs < 0L || localElapsedMs >= ALL_CLEAR_PARTICLE_DURATION_MS) continue
            val t = localElapsedMs.toFloat() / ALL_CLEAR_PARTICLE_DURATION_MS
            val radiusPx = (ALL_CLEAR_PARTICLE_START_RADIUS_DP + (ALL_CLEAR_PARTICLE_END_RADIUS_DP - ALL_CLEAR_PARTICLE_START_RADIUS_DP) * t) * density
            val angleRad = Math.toRadians(particle.angleDeg.toDouble())
            val px = centerX + radiusPx * cos(angleRad).toFloat()
            val py = centerY + radiusPx * sin(angleRad).toFloat()
            allClearParticlePaint.alpha = ((1f - t) * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(px - half, py - half, px + half, py + half, allClearParticlePaint)
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
        val isGameOver = gameStateController.state == GameState.GAME_OVER
        if (!isGameOver && !stageClear) return false
        // STAGE-CLEAR-01: same lockout constant, same tap-slop fields --
        // only one of the two result screens is ever active at once (see
        // the frame loop's own mutual-exclusion note), so reusing
        // retryDownX/Y/retryTapSlopPx here is safe.
        val lockoutElapsedMs = if (isGameOver) gameOverElapsedMs else stageClearElapsedMs
        if (lockoutElapsedMs < RETRY_INPUT_LOCKOUT_MS) return false
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
                    // GAME-FLOW-01: ALL CLEAR's tap (stageNumber >=
                    // CURRENT_RELEASE_FINAL_STAGE) now goes through the
                    // exact same restartGame() GAME OVER's RETRY already
                    // uses -- it already resets everything PLAY AGAIN
                    // needs (stageNumber=1, score=0, fresh lives, wave
                    // state, MARK, every visual timer), so this is pure
                    // reuse, no new reset logic.
                    when {
                        isGameOver -> restartGame()
                        stageNumber >= CURRENT_RELEASE_FINAL_STAGE -> restartGame()
                        else -> onTapToNextStage()
                    }
                    invalidate()
                }
                true
            }
            else -> true
        }
    }

    /**
     * STAGE-SYSTEM-01: now wired to [advanceToNextStage] -- Stage 2+ is
     * still identical in every difficulty respect to Stage 1 this round
     * (same 3 QUBEs, same createInitialQubes() layout, same durability/
     * speed/rolling), per this round's own explicit "no difficulty design
     * yet" scope.
     */
    private fun onTapToNextStage() {
        advanceToNextStage()
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
     * each is replaced. `qubes` is rebuilt via [beginStageWaves]/
     * [spawnWave] (STAGE-DESIGN-01), so durability/rotation/movement
     * progress and every QubeSoundTracker are back to a genuinely fresh
     * state with zero references to any pre-restart QUBE. MarkController
     * is the one exception to "replace
     * the whole object" -- it already exposes a safe public [MarkController.clear],
     * so that's used directly instead, per this round's own "use the
     * existing API, don't touch MarkController internals" instruction.
     */
    private fun restartGame() {
        stageNumber = 1
        gameStateController = GameStateController()
        score = 0
        resetBoardAndVisuals()

        wasGameOver = false
        gameOverElapsedMs = 0L
    }

    /**
     * STAGE-SYSTEM-01: NEXT (from STAGE CLEAR) advances stageNumber and
     * returns the board/player/QUBEs/every visual timer to a fresh
     * baseline -- but, unlike [restartGame], deliberately never touches
     * [score] or [gameStateController]. Score is required to carry over
     * unchanged (this round's own explicit "NEXT never resets SCORE").
     *
     * gameStateController is reused, not reconstructed, so its `life`
     * (lives/churu count) carries over too -- GameStateController exposes
     * no public setter for life, so preserving it while reconstructing
     * the instance is impossible, and GameStateController.kt's own logic
     * is fixed-spec this round. Reusing the same instance is safe despite
     * not resetting it: `state` is already PLAYING (STAGE CLEAR never
     * sets GAME_OVER -- see the frame loop's mutual-exclusion note), and
     * `inContact`/`hitElapsedMs` need no explicit reset because
     * checkCollision() unconditionally reassigns `inContact = colliding`
     * every single call -- since the freshly-generated QUBEs and the
     * freshly-repositioned player never start on the same cell, the very
     * next checkCollision() call (the next frame) already recomputes
     * `inContact = false` on its own, regardless of its value going in.
     * No stale collision state can leak across a NEXT.
     */
    private fun advanceToNextStage() {
        stageNumber++
        resetBoardAndVisuals()
    }

    /**
     * Shared by [restartGame] (full reset) and [advanceToNextStage]
     * (score/lives kept) -- everything else a "start playing again" needs
     * reset to fresh-launch state: player position/direction, every QUBE
     * (a brand new [beginStageWaves] wave 0, so durability/rotation/
     * movement progress and every QubeSoundTracker are genuinely fresh,
     * same guarantee [restartGame] already relied on), MARK, the break-
     * flash list, every cosmetic timer, and STAGE CLEAR's own tracking
     * fields. boardLogic is reconstructed fresh the same way
     * [restartGame] already did (see that method's original doc) --
     * BoardLogic.kt itself is still never touched.
     */
    private fun resetBoardAndVisuals() {
        boardLogic = BoardLogic()
        // STAGE-DESIGN-01: spawns the (new) stageNumber's own wave 0 and
        // resets stageClear/pendingStageClearAfterBreak/stageWaveIndex --
        // replaces the old `qubes = createInitialQubes()` line.
        beginStageWaves()
        brokenQubes.clear()
        markController.clear()

        hitReaction = PoiHitReaction()
        walkVisual = TimedCosmeticFlag(durationMs = WALK_VISUAL_DURATION_MS)
        punchVisual = TimedCosmeticFlag(durationMs = PUNCH_VISUAL_DURATION_MS)
        scorePopup = TimedCosmeticFlag(durationMs = SCORE_POPUP_DURATION_MS)
        scorePopupText = ""

        lastMoveDirection = Direction.SOUTH
        hitVisualElapsedMs = 0L

        wasStageClear = false
        stageClearElapsedMs = 0L
    }

    /** SCORE-SYSTEM-01: zero-padded to at least 6 digits for display only
     * -- [score] itself stays a plain Int, never reformatted/stored as a
     * String anywhere else. */
    private fun scoreText(): String = score.toString().padStart(6, '0')

    override fun onMoveRequested(direction: Direction) {
        if (gameStateController.state == GameState.GAME_OVER || stageClear || stageStartActive) return
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
        if (gameStateController.state == GameState.GAME_OVER || stageClear || stageStartActive) return
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
                // STAGE-CLEAR-01: no BrokenQubeVisual is involved on this
                // path, so resolution is immediate -- score is already
                // awarded above, satisfying "score first, then CLEAR."
                // STAGE-DESIGN-01: resolveWaveBoundary() decides whether
                // this was just a wave boundary (spawns the next wave) or
                // the stage's actual last QUBE (sets stageClear).
                if (qubes.isEmpty()) resolveWaveBoundary()
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
        if (gameStateController.state == GameState.GAME_OVER || stageClear || stageStartActive) return
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
            // STAGE-CLEAR-01: score is already awarded above. Unlike the
            // ACTIVATE path, this doesn't resolve immediately -- brokenQubes
            // (just added above) must finish its ~200ms flash first; see
            // pendingStageClearAfterBreak's own doc. STAGE-DESIGN-01: the
            // frame loop's own resolution (once brokenQubes drains) now
            // calls resolveWaveBoundary() instead of always meaning
            // "stage over" -- see that function.
            if (qubes.isEmpty()) pendingStageClearAfterBreak = true
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
