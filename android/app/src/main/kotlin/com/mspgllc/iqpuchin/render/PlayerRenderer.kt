package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.mspgllc.iqpuchin.board.GridCoord
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws Poi, the black-cat PLAYER marker, at the projected board
 * position. Purely presentational -- like BoardRenderer/QubeRenderer, it
 * never touches game state and is driven entirely by values GameView
 * hands it each frame. Kept in its own class (previously a private
 * method inside BoardRenderer) specifically so Poi's look/animation can
 * be replaced or extended later without touching floor-tile drawing.
 *
 * [reactionProgress] (see [PoiHitReaction]) drives a brief, purely
 * cosmetic flinch -- a squash, an "ouch" eye shape, and a tail flick --
 * on top of the same idle pose every other frame uses; it defaults to 0f
 * (idle) and never affects the projected board position itself.
 */
class PlayerRenderer {

    private val furPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 18, 22)
        style = Paint.Style.FILL
    }
    private val furOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val eyeGoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
        style = Paint.Style.FILL
    }
    private val eyePupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val ouchEyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val crownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
        style = Paint.Style.FILL
    }
    private val medalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
        style = Paint.Style.FILL
    }
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 18, 22)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    fun draw(
        canvas: Canvas,
        projection: IsoProjection,
        position: GridCoord,
        scale: Float,
        reactionProgress: Float = 0f
    ) {
        val p = projection.toScreen(position.x.toFloat(), position.z.toFloat())
        val radius = (RenderConfig.PLAYER_SIZE_PX * scale) / 2f

        // Brief squash: shrink vertically / bulge horizontally at the
        // peak of the reaction pulse, exactly 1:1 when idle (progress 0).
        val squashY = 1f - 0.25f * reactionProgress
        val squashX = 1f + 0.18f * reactionProgress

        // Lifted above the tile plane, purely so it doesn't visually
        // blend into the floor outline it's standing on.
        val cx = p[0]
        val cy = p[1] - radius * squashY

        drawTail(canvas, cx, cy, radius, reactionProgress)

        canvas.save()
        canvas.scale(squashX, squashY, cx, cy)
        canvas.drawCircle(cx, cy, radius, furPaint)
        canvas.drawCircle(cx, cy, radius, furOutline)
        drawEar(canvas, cx - radius * 0.55f, cy - radius * 0.55f, radius)
        drawEar(canvas, cx + radius * 0.55f, cy - radius * 0.55f, radius)
        drawEyes(canvas, cx, cy, radius, reactionProgress)
        canvas.restore()

        drawCrown(canvas, cx, cy - radius * 1.05f, radius)
        canvas.drawCircle(cx, cy + radius * 0.75f, radius * 0.22f, medalPaint)
    }

    private fun drawEar(canvas: Canvas, tipX: Float, tipBaseY: Float, radius: Float) {
        val size = radius * 0.55f
        val path = Path().apply {
            moveTo(tipX, tipBaseY - size)
            lineTo(tipX - size * 0.5f, tipBaseY + size * 0.3f)
            lineTo(tipX + size * 0.5f, tipBaseY + size * 0.3f)
            close()
        }
        canvas.drawPath(path, furPaint)
        canvas.drawPath(path, furOutline)
    }

    private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, radius: Float, reactionProgress: Float) {
        val eyeOffsetX = radius * 0.38f
        val eyeY = cy - radius * 0.05f
        if (reactionProgress > 0.15f) {
            // "Ouch" -- simple angled strokes instead of round eyes near
            // the peak of the reaction pulse.
            drawOuchEye(canvas, cx - eyeOffsetX, eyeY, radius)
            drawOuchEye(canvas, cx + eyeOffsetX, eyeY, radius)
        } else {
            val eyeR = radius * 0.28f
            canvas.drawCircle(cx - eyeOffsetX, eyeY, eyeR, eyeGoldPaint)
            canvas.drawCircle(cx + eyeOffsetX, eyeY, eyeR, eyeGoldPaint)
            canvas.drawCircle(cx - eyeOffsetX, eyeY, eyeR * 0.45f, eyePupilPaint)
            canvas.drawCircle(cx + eyeOffsetX, eyeY, eyeR * 0.45f, eyePupilPaint)
        }
    }

    private fun drawOuchEye(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val w = radius * 0.22f
        val h = radius * 0.16f
        canvas.drawLine(cx - w, cy + h, cx, cy - h, ouchEyePaint)
        canvas.drawLine(cx, cy - h, cx + w, cy + h, ouchEyePaint)
    }

    private fun drawCrown(canvas: Canvas, cx: Float, baseY: Float, radius: Float) {
        val w = radius * 0.9f
        val h = radius * 0.4f
        val path = Path().apply {
            moveTo(cx - w / 2f, baseY)
            lineTo(cx - w / 2f, baseY - h * 0.5f)
            lineTo(cx - w / 4f, baseY - h)
            lineTo(cx, baseY - h * 0.4f)
            lineTo(cx + w / 4f, baseY - h)
            lineTo(cx + w / 2f, baseY - h * 0.5f)
            lineTo(cx + w / 2f, baseY)
            close()
        }
        canvas.drawPath(path, crownPaint)
    }

    /** Idle: tail rests low. While [reactionProgress] plays, its tip
     * flicks upward -- "reflexively raised" -- then eases back down as
     * the pulse fades. */
    private fun drawTail(canvas: Canvas, cx: Float, cy: Float, radius: Float, reactionProgress: Float) {
        val baseX = cx + radius * 0.7f
        val baseY = cy + radius * 0.5f
        val idleAngle = Math.toRadians(70.0).toFloat()
        val flickAngle = Math.toRadians(70.0 - 90.0 * reactionProgress).toFloat()
        val length = radius * 1.1f
        val tipX = baseX + length * cos(flickAngle)
        val tipY = baseY - length * sin(flickAngle)
        val controlX = baseX + length * 0.6f * cos(idleAngle)
        val controlY = baseY - length * 0.6f * sin(idleAngle)
        val path = Path().apply {
            moveTo(baseX, baseY)
            quadTo(controlX, controlY, tipX, tipY)
        }
        canvas.drawPath(path, tailPaint)
    }
}
