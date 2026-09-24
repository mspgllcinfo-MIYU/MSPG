package com.mspgllc.iqpuchin.input

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure

/**
 * CAT-PAW-CONTROL-UI-01: shared "top of Azusan's paw" silhouette -- the
 * furry, knuckle side a cat shows when it sets a paw flat on the floor
 * (fur, no visible pad, no claws) -- as opposed to [PawShape]'s existing
 * palm/pad-side silhouette (a broad pad plus four round toe beans, which
 * reads as the underside). [PawShape] itself is left completely
 * untouched -- [com.mspgllc.iqpuchin.input.VirtualStickView]'s own knob
 * still uses it unmodified. This is a new, separate drawing helper used
 * by [RotationalStickView]'s knob and [PawActionButtonView] so the two
 * active controls this build read as the same character's paw, not two
 * different UI languages. Purely a drawing helper -- carries no state
 * and never touches input/game logic.
 *
 * Built from four layered elements (never just a bare circle/ellipse):
 * a soft fur-tuft fringe sampled along the paw's own outline (so it
 * follows the actual silhouette rather than a separate approximate
 * ring), an asymmetric rounded "mitten" body (wider across the toe end,
 * narrower at the wrist -- a plain circle would not read as a paw), a
 * couple of short toe-crease strokes near the toe end suggesting where
 * the toes divide without drawing distinct round pads or claws, and a
 * soft highlight for a touch of volume.
 */
object CatPawShape {
    fun draw(canvas: Canvas, cx: Float, cy: Float, r: Float, fill: Paint, outline: Paint?) {
        val body = pawBodyPath(cx, cy, r)

        drawFurFringe(canvas, body, fill, r)

        canvas.drawPath(body, fill)
        outline?.let { canvas.drawPath(body, it) }

        drawHighlight(canvas, cx, cy, r)
        outline?.let { drawToeCreases(canvas, cx, cy, r, it) }
    }

    /** Asymmetric rounded body: wider/rounder across the toe end (top)
     * than the wrist end (bottom), built from four cubic segments rather
     * than a symmetric oval so it reads as a paw shape on its own, even
     * before the fringe/creases are added. */
    private fun pawBodyPath(cx: Float, cy: Float, r: Float): Path = Path().apply {
        moveTo(cx, cy + r * 0.55f)
        cubicTo(
            cx + r * 0.58f, cy + r * 0.50f,
            cx + r * 0.66f, cy + r * 0.05f,
            cx + r * 0.60f, cy - r * 0.30f
        )
        cubicTo(
            cx + r * 0.52f, cy - r * 0.62f,
            cx + r * 0.20f, cy - r * 0.72f,
            cx, cy - r * 0.68f
        )
        cubicTo(
            cx - r * 0.20f, cy - r * 0.72f,
            cx - r * 0.52f, cy - r * 0.62f,
            cx - r * 0.60f, cy - r * 0.30f
        )
        cubicTo(
            cx - r * 0.66f, cy + r * 0.05f,
            cx - r * 0.58f, cy + r * 0.50f,
            cx, cy + r * 0.55f
        )
        close()
    }

    /** Small fur-tuft circles centered exactly on [body]'s own perimeter
     * (via [PathMeasure], so they follow the real silhouette instead of
     * an approximate ring) and drawn *underneath* the solid body fill --
     * only each tuft's outer half survives once the body is painted over
     * it, giving a soft fluffy fringe rather than a hard geometric edge. */
    private fun drawFurFringe(canvas: Canvas, body: Path, fill: Paint, r: Float) {
        val measure = PathMeasure(body, true)
        val length = measure.length
        if (length <= 0f) return
        val tuftCount = 16
        val pos = FloatArray(2)
        for (i in 0 until tuftCount) {
            val distance = length * i / tuftCount
            if (measure.getPosTan(distance, pos, null)) {
                // Deterministic per-index jitter (no Random instance
                // needed) so consecutive tufts vary slightly in size --
                // avoids a perfectly uniform, mechanical-looking fringe.
                val jitter = 0.82f + 0.36f * ((i * 53) % 7) / 6f
                canvas.drawCircle(pos[0], pos[1], r * 0.15f * jitter, fill)
            }
        }
    }

    /** Soft upper-left highlight for a touch of volume -- one translucent
     * white oval, layered on top of the solid fill/outline/fringe rather
     * than standing in for the whole shape on its own. */
    private fun drawHighlight(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            style = Paint.Style.FILL
        }
        canvas.drawOval(
            cx - r * 0.34f, cy - r * 0.55f, cx + r * 0.04f, cy - r * 0.14f,
            highlightPaint
        )
    }

    /** Three short curved strokes near the toe end, suggesting where the
     * toes divide on the furred/knuckle side -- deliberately not round
     * pad shapes (that's [PawShape]'s own palm-side visual language) and
     * never claws. */
    private fun drawToeCreases(canvas: Canvas, cx: Float, cy: Float, r: Float, crease: Paint) {
        for (fx in listOf(-0.24f, 0f, 0.24f)) {
            val path = Path().apply {
                moveTo(cx + r * fx - r * 0.09f, cy - r * 0.42f)
                quadTo(cx + r * fx, cy - r * 0.56f, cx + r * fx + r * 0.09f, cy - r * 0.42f)
            }
            canvas.drawPath(path, crease)
        }
    }
}
