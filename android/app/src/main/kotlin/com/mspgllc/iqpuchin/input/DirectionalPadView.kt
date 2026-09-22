package com.mspgllc.iqpuchin.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.mspgllc.iqpuchin.board.Direction

/**
 * CONTROL-SIMPLE-01: a classic 4-direction cross D-pad -- the left-hand
 * half of "don't make the player think about which control does what".
 * Like [SwipeInputView]/[VirtualStickView], this only ever moves the
 * PLAYER one discrete GridCoord cell per resolved direction via
 * [InputActionListener.onMoveRequested] -- GameView/BoardLogic/Direction
 * don't know or care which of the three move-input views is active. Kept
 * alongside those two (never replacing/deleting them -- see
 * MainActivity's MoveInputMode) so real-device comparison stays
 * possible.
 *
 * Touch model, deliberately simple: the square canvas is a 3x3 grid.
 * The four edge-center cells are the up/down/left/right arms; the
 * center cell and all four corners are dead (no direction). This is
 * what makes diagonal presses structurally impossible -- there is no
 * cell that maps to two directions at once, and the dead corners mean a
 * touch near a corner resolves to nothing rather than an ambiguous
 * guess. Only [MotionEvent.ACTION_DOWN]'s position decides the
 * direction (immediate, D-pad-button-style response -- no need to lift
 * first, matching how a physical cross pad works); a single
 * per-gesture latch means dragging across arms while still down, or
 * simply holding, can never fire a second move or auto-repeat -- exactly
 * one direction per gesture, mirroring how [SwipeInputView] fires at
 * most once per finger-down/up cycle and [VirtualStickView] fires
 * exactly once per newly-acquired lock.
 */
class DirectionalPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var listener: InputActionListener? = null

    /** Mirrors SwipeInputView/VirtualStickView/ActionInputSource's own attach(listener) pattern. */
    fun attach(listener: InputActionListener) {
        this.listener = listener
    }

    /** Set once at ACTION_DOWN and never re-evaluated until the next
     * ACTION_DOWN -- this is what stops a drag from one arm into another
     * (or simply holding) from firing a second move. */
    private var firedThisGesture = false

    /** Which arm (if any) is currently under the finger -- visual
     * highlight only; purely cosmetic, never re-decides the direction
     * already resolved (or not) at ACTION_DOWN. */
    private var pressedDirection: Direction? = null

    private val armFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
        style = Paint.Style.FILL
    }

    /** MIYU's pink accent, per the existing black/pink/gold palette --
     * lights an arm up while pressed, echoing the ACTION paw's own
     * gold-vs-glow state cue. */
    private val armPressedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 138, 178)
        style = Paint.Style.FILL
    }
    private val armOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 30, 10)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val hubFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 18, 22)
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 30, 10)
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        contentDescription = "DPAD"
    }

    /** Which cardinal arm (if any) contains (x, y), by the 3x3-grid rule
     * described in the class doc. Null for the center cell or any
     * corner -- a dead touch, not a fallback to the nearest arm. */
    private fun zoneAt(x: Float, y: Float): Direction? {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return null
        val col = (x / (w / 3f)).toInt().coerceIn(0, 2)
        val row = (y / (h / 3f)).toInt().coerceIn(0, 2)
        return when {
            col == 1 && row == 0 -> Direction.NORTH
            col == 1 && row == 2 -> Direction.SOUTH
            col == 0 && row == 1 -> Direction.WEST
            col == 2 && row == 1 -> Direction.EAST
            else -> null
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val direction = zoneAt(event.x, event.y)
                pressedDirection = direction
                firedThisGesture = false
                if (direction != null) {
                    listener?.onMoveRequested(direction)
                    firedThisGesture = true
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Visual feedback only -- the direction (or lack of one)
                // was already decided at ACTION_DOWN; firedThisGesture is
                // never re-armed here, so crossing into a different arm
                // mid-drag cannot fire again.
                val direction = zoneAt(event.x, event.y)
                if (direction != pressedDirection) {
                    pressedDirection = direction
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                firedThisGesture = false
                pressedDirection = null
                invalidate()
            }
            else -> return false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cellW = w / 3f
        val cellH = h / 3f

        drawArm(canvas, Direction.NORTH, cellW, 0f, cellW, cellH)
        drawArm(canvas, Direction.SOUTH, cellW, cellH * 2f, cellW, cellH)
        drawArm(canvas, Direction.WEST, 0f, cellH, cellW, cellH)
        drawArm(canvas, Direction.EAST, cellW * 2f, cellH, cellW, cellH)

        // Small dead hub in the center cell, purely so the cross reads
        // as one connected shape rather than four floating buttons.
        val hubRadius = minOf(cellW, cellH) * 0.32f
        canvas.drawCircle(w / 2f, h / 2f, hubRadius, hubFillPaint)
    }

    private fun drawArm(canvas: Canvas, direction: Direction, left: Float, top: Float, w: Float, h: Float) {
        val fill = if (pressedDirection == direction) armPressedFillPaint else armFillPaint
        val rect = RectF(left, top, left + w, top + h)
        val corner = minOf(w, h) * 0.18f
        canvas.drawRoundRect(rect, corner, corner, fill)
        canvas.drawRoundRect(rect, corner, corner, armOutlinePaint)
        drawArrow(canvas, direction, rect)
    }

    private fun drawArrow(canvas: Canvas, direction: Direction, rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val s = minOf(rect.width(), rect.height()) * 0.28f
        val path = Path()
        when (direction) {
            Direction.NORTH -> {
                path.moveTo(cx, cy - s)
                path.lineTo(cx - s, cy + s * 0.6f)
                path.lineTo(cx + s, cy + s * 0.6f)
            }
            Direction.SOUTH -> {
                path.moveTo(cx, cy + s)
                path.lineTo(cx - s, cy - s * 0.6f)
                path.lineTo(cx + s, cy - s * 0.6f)
            }
            Direction.WEST -> {
                path.moveTo(cx - s, cy)
                path.lineTo(cx + s * 0.6f, cy - s)
                path.lineTo(cx + s * 0.6f, cy + s)
            }
            Direction.EAST -> {
                path.moveTo(cx + s, cy)
                path.lineTo(cx - s * 0.6f, cy - s)
                path.lineTo(cx - s * 0.6f, cy + s)
            }
        }
        path.close()
        canvas.drawPath(path, arrowPaint)
    }
}
