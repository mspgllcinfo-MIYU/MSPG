package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
}
