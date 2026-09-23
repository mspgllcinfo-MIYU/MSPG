package com.mspgllc.iqpuchin.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.mspgllc.iqpuchin.R

/**
 * EFFECT-01C: purely cosmetic "あずさん visibly punches the glass" overlay
 * shown every life-loss HIT (1..3), reusing the same 4 gameover_azusan_*
 * frames EFFECT-01B's GAME OVER sequence already uses -- kept as its own
 * class (not sharing state with [GameOverCatEffect]) so that class's own
 * GAME OVER timeline stays completely untouched, per this round's own
 * explicit instruction.
 *
 * This class only ever answers "what should be drawn at elapsed time t"
 * and "when does the punch actually land" ([IMPACT_START_MS]) -- it does
 * not know about life, HIT numbers, or [GlassCrackEffect] at all. GameView
 * is what uses [IMPACT_START_MS] to call [GlassCrackEffect]'s own
 * beginCrackHit at exactly the right instant, which is the entire fix
 * this round makes: a crack used to appear the instant life decremented
 * (with no visible cause); now it only appears once this punch visibly
 * connects.
 */
class LifeLossAzusanEffect(context: Context) {

    companion object {
        // Mirrors EFFECT-01B's own (now-fixed, untouched) GAME OVER
        // sequence pacing for あずさん specifically, per this round's own
        // "base it on EFFECT-01B's already-tuned speed" instruction --
        // a separate copy, not a shared constant, so changing this can
        // never affect GameOverCatEffect's own timeline.
        // SOUND-01A: made public (visibility only, same value) so
        // GameView's own lifeLossSoundSchedule can derive its AZUSAN_STEP
        // instants (0/RUN_FRAME_MS/RUN_FRAME_MS*2) from this single
        // constant instead of a second, hardcoded copy of 180L.
        const val RUN_FRAME_MS = 180L
        private const val RUN_MS = RUN_FRAME_MS * 3 // 540ms

        /** The instant the punch frame begins showing -- GameView calls
         * [com.mspgllc.iqpuchin.render.GlassCrackEffect]'s beginCrackHit
         * at exactly this elapsed time, so "the crack appears" and "the
         * punch touches the glass" are the same frame. */
        const val IMPACT_START_MS = RUN_MS

        private const val IMPACT_MS = 300L
        const val TOTAL_DURATION_MS = RUN_MS + IMPACT_MS // 840ms
    }

    private fun load(context: Context, resId: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inScaled = false }
        return BitmapFactory.decodeResource(context.resources, resId, options)
    }

    private val run = listOf(
        load(context, R.drawable.gameover_azusan_run_01),
        load(context, R.drawable.gameover_azusan_run_02),
        load(context, R.drawable.gameover_azusan_run_03)
    )
    private val impact = load(context, R.drawable.gameover_azusan_punch_impact)

    // Same impact-point convention GlassCrackEffect itself uses (HIT1/
    // HIT2/HIT3 fractions) -- duplicated here rather than read from that
    // class, so GlassCrackEffect stays completely unmodified per this
    // round's own instruction. Landing the punch exactly where that
    // HIT's crack will originate is what sells "あずさんが殴ったから割れた."
    private val impactFraction: Map<Int, Pair<Float, Float>> = mapOf(
        1 to (0.30f to 0.42f),
        2 to (0.74f to 0.56f),
        3 to (0.50f to 0.66f)
    )

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun isDone(elapsedMs: Long): Boolean = elapsedMs >= TOTAL_DURATION_MS

    /** Draws あずさん rushing in from above and punching down onto
     * [hitNumber]'s own crack-origin point. No-ops once [isDone]. */
    fun draw(canvas: Canvas, width: Int, height: Int, elapsedMs: Long, hitNumber: Int) {
        if (elapsedMs < 0L || isDone(elapsedMs)) return
        val (toX, toY) = impactFraction[hitNumber] ?: (0.5f to 0.5f)
        val fromX = toX
        val fromY = toY - 0.55f

        val inRun = elapsedMs < RUN_MS
        val bitmap: Bitmap
        val progress: Float
        if (inRun) {
            val frameIndex = (elapsedMs / RUN_FRAME_MS).toInt().coerceIn(0, run.size - 1)
            bitmap = run[frameIndex]
            progress = (elapsedMs.toFloat() / RUN_MS).coerceIn(0f, 1f)
        } else {
            bitmap = impact
            progress = 1f
        }
        val eased = progress * progress
        val cx = width * (fromX + (toX - fromX) * eased)
        val cy = height * (fromY + (toY - fromY) * eased)
        var scale = 0.5f * (0.2f + 0.8f * eased)
        if (!inRun) {
            val impactT = ((elapsedMs - RUN_MS).toFloat() / IMPACT_MS).coerceIn(0f, 1f)
            val punch = if (impactT < 0.3f) 1f + 0.2f * (impactT / 0.3f) else 1f + 0.2f * (1f - (impactT - 0.3f) / 0.7f)
            scale *= punch
        }

        val s = (scale * width) / bitmap.width
        val matrix = Matrix()
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        matrix.postScale(s, s)
        matrix.postTranslate(cx, cy)
        canvas.drawBitmap(bitmap, matrix, bitmapPaint)
    }
}
