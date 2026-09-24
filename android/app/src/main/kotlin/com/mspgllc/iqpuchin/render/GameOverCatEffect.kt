package com.mspgllc.iqpuchin.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.mspgllc.iqpuchin.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * EFFECT-01B: the GAME OVER finishing sequence -- purely a presentation
 * layer, exactly like [GlassCrackEffect] (which this class never reads
 * from or writes to; both are independent, GameView composes them).
 * Four characters rush the screen in turn (まり -> あんこ -> あずさん,
 * each a quick 3-frame run + one impact frame), a deliberate pause, then
 * BB approaches in four slow, heavy stomps and delivers the final blow,
 * which this class renders as a short glass-shatter burst. GameView is
 * the only thing that decides *when* this starts (once EFFECT-01A's
 * HIT3 crack has fully settled) and *when* GAME OVER's own text/overlay
 * may finally show (once [isDone] for the elapsed time is true) -- this
 * class only ever answers "what should be drawn at elapsed time t."
 *
 * All positions/scales are expressed as fractions of the view's own
 * width/height/min-side, so the same timeline reads correctly on any
 * screen size, and every phase boundary is a plain elapsed-ms threshold
 * (never a frame count), so playback speed is identical regardless of
 * device frame rate, per this round's own "device performance shouldn't
 * change effect speed" instruction.
 */
class GameOverCatEffect(context: Context) {

    companion object {
        // EFFECT-01B-SPEED-01: real-device feedback said the whole
        // sequence read as too fast to actually register each character
        // -- every base timing constant below was doubled (BB's impact
        // hold/shatter ~1.8x, within this round's own 1.5-2x allowance),
        // with every *derived* constant (CHARACTER_MS, the per-character
        // start times, BB_RUN_MS, SHATTER_START_MS/END_MS,
        // TOTAL_DURATION_MS) simply recomputed from those via the same
        // formulas as before -- no structural change, timeline math only.
        //
        // まり/あんこ/あずさん: 3-frame rush (now 180ms/frame) + a longer
        // impact hold, with a brief silent gap between characters (GAP_MS
        // itself is untouched -- not called out in this round's spec --
        // so ANKO/AZUSAN's start-time offsets shift automatically only
        // because CHARACTER_MS grew, preserving the same relative
        // structure).
        // SOUND-01A: the following constants were made public (visibility
        // only, same values) so GameView's own catEffectSoundSchedule can
        // derive every GAMEOVER_CAT_STEP/IMPACT/BB_STOMP/BB_FINAL_IMPACT/
        // GLASS_SHATTER instant directly from them, instead of a second,
        // hardcoded copy of these millisecond values.
        const val RUN_FRAME_MS = 180L
        const val RUN_MS = RUN_FRAME_MS * 3 // 540ms
        private const val IMPACT_MS = 300L
        private const val GAP_MS = 80L
        private const val CHARACTER_MS = RUN_MS + IMPACT_MS // 840ms

        const val MARI_START_MS = 0L
        const val ANKO_START_MS = MARI_START_MS + CHARACTER_MS + GAP_MS // 920ms
        const val AZUSAN_START_MS = ANKO_START_MS + CHARACTER_MS + GAP_MS // 1840ms

        // The deliberate "did it end?" hush before BB -- this round's own
        // explicitly named 1100ms baseline (within its stated 1000-1200ms
        // range), replacing the previous 600ms.
        private const val PRE_BB_PAUSE_MS = 1100L
        const val BB_START_MS = AZUSAN_START_MS + CHARACTER_MS + PRE_BB_PAUSE_MS // 3780ms

        // BB: 4 distinct heavy stomps (now 520ms each -- still a fixed
        // pose-and-scale step per stomp, never a smooth/fast slide),
        // then a longer hold on the final-impact pose before the shatter
        // takes over.
        const val BB_STOMP_MS = 520L
        const val BB_RUN_MS = BB_STOMP_MS * 4 // 2080ms
        private const val BB_IMPACT_HOLD_MS = 440L
        const val SHATTER_START_MS = BB_START_MS + BB_RUN_MS + BB_IMPACT_HOLD_MS // 6300ms

        private const val SHATTER_BURST_MS = 280L
        private const val SHATTER_SHARD_MS = 840L
        const val SHATTER_END_MS = SHATTER_START_MS + SHATTER_SHARD_MS // 7140ms

        /** GameView's own single source of truth for "has the whole
         * sequence finished" -- see [isDone]. */
        const val TOTAL_DURATION_MS = SHATTER_END_MS

        // GAMEOVER-AFTERSHOCK-01: the window after GLASS_SHATTER's own
        // burst/shard debris ([drawShatter]) settles and before GAME
        // OVER's own dim/text takes over -- exactly SHATTER_SHARD_MS
        // (840ms) by construction, reused here rather than a second
        // hardcoded literal so it can never drift from SHATTER_START_MS/
        // SHATTER_END_MS.
        const val AFTERSHOCK_MS = SHATTER_END_MS - SHATTER_START_MS

        // Modest and monotonic -- much weaker than GAME OVER's own
        // GAME_OVER_DIM_MAX_ALPHA (170, in GameView), so the handoff once
        // catEffectDone reads as a continuation of the same darkening
        // rather than a hard cut.
        private const val AFTERSHOCK_DIM_MAX_ALPHA = 90
        private const val DUST_GLINT_WINDOW_MS = 220L
        private const val EDGE_FADE_START_MS = 250L
    }

