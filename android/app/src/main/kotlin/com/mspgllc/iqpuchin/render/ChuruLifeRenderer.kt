package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * CATPUNCH-01: draws remaining life as a row of "ちゅーる" (churu treat
 * pouch) stick icons instead of hearts or a number -- purely
 * presentational, reads [life]/[maxLife] and draws that many pouches
 * (plus faint empty-slot outlines for the rest) each frame. Never
 * touches GameStateController itself; GameView just hands it the two
 * ints.
 */
class ChuruLifeRenderer {

    private val pouchBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(250, 235, 210)
        style = Paint.Style.FILL
    }
    private val pouchStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 90, 120)
        style = Paint.Style.FILL
    }
    private val pouchOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 80, 60)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val emptySlotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 250, 235, 210)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /** Draws left-to-right starting at ([x], [y]), each icon
     * [iconWidth] wide with a small gap, vertically centered on [y]. */
    fun draw(canvas: Canvas, life: Int, maxLife: Int, x: Float, y: Float, iconWidth: Float) {
        val iconHeight = iconWidth * 0.42f
        val gap = iconWidth * 0.25f
        for (i in 0 until maxLife) {
            val left = x + i * (iconWidth + gap)
            if (i < life) {
                drawChuru(canvas, left, y, iconWidth, iconHeight)
            } else {
                canvas.drawRoundRect(
                    left, y - iconHeight / 2f, left + iconWidth, y + iconHeight / 2f,
                    iconHeight * 0.4f, iconHeight * 0.4f, emptySlotPaint
                )
            }
        }
    }

    /** A single churu pouch: a rounded tube body with a twisted/pinched
     * tip at each end and one diagonal wrapper stripe -- enough to read
     * as "a treat pouch", not a heart or a plain bar. */
    private fun drawChuru(canvas: Canvas, left: Float, cy: Float, w: Float, h: Float) {
        val tubeLeft = left + w * 0.14f
        val tubeRight = left + w * 0.86f
        val top = cy - h / 2f
        val bottom = cy + h / 2f

        val body = Path().apply {
            addRoundRect(tubeLeft, top, tubeRight, bottom, h * 0.45f, h * 0.45f, Path.Direction.CW)
        }
        canvas.drawPath(body, pouchBodyPaint)

        val stripe = Path().apply {
            moveTo(tubeLeft + w * 0.10f, bottom)
            lineTo(tubeLeft + w * 0.30f, top)
            lineTo(tubeLeft + w * 0.44f, top)
            lineTo(tubeLeft + w * 0.24f, bottom)
            close()
        }
        canvas.save()
        canvas.clipPath(body)
        canvas.drawPath(stripe, pouchStripePaint)
        canvas.restore()

        canvas.drawPath(body, pouchOutlinePaint)

        // Pinched twist ends, just outside the tube on each side.
        drawTwist(canvas, tubeLeft, cy, h)
        drawTwist(canvas, tubeRight, cy, h)
    }

    private fun drawTwist(canvas: Canvas, x: Float, cy: Float, h: Float) {
        val path = Path().apply {
            moveTo(x, cy - h * 0.22f)
            lineTo(x, cy + h * 0.22f)
            lineTo(x - h * 0.05f, cy)
            close()
        }
        canvas.drawPath(path, pouchOutlinePaint)
    }
}
