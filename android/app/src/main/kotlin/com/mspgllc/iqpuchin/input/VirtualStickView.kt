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
 * Touch handling, VIRTUAL-STICK-TUNE-01 (direction lock): dragging the
 * knob away from center resolves a direction once past
 * [DEADZONE_FRACTION] of the base radius (smaller finger jitter near
 * center is ignored entirely), by comparing the absolute drag distance
 * on each axis at that instant -- horizontal wins only when strictly
 * greater than vertical, so an exact tie (or anything closer to
 * vertical) resolves to up/down, per the requested tie-break rule. That
 * resolved direction is then *locked* ([lockedDirection]): while still
 * outside the deadzone, further drag does not re-resolve or switch
 * direction at all, even if it drifts diagonally past what the raw
 * axis comparison would otherwise pick -- this is specifically what
 * keeps a thumb pushed "left" from flickering to up/down on natural
 * wobble. The lock is released only by the finger returning inside the
 * deadzone, or by lifting it (ACTION_UP/ACTION_CANCEL, which also snaps
 * the knob back to center) -- only then can the next deadzone crossing
 * resolve (and lock) a new direction. A move is requested exactly once
 * per lock acquired, mirroring the D-pad's own single-press-per-tap
 * behavior (no held-repeat); the visual knob position, once locked, is
 * also constrained to the locked axis (see [applyKnobOffset]) so its
 * position always matches what is actually locked in, never implying a
 * direction that isn't the one currently in effect.
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

    /** Null while centered/in the deadzone; otherwise the direction
     * resolved at the instant the deadzone was last crossed, held fixed
     * (locked) until the finger returns to the deadzone or is released
     * -- see the class doc. Doubles as the one-move-per-lock edge-fire
     * gate, since a lock's direction never changes during its lifetime. */
    private var lockedDirection: Direction? = null

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

        if (distance < deadzoneRadius) {
            // Back near center: release the lock (rule 4) so the next
            // deadzone crossing resolves a fresh direction.
            lockedDirection = null
            knobActive = false
            knobOffsetX = dx
            knobOffsetY = dy
            invalidate()
            return
        }

        if (distance > maxKnobRadius) {
            val scale = maxKnobRadius / distance
            dx *= scale
            dy *= scale
        }
        knobActive = true

        val alreadyLocked = lockedDirection
        if (alreadyLocked == null) {
            // First crossing since the last unlock: resolve and lock a
            // direction now (dominant-axis rule -- horizontal wins only
            // on a strict inequality, so a tie resolves to up/down), and
            // request the move exactly once for this newly-acquired lock.
            val direction = if (abs(dx) > abs(dy)) {
                if (dx > 0f) Direction.EAST else Direction.WEST
            } else {
                if (dy > 0f) Direction.SOUTH else Direction.NORTH
            }
            lockedDirection = direction
            applyKnobOffset(direction, dx, dy)
            listener?.onMoveRequested(direction)
        } else {
            // Still locked: direction does not change no matter how the
            // finger drifts while outside the deadzone (rule 3) -- only
            // the knob's own on-screen position keeps following, and
            // stays constrained to the locked axis (see applyKnobOffset).
            applyKnobOffset(alreadyLocked, dx, dy)
        }
        invalidate()
    }

    /** Draws the knob's offset constrained to [direction]'s own axis, so
     * its position always matches the direction actually locked in --
     * e.g. once locked to EAST/WEST, vertical drift never moves the knob
     * off the horizontal axis. */
    private fun applyKnobOffset(direction: Direction, dx: Float, dy: Float) {
        when (direction) {
            Direction.EAST, Direction.WEST -> {
                knobOffsetX = dx
                knobOffsetY = 0f
            }
            Direction.NORTH, Direction.SOUTH -> {
                knobOffsetX = 0f
                knobOffsetY = dy
            }
        }
    }

    private fun resetKnob() {
        // Rule 6: ACTION_UP/ACTION_CANCEL always releases the lock and
        // returns the knob to center.
        knobOffsetX = 0f
        knobOffsetY = 0f
        knobActive = false
        lockedDirection = null
        invalidate()
    }
}
