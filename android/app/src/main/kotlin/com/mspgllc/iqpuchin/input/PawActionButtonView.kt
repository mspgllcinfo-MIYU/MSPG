package com.mspgllc.iqpuchin.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * UI-CUTE-01: POI-themed replacement for the old rectangular MARK/
 * ACTIVATE [android.widget.Button] -- draws a single gold cat-paw
 * instead of text. This view fills exactly the same LayoutParams
 * bounds MainActivity already gave the old button (same position,
 * same size, same touch area -- the whole view is the tap target, same
 * as a Button), so only the paint/shape and the press animation are
 * new. The MARK -> ACTIVATE -> mark-clear game logic itself lives
 * entirely in GameView/MarkController and is untouched by this class;
 * it only *reports* taps via [setOnActionClick] and *displays*
 * whichever state [ActionInputSource] tells it about via
 * [setAwaitingActivate], mirroring how the old Button's
 * onClickListener/text swap worked.
 */
class PawActionButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var onActionClick: (() -> Unit)? = null
    fun setOnActionClick(listener: () -> Unit) {
        onActionClick = listener
    }

    /** true once MARK has been placed and the next press will ACTIVATE;
     * false while waiting for a MARK. Set externally by
     * [ActionInputSource] from the real game state -- this view never
     * decides it itself, it only renders it (gold = waiting for MARK,
     * glowing = waiting for ACTIVATE), so no text is needed to tell the
     * two states apart. */
    private var awaitingActivate = false
    fun setAwaitingActivate(awaiting: Boolean) {
        if (awaitingActivate != awaiting) {
            awaitingActivate = awaiting
            invalidate()
        }
    }

    /** True while a finger is down and still over the button -- drives
     * the "pressed/sinks in" animation; the actual click still fires on
     * ACTION_UP, same as the Button this replaces. */
    private var pressed = false

    init {
        isClickable = true
        contentDescription = "ACTION"
    }

    private val padGold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
        style = Paint.Style.FILL
    }
    private val padOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 30, 10)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    /** Soft outer glow, only drawn while awaiting ACTIVATE -- readable
     * at a glance even with a thumb covering the paw itself. */
    private val glowRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 235, 150)
        style = Paint.Style.FILL
    }

    /** Bright highlight on the main pad, also ACTIVATE-only -- a second,
     * more central glow cue in case the outer ring is occluded. */
    private val glowCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 250, 210)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(width, height) / 2f

        // "Muniっと押した感覚": the whole paw visibly sinks/shrinks while held.
        val pressScale = if (pressed) 0.88f else 1f
        canvas.save()
        canvas.scale(pressScale, pressScale, cx, cy)

        if (awaitingActivate) {
            canvas.drawCircle(cx, cy, baseRadius * 1.05f, glowRingPaint)
        }

        PawShape.draw(canvas, cx, cy, baseRadius, padGold, padOutline)

        if (awaitingActivate) {
            canvas.drawCircle(cx, cy + baseRadius * 0.3f, baseRadius * 0.14f, glowCenterPaint)
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height
                if (pressed != inside) {
                    pressed = inside
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasPressed = pressed
                pressed = false
                invalidate()
                if (wasPressed) {
                    onActionClick?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
