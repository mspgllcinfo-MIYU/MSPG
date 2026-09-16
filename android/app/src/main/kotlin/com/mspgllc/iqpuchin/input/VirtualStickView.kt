package com.mspgllc.iqpuchin.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.mspgllc.iqpuchin.board.Direction
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Replaces the old 4-button cross D-pad with a virtual analog-stick
 * *control surface* -- the stick's own position is purely visual/touch
 * feedback, never sent into the game as an analog value. Internally this
 * still drives [InputActionListener.onMoveRequested] with the exact same
 * discrete [Direction] values the old D-pad buttons used (up->NORTH,
 * down->SOUTH, left->WEST, right->EAST -- unchanged from the final D-pad
 * mapping), and PLAYER still moves exactly one GridCoord cell per call,
 * with no debouncing/delay added here, matching how the D-pad buttons
 * worked (a discrete "press", not held-repeat).
 *
 * Touch handling: dragging the knob away from center resolves to a
 * direction once past [DEADZONE_FRACTION] of the base radius (smaller
 * finger jitter near center is ignored entirely), by comparing the
 * absolute drag distance on each axis -- horizontal wins only when
 * strictly greater than vertical, so an exact tie (or anything closer to
 * vertical) resolves to up/down, per the requested tie-break rule. A
 * move is only requested on the instant the resolved direction *changes*
 * (entering a new direction, or first leaving the deadzone) -- holding
 * the stick steady in one direction does not repeatedly fire, mirroring
 * the D-pad's own single-press-per-tap behavior; reaching the same
 * direction again requires passing back through the deadzone first.
 * Releasing the touch snaps the knob back to center and clears that
 * state, ready for a fresh press.
 */
class VirtualStickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Fraction of the base radius treated as dead -- no direction is
         * resolved and no move is requested until the knob is dragged
         * past this distance from center. */
        const val DEADZONE_FRACTION = 0.22f

        /** Fraction of the base radius the knob is allowed to visually
         * travel from center; drags beyond this are clamped so the knob
         * never draws outside its base. */
        const val MAX_KNOB_FRACTION = 0.85f
    }

    private var listener: InputActionListener? = null

    /** Mirrors ActionInputSource's own attach(listener) pattern. */
    fun attach(listener: InputActionListener) {
        this.listener = listener
    }

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val baseOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /** Knob color while centered/within the deadzone (not currently
     * resolving to any direction). */
    private val knobIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 110, 20)
        style = Paint.Style.FILL
    }

    /** Knob color while actively resolving a direction, past the
     * deadzone -- a clearly brighter color so which way is currently
     * being pushed reads at a glance even with a finger over it. */
    private val knobActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 51)
        style = Paint.Style.FILL
    }
    private val knobOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var knobOffsetX = 0f
    private var knobOffsetY = 0f
    private var knobActive = false

    /** The direction last actually sent to [listener], or null while
     * centered/in the deadzone -- the edge-detection state described in
     * the class doc. */
    private var lastFiredDirection: Direction? = null

    init {
        isClickable = true
        contentDescription = "STICK"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) / 2f

        canvas.drawCircle(cx, cy, baseRadius, basePaint)
        canvas.drawCircle(cx, cy, baseRadius, baseOutline)

        val knobRadius = baseRadius * 0.4f
        val knobPaint = if (knobActive) knobActivePaint else knobIdlePaint
        canvas.drawCircle(cx + knobOffsetX, cy + knobOffsetY, knobRadius, knobPaint)
        canvas.drawCircle(cx + knobOffsetX, cy + knobOffsetY, knobRadius, knobOutline)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateKnob(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetKnob()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateKnob(touchX: Float, touchY: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) / 2f
        val maxKnobRadius = baseRadius * MAX_KNOB_FRACTION
        val deadzoneRadius = baseRadius * DEADZONE_FRACTION

        var dx = touchX - cx
        var dy = touchY - cy
        val distance = hypot(dx, dy)
        if (distance > maxKnobRadius && distance > 0f) {
            val scale = maxKnobRadius / distance
            dx *= scale
            dy *= scale
        }
        knobOffsetX = dx
        knobOffsetY = dy

        if (distance < deadzoneRadius) {
            knobActive = false
            lastFiredDirection = null
            invalidate()
            return
        }
        knobActive = true

        // Dominant-axis resolution: horizontal only wins on a strict
        // inequality, so an exact tie (or anything closer to vertical)
        // resolves to up/down, per the requested tie-break rule.
        val direction = if (abs(dx) > abs(dy)) {
            if (dx > 0f) Direction.EAST else Direction.WEST
        } else {
            if (dy > 0f) Direction.SOUTH else Direction.NORTH
        }

        if (direction != lastFiredDirection) {
            lastFiredDirection = direction
            listener?.onMoveRequested(direction)
        }
        invalidate()
    }

    private fun resetKnob() {
        knobOffsetX = 0f
        knobOffsetY = 0f
        knobActive = false
        lastFiredDirection = null
        invalidate()
    }
}