    /** True once GAME OVER's own text/dim/TAP TO RETRY may finally show
     * -- see this class's own doc for why GameView gates on this rather
     * than showing GAME OVER the instant `state` becomes GAME_OVER. */
    fun isDone(elapsedMs: Long): Boolean = elapsedMs >= TOTAL_DURATION_MS

    /** True from [SHATTER_START_MS] onward -- GameView uses this to stop
     * drawing [GlassCrackEffect]'s own 3-stage cracks once this class's
     * own shatter burst takes over as "the glass, now breaking for
     * real." */
    fun isShatterActive(elapsedMs: Long): Boolean = elapsedMs >= SHATTER_START_MS

    private data class RunSequence(
        val run: List<Bitmap>,
        val impact: Bitmap,
        val startMs: Long,
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val peakScale: Float
    )

    private fun load(context: Context, resId: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inScaled = false }
        return BitmapFactory.decodeResource(context.resources, resId, options)
    }

    // Loaded once at construction, never per-frame -- same discipline as
    // PlayerSpriteSheet's own Azusan pose loading.
    private val mariRun = listOf(
        load(context, R.drawable.gameover_mari_run_01),
        load(context, R.drawable.gameover_mari_run_02),
        load(context, R.drawable.gameover_mari_run_03)
    )
    private val mariImpact = load(context, R.drawable.gameover_mari_impact)
    private val ankoRun = listOf(
        load(context, R.drawable.gameover_anko_run_01),
        load(context, R.drawable.gameover_anko_run_02),
        load(context, R.drawable.gameover_anko_run_03)
    )
    private val ankoImpact = load(context, R.drawable.gameover_anko_impact)
    private val azusanRun = listOf(
        load(context, R.drawable.gameover_azusan_run_01),
        load(context, R.drawable.gameover_azusan_run_02),
        load(context, R.drawable.gameover_azusan_run_03)
    )
    private val azusanImpact = load(context, R.drawable.gameover_azusan_punch_impact)
    private val bbRun = listOf(
        load(context, R.drawable.gameover_bb_run_01),
        load(context, R.drawable.gameover_bb_run_02),
        load(context, R.drawable.gameover_bb_run_03),
        load(context, R.drawable.gameover_bb_run_04)
    )
    private val bbFinalImpact = load(context, R.drawable.gameover_bb_final_impact)

    // Impact positions deliberately reuse GlassCrackEffect's own HIT1/
    // HIT2/HIT3 impact-fraction coordinates (see that class) -- まり/
    // あんこ/あずさん each land their blow on the same spot one of the
    // three life-loss cracks originated from, so the finishing sequence
    // reads as "still hitting the same already-cracked pane," not a
    // disconnected new location.
    private val sequences = listOf(
        RunSequence(mariRun, mariImpact, MARI_START_MS, fromX = -0.35f, fromY = 0.42f, toX = 0.30f, toY = 0.42f, peakScale = 0.55f),
        RunSequence(ankoRun, ankoImpact, ANKO_START_MS, fromX = 1.35f, fromY = 0.56f, toX = 0.74f, toY = 0.56f, peakScale = 0.58f),
        RunSequence(azusanRun, azusanImpact, AZUSAN_START_MS, fromX = 0.50f, fromY = -0.35f, toX = 0.50f, toY = 0.66f, peakScale = 0.60f)
    )

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val flashPaint = Paint().apply { color = Color.WHITE }

    private fun drawSequence(canvas: Canvas, seq: RunSequence, width: Int, height: Int, localMs: Long) {
        if (localMs < 0L || localMs >= CHARACTER_MS) return
        val inRun = localMs < RUN_MS
        val bitmap: Bitmap
        val progress: Float
        if (inRun) {
            val frameIndex = (localMs / RUN_FRAME_MS).toInt().coerceIn(0, seq.run.size - 1)
            bitmap = seq.run[frameIndex]
            progress = (localMs.toFloat() / RUN_MS).coerceIn(0f, 1f)
        } else {
            bitmap = seq.impact
            progress = 1f
        }
        // Ease-in: starts slow, rushes rapidly in the final approach --
        // "高速で突進" reads as a fast final rush, not a linear glide.
        val eased = progress * progress
        val cx = width * (seq.fromX + (seq.toX - seq.fromX) * eased)
        val cy = height * (seq.fromY + (seq.toY - seq.fromY) * eased)
        var scale = seq.peakScale * (0.15f + 0.85f * eased)
        if (!inRun) {
            // A brief punch-scale overshoot on impact (localMs measured
            // from the start of the impact hold).
            val impactT = ((localMs - RUN_MS).toFloat() / IMPACT_MS).coerceIn(0f, 1f)
            val punch = if (impactT < 0.35f) 1f + 0.18f * (impactT / 0.35f) else 1f + 0.18f * (1f - (impactT - 0.35f) / 0.65f)
            scale *= punch
        }
        drawBitmapCentered(canvas, bitmap, cx, cy, scale * width)
    }

    private fun drawBitmapCentered(canvas: Canvas, bitmap: Bitmap, cx: Float, cy: Float, targetWidthPx: Float) {
        val s = targetWidthPx / bitmap.width
        val matrix = Matrix()
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        matrix.postScale(s, s)
        matrix.postTranslate(cx, cy)
        canvas.drawBitmap(bitmap, matrix, bitmapPaint)
    }

    private fun drawBb(canvas: Canvas, width: Int, height: Int, localMs: Long) {
        if (localMs < 0L) return
        val runEnd = BB_RUN_MS
        val cx = width * 0.5f
        val cy = height * 0.5f
        if (localMs < runEnd) {
            val stompIndex = (localMs / BB_STOMP_MS).toInt().coerceIn(0, bbRun.size - 1)
            val stompLocalMs = localMs - stompIndex * BB_STOMP_MS
            // Discrete stomps, not a smooth slide -- each frame holds its
            // own fixed size with a quick settle-in "thud," per this
            // round's own "heavy, not fast" instruction.
            val stompProgress = (stompLocalMs.toFloat() / BB_STOMP_MS).coerceIn(0f, 1f)
            val settle = 1f - (1f - stompProgress) * (1f - stompProgress)
            // Each successive stomp is a visibly bigger step than the
            // last -- 0.30 -> 0.48 -> 0.68 -> 0.92 of screen width.
            val baseScales = floatArrayOf(0.30f, 0.48f, 0.68f, 0.92f)
            val prevScale = if (stompIndex == 0) baseScales[0] * 0.4f else baseScales[stompIndex - 1]
            val scale = prevScale + (baseScales[stompIndex] - prevScale) * settle
            drawBitmapCentered(canvas, bbRun[stompIndex], cx, cy, scale * width)
        } else if (localMs < runEnd + BB_IMPACT_HOLD_MS) {
            val holdT = ((localMs - runEnd).toFloat() / BB_IMPACT_HOLD_MS).coerceIn(0f, 1f)
            val punch = 1.0f + 0.10f * (1f - holdT)
            drawBitmapCentered(canvas, bbFinalImpact, cx, cy, 1.05f * punch * width)
        }
    }

    // -- Glass shatter: a deterministic (fixed-seed) shard burst, purely
    // Canvas-drawn -- no physics engine, per this round's own explicit
    // "doesn't need to be physically simulated" allowance.
    private data class Shard(
        val angleDeg: Float,
        val distanceFrac: Float,
        val sizeFrac: Float,
        val rotationSpeedDeg: Float,
        val delayFrac: Float,
        val large: Boolean
    )

    private val shards: List<Shard> = run {
        val rnd = Random(7777)
        (0 until 22).map { i ->
            Shard(
                angleDeg = rnd.nextFloat() * 360f,
                distanceFrac = 0.15f + rnd.nextFloat() * 0.65f,
                sizeFrac = if (i < 8) 0.05f + rnd.nextFloat() * 0.05f else 0.02f + rnd.nextFloat() * 0.03f,
                rotationSpeedDeg = (rnd.nextFloat() - 0.5f) * 720f,
                delayFrac = rnd.nextFloat() * 0.35f,
                large = i < 8
            )
        }
    }

    // GAMEOVER-FINAL-01: the radiating fracture lines from drawShatter's
    // own initial burst, precomputed once here (same fixed seed/formula
    // that function's own per-call `Random(9001)` already used every
    // frame -- moving it to a class-level val changes nothing about what
    // drawShatter renders, it just makes the same geometry reusable for
    // [drawResidue] below) so the post-shatter "cracks stay visible"
    // state can redraw them frozen at full length without recomputing.
    private data class BurstLine(val angleDeg: Float, val lengthFrac: Float, val thick: Boolean)

    private val burstLines: List<BurstLine> = run {
        val rnd = Random(9001)
        val lineCount = 16
        (0 until lineCount).map { i ->
            BurstLine(
                angleDeg = (360f / lineCount) * i + rnd.nextFloat() * 10f,
                lengthFrac = 0.35f + rnd.nextFloat() * 0.25f,
                thick = i % 4 == 0
            )
        }
    }

    private val shardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val shardEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 200, 210, 220)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val burstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private fun drawShatter(canvas: Canvas, width: Int, height: Int, localMs: Long, density: Float) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val minSide = min(width, height).toFloat()

        // Central impact -> radiating fracture (first SHATTER_BURST_MS).
        val burstT = (localMs.toFloat() / SHATTER_BURST_MS).coerceIn(0f, 1f)
        if (burstT > 0f) {
            for (line in burstLines) {
                val len = minSide * line.lengthFrac * burstT
                val rad = Math.toRadians(line.angleDeg.toDouble())
                val ex = cx + (cos(rad) * len).toFloat()
                val ey = cy + (sin(rad) * len).toFloat()
                burstPaint.strokeWidth = (if (line.thick) 3f else 1.6f) * density
                burstPaint.alpha = (235 * (1f - burstT * 0.3f)).toInt().coerceIn(0, 235)
                canvas.drawLine(cx, cy, ex, ey, burstPaint)
            }
        }

        // Shards spawn and fly outward + downward, fading out -- "big
        // pieces first, then small pieces, then off screen."
        val shardWindowMs = SHATTER_SHARD_MS.toFloat()
        for (shard in shards) {
            val localStart = shard.delayFrac * shardWindowMs
            val t = ((localMs - localStart) / (shardWindowMs * (1f - shard.delayFrac))).coerceIn(0f, 1f)
            if (localMs < localStart) continue
            val eased = t
            val rad = Math.toRadians(shard.angleDeg.toDouble())
            val travel = shard.distanceFrac * minSide * eased
            val fallExtra = minSide * 0.25f * eased * eased // gravity-like extra downward drift
            val px = cx + (cos(rad) * travel).toFloat()
            val py = cy + (sin(rad) * travel).toFloat() + fallExtra
            val rotation = shard.rotationSpeedDeg * (localMs.coerceAtLeast(0L) / 1000f)
            val size = shard.sizeFrac * minSide * (1f - 0.3f * eased)
            val alpha = ((1f - eased) * 255f).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue

            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(rotation)
            val path = Path().apply {
                moveTo(0f, -size)
                lineTo(size * 0.8f, size * 0.5f)
                lineTo(-size * 0.6f, size * 0.7f)
                close()
            }
            shardPaint.alpha = alpha
            shardEdgePaint.alpha = (alpha * 0.7f).toInt()
            canvas.drawPath(path, shardPaint)
            canvas.drawPath(path, shardEdgePaint)
            canvas.restore()
        }

        // A brief white flash right at the moment of impact sells the
        // "shattering" instant without lingering (never a long flash).
        val flashT = (localMs.toFloat() / 90f).coerceIn(0f, 1f)
        if (flashT < 1f) {
            flashPaint.alpha = ((1f - flashT) * 120f).toInt().coerceIn(0, 120)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }
    }

    /** Draws whatever this timeline says belongs on screen at
     * [elapsedMs] since GameView started the sequence. No-ops once
     * [isDone] -- see [drawResidue] for what GameView draws instead from
     * that point on. */
    fun draw(canvas: Canvas, width: Int, height: Int, density: Float, elapsedMs: Long) {
        if (elapsedMs < 0L || isDone(elapsedMs)) return

        if (elapsedMs >= SHATTER_START_MS) {
            drawShatter(canvas, width, height, elapsedMs - SHATTER_START_MS, density)
            return
        }

        for (seq in sequences) {
            drawSequence(canvas, seq, width, height, elapsedMs - seq.startMs)
        }
        if (elapsedMs >= BB_START_MS) {
            drawBb(canvas, width, height, elapsedMs - BB_START_MS)
        }
    }

    /** GAMEOVER-FINAL-01: the shattered pane's permanent, static
     * aftermath -- [drawShatter]'s own flying/fading shards are debris
     * that has flown off screen by [SHATTER_END_MS] (by design, alpha
     * reaches 0 exactly then), so once [isDone] this is the *only*
     * broken-glass visual GameView has left to show. It is deliberately
     * unrelated to [draw]'s own no-op-once-[isDone] contract: GameView
     * calls this separately, for as long as GAME OVER is showing, so the
     * full-face crack lines (frozen at the same full length/alpha
     * [drawShatter]'s own burst settles into after [SHATTER_BURST_MS])
     * and a handful of small embedded fragments near the impact point
     * stay on screen indefinitely -- reset only when GameView's own
     * restartGame() clears the state this class's [draw] itself is keyed
     * on (`catEffectDone`), never by this class. */
    fun drawResidue(canvas: Canvas, width: Int, height: Int, density: Float) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val minSide = min(width, height).toFloat()

        for (line in burstLines) {
            val len = minSide * line.lengthFrac
            val rad = Math.toRadians(line.angleDeg.toDouble())
            val ex = cx + (cos(rad) * len).toFloat()
            val ey = cy + (sin(rad) * len).toFloat()
            burstPaint.strokeWidth = (if (line.thick) 3f else 1.6f) * density
            burstPaint.alpha = 164 // the settled alpha drawShatter's own burst lines reach once burstT=1
            canvas.drawLine(cx, cy, ex, ey, burstPaint)
        }

        // A handful of small fragments left stuck near the impact point
        // -- fixed positions/rotation (never flying, never spinning),
        // reusing the same shard silhouette [drawShatter] uses for its
        // own flying debris so the two visually match, but only the
        // smaller ("not large") shards, kept close to center rather than
        // at their [Shard.distanceFrac]*[minSide] flown-out distance.
        for (shard in shards) {
            if (shard.large) continue
            val rad = Math.toRadians(shard.angleDeg.toDouble())
            val travel = shard.distanceFrac * minSide * 0.18f
            val px = cx + (cos(rad) * travel).toFloat()
            val py = cy + (sin(rad) * travel).toFloat()
            val size = shard.sizeFrac * minSide * 0.7f
            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(shard.angleDeg)
            val path = Path().apply {
                moveTo(0f, -size)
                lineTo(size * 0.8f, size * 0.5f)
                lineTo(-size * 0.6f, size * 0.7f)
                close()
            }
            shardPaint.alpha = 150
            shardEdgePaint.alpha = 100
            canvas.drawPath(path, shardPaint)
            canvas.drawPath(path, shardEdgePaint)
            canvas.restore()
        }
    }

    // -- GAMEOVER-AFTERSHOCK-01: "the destruction still has an echo" --
    // purely additive to everything above (never reads/writes shards,
    // burstLines, sequences, or any of the drawSequence/drawBb/
    // drawShatter/draw/drawResidue methods). Three independent,
    // fixed-seed geometry sets, each generated once here (same discipline
    // as [shards]/[burstLines] above) so nothing is regenerated per
    // frame and RETRY needs no reset of its own -- GameView only ever
    // calls the draw methods below while catEffectStarted/catEffectDone
    // say to, and those flags already reset for free on restartGame().

    private data class DelayedShard(
        val angleDeg: Float,
        val distanceFrac: Float,
        val sizeFrac: Float,
        val rotationSpeedDeg: Float,
        val delayMs: Long,
        val fallBoost: Float
    )

    // Small pieces that finish falling well after the big GLASS_SHATTER
    // debris -- delayMs spread 100-500ms per spec, size/rotation/fall
    // speed all varied per-shard so nothing reads as a single uniform
    // batch.
    private val aftershockDelayedShards: List<DelayedShard> = run {
        val rnd = Random(8888)
        (0 until 12).map {
            DelayedShard(
                angleDeg = rnd.nextFloat() * 360f,
                distanceFrac = 0.10f + rnd.nextFloat() * 0.45f,
                sizeFrac = 0.015f + rnd.nextFloat() * 0.025f,
                rotationSpeedDeg = (rnd.nextFloat() - 0.5f) * 900f,
                delayMs = 100L + (rnd.nextFloat() * 400f).toLong(),
                fallBoost = 0.7f + rnd.nextFloat() * 0.6f
            )
        }
    }

    private data class DustFleck(
        val angleDeg: Float,
        val distanceFrac: Float,
        val sizeFrac: Float,
        val delayMs: Long,
        val driftDeg: Float
    )

    // Fine glass dust -- each fleck only catches the light for a brief
    // window (see drawAftershock's glintWindowMs), never a continuous
    // sparkle, so this never reads as snow/stars/magic.
    private val aftershockDust: List<DustFleck> = run {
        val rnd = Random(8500)
        (0 until 26).map {
            DustFleck(
                angleDeg = rnd.nextFloat() * 360f,
                distanceFrac = 0.08f + rnd.nextFloat() * 0.5f,
                sizeFrac = 0.004f + rnd.nextFloat() * 0.006f,
                delayMs = 120L + (rnd.nextFloat() * 550f).toLong(),
                driftDeg = (rnd.nextFloat() - 0.5f) * 40f
            )
        }
    }

    private data class EdgeFragment(
        val xFrac: Float,
        val yFrac: Float,
        val sizeFrac: Float,
        val rotationDeg: Float,
        val crackLine: Boolean
    )

    // Small leftover glass at the four corners plus the four edge
    // midpoints -- deliberately kept off the center so GAME OVER's own
    // text (drawn before drawAftershockResidue, see GameView.onDraw)
    // always stays readable. A little per-fragment position jitter keeps
    // this from reading as a perfectly symmetric frame.
    private val aftershockEdgeFragments: List<EdgeFragment> = run {
        val rnd = Random(8700)
        val anchors = listOf(
            0.03f to 0.05f, 0.97f to 0.05f, 0.03f to 0.95f, 0.97f to 0.95f,
            0.02f to 0.5f, 0.98f to 0.5f, 0.5f to 0.03f, 0.5f to 0.97f
        )
        anchors.flatMapIndexed { i, anchor ->
            val (ax, ay) = anchor
            (0 until 2).map { j ->
                EdgeFragment(
                    xFrac = (ax + (rnd.nextFloat() - 0.5f) * 0.05f).coerceIn(0f, 1f),
                    yFrac = (ay + (rnd.nextFloat() - 0.5f) * 0.05f).coerceIn(0f, 1f),
                    sizeFrac = 0.02f + rnd.nextFloat() * 0.03f,
                    rotationDeg = rnd.nextFloat() * 360f,
                    crackLine = (i + j) % 3 == 0
                )
            }
        }
    }

    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val aftershockDimPaint = Paint().apply { color = Color.BLACK }

    /**
     * GAMEOVER-AFTERSHOCK-01: everything that happens between
     * GLASS_SHATTER's own burst ([drawShatter], via [draw] above)
     * settling and GAME OVER's own dim/text taking over -- delayed small
     * shards and glass dust falling in after the big pieces, a modest
     * dark vignette creeping in, and the screen-edge residue below
     * starting to fade into view. A pure function of [elapsedMs], no
     * caller-held state needed. GameView calls this once, right after its
     * existing [draw] call, under the same `catEffectStarted &&
     * !catEffectDone` guard that already gates [draw] -- so it
     * automatically stops (and RETRY implicitly clears it) the same way
     * [draw] already does.
     */
    fun drawAftershock(canvas: Canvas, width: Int, height: Int, density: Float, elapsedMs: Long) {
        if (elapsedMs < SHATTER_START_MS) return
        val localMs = (elapsedMs - SHATTER_START_MS).coerceIn(0L, AFTERSHOCK_MS)
        val minSide = min(width, height).toFloat()
        val cx = width * 0.5f
        val cy = height * 0.5f

        val dimT = localMs.toFloat() / AFTERSHOCK_MS
        if (dimT > 0f) {
            aftershockDimPaint.alpha = (AFTERSHOCK_DIM_MAX_ALPHA * dimT).toInt().coerceIn(0, AFTERSHOCK_DIM_MAX_ALPHA)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), aftershockDimPaint)
        }

        for (s in aftershockDelayedShards) {
            if (localMs < s.delayMs) continue
            val window = (AFTERSHOCK_MS - s.delayMs).coerceAtLeast(1L)
            val t = ((localMs - s.delayMs).toFloat() / window).coerceIn(0f, 1f)
            val rad = Math.toRadians(s.angleDeg.toDouble())
            val travel = s.distanceFrac * minSide * t
            // Quadratic, not linear -- gravity-like acceleration, per spec.
            val fallExtra = minSide * 0.30f * s.fallBoost * t * t
            val px = cx + (cos(rad) * travel).toFloat()
            val py = cy + (sin(rad) * travel).toFloat() + fallExtra
            val rotation = s.rotationSpeedDeg * (localMs - s.delayMs).coerceAtLeast(0L) / 1000f
            val size = s.sizeFrac * minSide * (1f - 0.2f * t)
            val alpha = ((1f - t) * 235f).toInt().coerceIn(0, 235)
            if (alpha <= 0) continue

            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(rotation)
            val path = Path().apply {
                moveTo(0f, -size)
                lineTo(size * 0.75f, size * 0.55f)
                lineTo(-size * 0.65f, size * 0.6f)
                close()
            }
            shardPaint.alpha = alpha
            shardEdgePaint.alpha = (alpha * 0.7f).toInt()
            canvas.drawPath(path, shardPaint)
            canvas.drawPath(path, shardEdgePaint)
            canvas.restore()
        }

        // Each fleck gets one brief up-down alpha pulse starting at its
        // own delay -- never a looping/continuous glow.
        for (d in aftershockDust) {
            if (localMs < d.delayMs) continue
            val tSinceDelay = localMs - d.delayMs
            if (tSinceDelay > DUST_GLINT_WINDOW_MS) continue
            val glintT = tSinceDelay.toFloat() / DUST_GLINT_WINDOW_MS
            val pulse = sin((glintT * Math.PI).toFloat()).coerceIn(0f, 1f)
            val baseRad = Math.toRadians(d.angleDeg.toDouble())
            val driftRad = Math.toRadians((d.angleDeg + d.driftDeg).toDouble())
            val drift = d.distanceFrac * minSide * 0.12f * glintT
            val px = cx + (cos(baseRad) * d.distanceFrac * minSide).toFloat() + (cos(driftRad) * drift).toFloat()
            val py = cy + (sin(baseRad) * d.distanceFrac * minSide).toFloat() + (sin(driftRad) * drift).toFloat() + minSide * 0.05f * glintT
            val size = d.sizeFrac * minSide
            dustPaint.color = Color.argb((pulse * 210f).toInt().coerceIn(0, 210), 235, 238, 242)
            canvas.drawCircle(px, py, size, dustPaint)
        }

        val edgeT = ((localMs - EDGE_FADE_START_MS).toFloat() / (AFTERSHOCK_MS - EDGE_FADE_START_MS)).coerceIn(0f, 1f)
        if (edgeT > 0f) {
            drawEdgeFragments(canvas, width, height, density, edgeT)
        }
    }

    /**
     * GAMEOVER-AFTERSHOCK-01: the screen-edge glass residue's permanent,
     * fully-settled state -- drawn for as long as GAME OVER is showing
     * (GameView calls this once, right after its existing [drawResidue]
     * call, under the same `catEffectDone` guard, so it shares that
     * method's own reset-on-RETRY lifecycle without needing one of its
     * own), so a glance at the corners/edges still reads as "this glass
     * really broke" beneath the GAME OVER text.
     */
    fun drawAftershockResidue(canvas: Canvas, width: Int, height: Int, density: Float) {
        drawEdgeFragments(canvas, width, height, density, 1f)
    }

    private fun drawEdgeFragments(canvas: Canvas, width: Int, height: Int, density: Float, alphaT: Float) {
        val minSide = min(width, height).toFloat()
        for (f in aftershockEdgeFragments) {
            val px = width * f.xFrac
            val py = height * f.yFrac
            val size = f.sizeFrac * minSide
            if (f.crackLine) {
                burstPaint.strokeWidth = 1.4f * density
                burstPaint.alpha = (140 * alphaT).toInt().coerceIn(0, 140)
                val rad = Math.toRadians(f.rotationDeg.toDouble())
                val ex = px + (cos(rad) * size * 1.6f).toFloat()
                val ey = py + (sin(rad) * size * 1.6f).toFloat()
                canvas.drawLine(px, py, ex, ey, burstPaint)
            } else {
                canvas.save()
                canvas.translate(px, py)
                canvas.rotate(f.rotationDeg)
                val path = Path().apply {
                    moveTo(0f, -size)
                    lineTo(size * 0.8f, size * 0.5f)
                    lineTo(-size * 0.6f, size * 0.7f)
                    close()
                }
                shardPaint.alpha = (170 * alphaT).toInt().coerceIn(0, 170)
                shardEdgePaint.alpha = (110 * alphaT).toInt().coerceIn(0, 110)
                canvas.drawPath(path, shardPaint)
                canvas.drawPath(path, shardEdgePaint)
                canvas.restore()
            }
        }
    }
}
