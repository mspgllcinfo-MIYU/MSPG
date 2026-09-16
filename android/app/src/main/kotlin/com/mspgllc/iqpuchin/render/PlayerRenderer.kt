package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.mspgllc.iqpuchin.board.GridCoord
import com.mspgllc.iqpuchin.input.PawShape
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws "アズさん" (Azusan), the PLAYER marker, at the projected board
 * position. Purely presentational -- like BoardRenderer/QubeRenderer, it
 * never touches game state and is driven entirely by values GameView
 * hands it each frame. Kept in its own class (previously a private
 * method inside BoardRenderer) specifically so the character's look/
 * animation can be replaced or extended later without touching
 * floor-tile drawing.
 *
 * CATPUNCH-01: replaced the previous solid-black "Poi" design with a
 * black-heavy tortoiseshell cat per the provided character reference --
 * a near-black base circle (still the dominant silhouette color, per
 * "白はほぼ使用しない/黒多め") with a few rust-brown patches clipped to
 * that same circle, pink paw pads (reusing [PawShape], the same shape
 * already used for the ACTION button, for visual consistency), a
 * slightly thicker tail, and pink inner ears. The round base-circle body
 * and crown were already close to the reference's "roundish deformed,
 * not slim" chibi proportions and king-crown, so those are kept as-is.
 *
 * [reactionProgress] (see [PoiHitReaction]) drives a brief, purely
 * cosmetic flinch -- a squash, an "ouch" eye shape, and a tail flick --
 * on top of the same idle pose every other frame uses; it defaults to 0f
 * (idle) and never affects the projected board position itself.
 *
 * [angerLevel] (0=normal/life3, 1=life2, 2=life1) drives a purely
 * cosmetic eyebrow/eye change -- per spec this must NEVER be read as a
 * gameplay stat anywhere else (no speed/attack/cooldown changes are
 * tied to it); GameView derives it from remaining life and nothing
 * else consumes it. Ignored while [reactionProgress] is actively
 * playing an "ouch" flinch, so the two expressions never fight.
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
    // CATPUNCH-01: sparse tortoiseshell patches over the near-black base
    // -- kept a minority of the silhouette by design (few, small ovals).
    private val tortoisePatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(122, 68, 34)
        style = Paint.Style.FILL
    }
    private val innerEarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 165, 176)
        style = Paint.Style.FILL
    }
    private val pawPadFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 158, 186)
        style = Paint.Style.FILL
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
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
    }
    // CATPUNCH-01: anger eyebrows -- same stroke idiom as ouchEyePaint,
    // just angled differently per angerLevel (see drawAngerBrow).
    private val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 18, 22)
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }

    fun draw(
        canvas: Canvas,
        projection: IsoProjection,
        position: GridCoord,
        scale: Float,
        reactionProgress: Float = 0f,
        angerLevel: Int = 0
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
        drawTortoisePattern(canvas, cx, cy, radius)
        canvas.drawCircle(cx, cy, radius, furOutline)
        drawEar(canvas, cx - radius * 0.55f, cy - radius * 0.55f, radius)
        drawEar(canvas, cx + radius * 0.55f, cy - radius * 0.55f, radius)
        drawEyes(canvas, cx, cy, radius, reactionProgress, angerLevel)
        drawForepaws(canvas, cx, cy, radius)
        canvas.restore()

        drawCrown(canvas, cx, cy - radius * 1.05f, radius)
        canvas.drawCircle(cx, cy + radius * 0.75f, radius * 0.22f, medalPaint)
    }

    /** A few rust-brown patches clipped to the body circle -- kept small
     * and off-symmetric so the base near-black fur still reads as
     * dominant at a glance, per "黒多めのサビ柄". */
    private fun drawTortoisePattern(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) })
        drawPatch(canvas, cx - radius * 0.38f, cy - radius * 0.5f, radius * 0.40f, radius * 0.28f, -18f)
        drawPatch(canvas, cx + radius * 0.48f, cy - radius * 0.05f, radius * 0.32f, radius * 0.46f, 20f)
        drawPatch(canvas, cx - radius * 0.02f, cy + radius * 0.58f, radius * 0.46f, radius * 0.30f, 6f)
        canvas.restore()
    }

    private fun drawPatch(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, rotationDeg: Float) {
        canvas.save()
        canvas.rotate(rotationDeg, cx, cy)
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, tortoisePatchPaint)
        canvas.restore()
    }

    /** Two small pink-padded paws at the body's front-bottom edge --
     * reuses [PawShape], the same shape drawn on the ACTION button, so
     * the character's paws and the UI paw read as the same design
     * language. Always visible (not punch-specific) per "ピンク肉球". */
    private fun drawForepaws(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val pawY = cy + radius * 0.78f
        val pawR = radius * 0.30f
        PawShape.draw(canvas, cx - radius * 0.36f, pawY, pawR, pawPadFillPaint, furOutline)
        PawShape.draw(canvas, cx + radius * 0.36f, pawY, pawR, pawPadFillPaint, furOutline)
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

        val innerPath = Path().apply {
            moveTo(tipX, tipBaseY - size * 0.62f)
            lineTo(tipX - size * 0.28f, tipBaseY + size * 0.14f)
            lineTo(tipX + size * 0.28f, tipBaseY + size * 0.14f)
            close()
        }
        canvas.drawPath(innerPath, innerEarPaint)
    }

    private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, radius: Float, reactionProgress: Float, angerLevel: Int) {
        val eyeOffsetX = radius * 0.38f
        val eyeY = cy - radius * 0.05f
        if (reactionProgress > 0.15f) {
            // "Ouch" -- simple angled strokes instead of round eyes near
            // the peak of the reaction pulse. Takes priority over anger
            // so the two cosmetic states never overlap.
            drawOuchEye(canvas, cx - eyeOffsetX, eyeY, radius)
            drawOuchEye(canvas, cx + eyeOffsetX, eyeY, radius)
        } else {
            // Big and round per "大きく丸い目" -- larger than the
            // original Poi design's eyeR.
            val eyeR = radius * 0.34f * (1f - 0.08f * angerLevel)
            canvas.drawCircle(cx - eyeOffsetX, eyeY, eyeR, eyeGoldPaint)
            canvas.drawCircle(cx + eyeOffsetX, eyeY, eyeR, eyeGoldPaint)
            canvas.drawCircle(cx - eyeOffsetX, eyeY, eyeR * 0.45f, eyePupilPaint)
            canvas.drawCircle(cx + eyeOffsetX, eyeY, eyeR * 0.45f, eyePupilPaint)
            if (angerLevel > 0) {
                drawAngerBrows(canvas, cx, eyeY, radius, eyeOffsetX, angerLevel)
            }
        }
    }

    private fun drawOuchEye(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val w = radius * 0.22f
        val h = radius * 0.16f
        canvas.drawLine(cx - w, cy + h, cx, cy - h, ouchEyePaint)
        canvas.drawLine(cx, cy - h, cx + w, cy + h, ouchEyePaint)
    }

    /** Furrowed brows over each eye, angled inward-down toward the nose --
     * steeper at [angerLevel] 2 than 1 -- so "残り1であることが視覚的に
     * も分かる" without changing anything about ability/speed/timing. */
    private fun drawAngerBrows(canvas: Canvas, cx: Float, eyeY: Float, radius: Float, eyeOffsetX: Float, angerLevel: Int) {
        val browY = eyeY - radius * 0.30f
        val tilt = if (angerLevel >= 2) radius * 0.24f else radius * 0.14f
        val halfWidth = radius * 0.20f

        // Left brow: outer end high, inner end (toward center) low.
        canvas.drawLine(cx - eyeOffsetX - halfWidth, browY - tilt * 0.4f, cx - eyeOffsetX + halfWidth, browY + tilt, browPaint)
        // Right brow: mirrored.
        canvas.drawLine(cx + eyeOffsetX + halfWidth, browY - tilt * 0.4f, cx + eyeOffsetX - halfWidth, browY + tilt, browPaint)
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
