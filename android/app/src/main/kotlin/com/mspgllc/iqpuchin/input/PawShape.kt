package com.mspgllc.iqpuchin.input

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Shared cat-paw silhouette (one broad main pad plus four toe beans),
 * used by both [PawActionButtonView] and [VirtualStickView]'s knob so
 * the two POI-themed controls read as the same visual language
 * (UI-CUTE-01). Purely a drawing helper -- carries no state and never
 * touches input/game logic.
 */
object PawShape {
    fun draw(canvas: Canvas, cx: Float, cy: Float, r: Float, fill: Paint, outline: Paint?) {
        val mainPad = Path().apply {
            addRoundRect(
                cx - r * 0.55f, cy - r * 0.05f, cx + r * 0.55f, cy + r * 0.75f,
                r * 0.4f, r * 0.4f, Path.Direction.CW
            )
        }
        canvas.drawPath(mainPad, fill)
        outline?.let { canvas.drawPath(mainPad, it) }

        val toeRadius = r * 0.24f
        val toeCenterFractions = listOf(
            -0.62f to -0.48f,
            -0.22f to -0.70f,
            0.22f to -0.70f,
            0.62f to -0.48f
        )
        for ((fx, fy) in toeCenterFractions) {
            val tx = cx + r * fx
            val ty = cy + r * fy
            canvas.drawCircle(tx, ty, toeRadius, fill)
            outline?.let { canvas.drawCircle(tx, ty, toeRadius, it) }
        }
    }
}
