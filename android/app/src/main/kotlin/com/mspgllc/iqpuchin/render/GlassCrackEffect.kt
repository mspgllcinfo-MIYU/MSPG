package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * EFFECT-01A: a purely cosmetic "the phone's own glass just got cat-
 * punched" overlay. One independent crack pattern per life-loss HIT (up
 * to [MAX_HITS], matching GameStateController.STARTING_LIFE) -- GameView
 * only ever tells this class "HIT number N just happened" and "how many
 * HIT-stages should currently read as fully settled," never anything
 * about life/collision/GAME_OVER itself. No hit-testing of any kind is
 * done against these cracks; they are draw-only.
 *
 * Each HIT's pattern is generated once, lazily, from a fixed seed (see
 * [generatePattern]) -- organic-looking (radiating main cracks with
 * branches and micro-cracks) but identical every run, per this round's
 * own "controlled pattern, not fully random" instruction. A HIT's cracks
 * "grow" into place over [REVEAL_DURATION_MS] by revealing segments in
 * generation order with the in-progress segment's endpoint interpolated,
 * then stay fully drawn forever after (until GameView resets this
 * class's caller-held state on RETRY/PLAY AGAIN).
 */
class GlassCrackEffect {

    companion object {
        const val MAX_HITS = 3
        const val REVEAL_DURATION_MS = 200L
    }

    private enum class Tier { MAIN, BRANCH, MICRO }

    private data class Segment(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val tier: Tier,
        val order: Int
    )

    // Impact points as screen-fraction coordinates (0f..1f) -- fixed and
    // spread apart so HIT1/HIT2/HIT3 don't originate from the same spot,
    // and kept clear of the churu (top-left) / SCORE (top-right) / STAGE
    // (top-left, under churu) HUD text, which all live in the top ~90dp
    // band.
    private val impactFraction: Map<Int, Pair<Float, Float>> = mapOf(
        1 to (0.30f to 0.42f),
        2 to (0.74f to 0.56f),
        3 to (0.50f to 0.66f)
    )

    // How far each HIT's cracks reach, as a fraction of min(width,height)
    // -- HIT2 a bit bigger than HIT1, HIT3 reaching toward the screen's
    // edges, per spec.
    private val reachFraction: Map<Int, Float> = mapOf(1 to 0.30f, 2 to 0.38f, 3 to 0.55f)

    private val patternsByHit: Map<Int, List<Segment>> by lazy {
        (1..MAX_HITS).associateWith { generatePattern(it) }
    }

    /** Fixed seed (1000+hit) -- same organic-looking pattern every run,
     * never regenerated per-launch/per-device, so real-device testing of
     * this effect stays reproducible. */
    private fun generatePattern(hit: Int): List<Segment> {
        val rnd = Random(1000 + hit)
        val segments = mutableListOf<Segment>()
        var order = 0
        val mainCount = 5 + hit
        val mainAngles = (0 until mainCount).map { i ->
            (360f / mainCount) * i + rnd.nextFloat() * 20f - 10f
        }
        for (angle in mainAngles) {
            var x = 0f
            var y = 0f
            var dir = angle
            val segCount = 2 + rnd.nextInt(2)
            val segLenBase = 1f / segCount
            for (s in 0 until segCount) {
                dir += rnd.nextFloat() * 24f - 12f
                val len = segLenBase * (0.7f + rnd.nextFloat() * 0.6f)
                val nx = x + cosDeg(dir) * len
                val ny = y + sinDeg(dir) * len
                segments.add(Segment(x, y, nx, ny, Tier.MAIN, order++))
                if (rnd.nextFloat() < 0.7f) {
                    val bx = x + (nx - x) * (0.4f + rnd.nextFloat() * 0.3f)
                    val by = y + (ny - y) * (0.4f + rnd.nextFloat() * 0.3f)
                    val bdir = dir + (if (rnd.nextBoolean()) 1 else -1) * (35f + rnd.nextFloat() * 25f)
                    val blen = len * (0.35f + rnd.nextFloat() * 0.25f)
                    val bx2 = bx + cosDeg(bdir) * blen
                    val by2 = by + sinDeg(bdir) * blen
                    segments.add(Segment(bx, by, bx2, by2, Tier.BRANCH, order++))
                    if (rnd.nextFloat() < 0.6f) {
                        val mdir = bdir + (if (rnd.nextBoolean()) 1 else -1) * (30f + rnd.nextFloat() * 20f)
                        val mlen = blen * 0.5f
                        val mx2 = bx2 + cosDeg(mdir) * mlen
                        val my2 = by2 + sinDeg(mdir) * mlen
                        segments.add(Segment(bx2, by2, mx2, my2, Tier.MICRO, order++))
                    }
                }
                x = nx
                y = ny
            }
        }
        return segments
    }

    private fun cosDeg(deg: Float) = cos(Math.toRadians(deg.toDouble())).toFloat()
    private fun sinDeg(deg: Float) = sin(Math.toRadians(deg.toDouble())).toFloat()

    // GAMEOVER-GLASS-02: a second, independent crack system driven by the
    // GAME OVER finishing sequence (GameOverCatEffect) rather than by
    // life-loss HITs. Each of mari/anko/azusan/BB gets its own pattern,
    // fixed at generation time (same "controlled pattern" approach as
    // patternsByHit above) and revealed progressively as that character's
    // own impact plays out, then held forever after -- never erased by a
    // later stage. This is purely additive: it shares drawSegments (the
    // reveal/draw loop extracted from drawHit below) but never touches
    // patternsByHit/generatePattern/drawHit's own existing behavior.
    private enum class GoStage { MARI, ANKO, AZUSAN, BB }

    // Reuses HIT1/HIT2/HIT3's own impact points for mari/anko/azusan so
    // the finishing sequence's cracks originate from the same spots
    // GameOverCatEffect's own run-in animation already lands on (see that
    // class's doc comment). BB has no HIT slot, so it uses the same
    // screen-center origin GameOverCatEffect.drawBb uses for BB itself.
    private val goOriginFraction: Map<GoStage, Pair<Float, Float>> = mapOf(
        GoStage.MARI to impactFraction.getValue(1),
        GoStage.ANKO to impactFraction.getValue(2),
        GoStage.AZUSAN to impactFraction.getValue(3),
        GoStage.BB to (0.5f to 0.5f)
    )

    // Reach grows stage over stage, per spec (damage escalates) -- larger
    // than the corresponding HIT reach so the finishing sequence reads as
    // clearly worse than ordinary life-loss damage.
    private val goReachFraction: Map<GoStage, Float> = mapOf(
        GoStage.MARI to 0.34f,
        GoStage.ANKO to 0.44f,
        GoStage.AZUSAN to 0.66f,
        GoStage.BB to 0.85f
    )

    // Tied to GameOverCatEffect's own public timing constants (never
    // hardcoded copies of the millisecond literals) so this stays correct
    // even if that class's own constants ever change -- mari/anko/azusan
    // crack at their own impact instant, BB at its charge-in start (its
    // damage is then a continuous function of elapsedMs, see
    // bbRevealWindowMs below).
    private val goImpactMs: Map<GoStage, Long> = mapOf(
        GoStage.MARI to GameOverCatEffect.MARI_START_MS + GameOverCatEffect.RUN_MS,
        GoStage.ANKO to GameOverCatEffect.ANKO_START_MS + GameOverCatEffect.RUN_MS,
        GoStage.AZUSAN to GameOverCatEffect.AZUSAN_START_MS + GameOverCatEffect.RUN_MS,
        GoStage.BB to GameOverCatEffect.BB_START_MS
    )

    // BB's 4 stomps (at +0/520/1040/1560ms from BB_START_MS) each
    // incrementally extend/branch BB's own crack rather than starting a
    // new impact point (per spec) -- modeled as one continuous reveal
    // across all 4 stomps, reaching exactly 1f at BB_START_MS +
    // 4*BB_STOMP_MS = 3780+2080 = 5860ms, i.e. exactly
    // GameOverCatEffect's own (private) BB_FINAL_IMPACT instant, while
    // still passing through the 4 real stomp timestamps at 25/50/75% of
    // the window along the way.
    private val bbRevealWindowMs = GameOverCatEffect.BB_STOMP_MS * 4

    private val goPatterns: Map<GoStage, List<Segment>> by lazy {
        GoStage.values().associateWith { generateGoPattern(it) }
    }

    // Separate seeds (5001..5004) and its own independent Random instance
    // per call -- never shares patternsByHit's RNG sequence, so this
    // addition cannot perturb HIT1-3's existing patterns. azusan/BB bias
    // part of their main cracks toward mari/anko(/azusan)'s own origin
    // points (via atan2) so they visibly reach toward/connect with the
    // earlier damage, per spec.
    private fun generateGoPattern(stage: GoStage): List<Segment> {
        val (mainCount, seed, towardStages) = when (stage) {
            GoStage.MARI -> Triple(4, 5001, emptyList())
            GoStage.ANKO -> Triple(5, 5002, emptyList())
            GoStage.AZUSAN -> Triple(6, 5003, listOf(GoStage.MARI, GoStage.ANKO))
            GoStage.BB -> Triple(5, 5004, listOf(GoStage.MARI, GoStage.ANKO, GoStage.AZUSAN))
        }
        val rnd = Random(seed)
        val segments = mutableListOf<Segment>()
        var order = 0
        val (ox, oy) = goOriginFraction.getValue(stage)
        val biasedAngles = towardStages.take(2).map { other ->
            val (tx, ty) = goOriginFraction.getValue(other)
            Math.toDegrees(atan2((ty - oy).toDouble(), (tx - ox).toDouble())).toFloat()
        }
        val spreadCount = mainCount - biasedAngles.size
        val spreadAngles = (0 until spreadCount).map { i ->
            (360f / spreadCount) * i + rnd.nextFloat() * 20f - 10f
        }
        val mainAngles = biasedAngles + spreadAngles
        for (angle in mainAngles) {
            var x = 0f
            var y = 0f
            var dir = angle
            val segCount = 2 + rnd.nextInt(2)
            val segLenBase = 1f / segCount
            for (s in 0 until segCount) {
                dir += rnd.nextFloat() * 24f - 12f
                val len = segLenBase * (0.7f + rnd.nextFloat() * 0.6f)
                val nx = x + cosDeg(dir) * len
                val ny = y + sinDeg(dir) * len
                segments.add(Segment(x, y, nx, ny, Tier.MAIN, order++))
                if (rnd.nextFloat() < 0.7f) {
                    val bx = x + (nx - x) * (0.4f + rnd.nextFloat() * 0.3f)
                    val by = y + (ny - y) * (0.4f + rnd.nextFloat() * 0.3f)
                    val bdir = dir + (if (rnd.nextBoolean()) 1 else -1) * (35f + rnd.nextFloat() * 25f)
                    val blen = len * (0.35f + rnd.nextFloat() * 0.25f)
                    val bx2 = bx + cosDeg(bdir) * blen
                    val by2 = by + sinDeg(bdir) * blen
                    segments.add(Segment(bx, by, bx2, by2, Tier.BRANCH, order++))
                    if (rnd.nextFloat() < 0.6f) {
                        val mdir = bdir + (if (rnd.nextBoolean()) 1 else -1) * (30f + rnd.nextFloat() * 20f)
                        val mlen = blen * 0.5f
                        val mx2 = bx2 + cosDeg(mdir) * mlen
                        val my2 = by2 + sinDeg(mdir) * mlen
                        segments.add(Segment(bx2, by2, mx2, my2, Tier.MICRO, order++))
                    }
                }
                x = nx
                y = ny
            }
        }
        return segments
    }

    // Three-tier line weight/opacity (never a single uniform-width line,
    // per spec) plus a faint dark shadow offset for a bit of glass depth.
    // No blue/red -- white/gray/translucent only.
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val branchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 225, 225, 230)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val microPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 210, 210, 215)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * Draws HIT-stages 1..[fullyRevealedUpTo] fully settled, plus
     * [animatingHit] (if in 1..MAX_HITS) growing in at [animatingProgress]
     * (0f..1f). Callers keep [fullyRevealedUpTo] at [animatingHit]-1 while
     * that HIT is still animating, per [GlassCrackEffect]'s own class doc.
     */
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        density: Float,
        fullyRevealedUpTo: Int,
        animatingHit: Int,
        animatingProgress: Float
    ) {
        if (fullyRevealedUpTo <= 0 && animatingHit <= 0) return
        val minSide = minOf(width, height).toFloat()
        for (hit in 1..MAX_HITS) {
            val progress = when {
                hit <= fullyRevealedUpTo -> 1f
                hit == animatingHit -> animatingProgress
                else -> continue
            }
            if (progress <= 0f) continue
            drawHit(canvas, hit, width, height, minSide, density, progress)
        }
    }

    private fun drawHit(
        canvas: Canvas,
        hit: Int,
        width: Int,
        height: Int,
        minSide: Float,
        density: Float,
        progress: Float
    ) {
        val segments = patternsByHit[hit] ?: return
        val (fx, fy) = impactFraction[hit] ?: (0.5f to 0.5f)
        val originX = width * fx
        val originY = height * fy
        val reach = (reachFraction[hit] ?: 0.3f) * minSide
        drawSegments(canvas, segments, originX, originY, reach, density, progress)
    }

    private fun drawGoStage(
        canvas: Canvas,
        stage: GoStage,
        width: Int,
        height: Int,
        minSide: Float,
        density: Float,
        progress: Float
    ) {
        val segments = goPatterns[stage] ?: return
        val (fx, fy) = goOriginFraction.getValue(stage)
        val originX = width * fx
        val originY = height * fy
        val reach = goReachFraction.getValue(stage) * minSide
        drawSegments(canvas, segments, originX, originY, reach, density, progress)
    }

    // Verbatim body moved out of drawHit (EFFECT-01A's own original
    // reveal/draw loop, unchanged) so drawHit and the new drawGoStage
    // (GAMEOVER-GLASS-02) can share it without either one being able to
    // affect the other's output.
    private fun drawSegments(
        canvas: Canvas,
        segments: List<Segment>,
        originX: Float,
        originY: Float,
        reach: Float,
        density: Float,
        progress: Float
    ) {
        val totalSegments = segments.size
        val exactRevealed = totalSegments * progress
        val revealedCount = exactRevealed.toInt().coerceIn(0, totalSegments)
        val partialFraction = (exactRevealed - revealedCount).coerceIn(0f, 1f)

        for (seg in segments) {
            val fullyIn = seg.order < revealedCount
            val isPartial = seg.order == revealedCount
            if (!fullyIn && !isPartial) continue
            val segProgress = if (fullyIn) 1f else partialFraction
            if (segProgress <= 0f) continue

            val x1 = originX + seg.x1 * reach
            val y1 = originY + seg.y1 * reach
            val x2 = originX + (seg.x1 + (seg.x2 - seg.x1) * segProgress) * reach
            val y2 = originY + (seg.y1 + (seg.y2 - seg.y1) * segProgress) * reach

            val (paint, strokeWidthDp) = when (seg.tier) {
                Tier.MAIN -> mainPaint to 2.2f
                Tier.BRANCH -> branchPaint to 1.4f
                Tier.MICRO -> microPaint to 0.9f
            }
            shadowPaint.strokeWidth = (strokeWidthDp + 0.6f) * density
            canvas.drawLine(x1 + density, y1 + density, x2 + density, y2 + density, shadowPaint)
            paint.strokeWidth = strokeWidthDp * density
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }

    /**
     * GAMEOVER-GLASS-02: draws all GoStage cracks whose impact instant has
     * already passed as of [elapsedMs] (GameOverCatEffect's own elapsed-ms
     * clock, e.g. GameView's catEffectElapsedMs) -- mari/anko/azusan "run
     * in" over REVEAL_DURATION_MS like an ordinary HIT crack, then hold at
     * fully revealed; BB's crack instead grows continuously across all 4
     * of its stomps (see bbRevealWindowMs) and holds at fully revealed
     * from BB_FINAL_IMPACT (5860ms) onward. Callers are expected to stop
     * calling this once GLASS_SHATTER (6300ms) takes over, same as they
     * already do for the HIT1-3 draw(...) above.
     */
    fun drawGameOverStage(canvas: Canvas, width: Int, height: Int, density: Float, elapsedMs: Long) {
        val minSide = minOf(width, height).toFloat()
        for (stage in GoStage.values()) {
            val impactMs = goImpactMs.getValue(stage)
            if (elapsedMs < impactMs) continue
            val windowMs = if (stage == GoStage.BB) bbRevealWindowMs else REVEAL_DURATION_MS
            val progress = ((elapsedMs - impactMs).toFloat() / windowMs).coerceIn(0f, 1f)
            if (progress <= 0f) continue
            drawGoStage(canvas, stage, width, height, minSide, density, progress)
        }
    }
}
