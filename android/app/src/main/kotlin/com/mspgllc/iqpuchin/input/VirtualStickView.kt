package com.mspgllc.iqpuchin.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.mspgllc.iqpuchin.board.Direction
import kotlin.math.abs
import kotlin.math.atan2
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
 * direction that isn't the one currently in effect. UI-CONTROL-02 adds
 * exactly one side effect at the same single lock-acquisition point: a
 * one-shot haptic tick (see [StickHaptics]) -- no condition here changed.
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

    // UI-CONTROL-02: pseudo-3D gamepad-stick repaint (concave gold-rimmed
    // dish + a raised knob that shifts shadow/highlight and squashes
    // toward the push direction) -- purely cosmetic, all drawn below in
    // onDraw/drawBase/drawKnob. Internal input stays 4-direction digital;
    // none of the deadzone/lock/direction fields or touch handling in
    // this file changed (see the one-line StickHaptics.tick call noted
    // in updateKnob).
    private val dishInnerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val goldRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 60)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val knobRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /** Highlight on the knob's raised side while centered/idle -- a
     * faint, direction-neutral sheen (ambient light, no push yet). */
    private val knobHighlightIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.FILL
    }

    /** Highlight while actively pushed -- brighter and warmer, and (see
     * [drawKnob]) repositioned toward the push direction each frame. */
    private val knobHighlightActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 240, 200)
        style = Paint.Style.FILL
    }

    /** Paw-mark color while centered/within the deadzone (not currently
     * resolving to any direction). */
    private val pawIdleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 110, 20)
        style = Paint.Style.FILL
    }

    /** Paw-mark color while actively resolving a direction, past the
     * deadzone -- a clearly brighter color so which way is currently
     * being pushed reads at a glance even with a finger over it. */
    private val pawActiveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 214, 51)
        style = Paint.Style.FILL
    }
    private val pawOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 30, 10)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
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

        drawBase(canvas, cx, cy, baseRadius)
        drawKnob(canvas, cx, cy, baseRadius)
    }

    /** The stick's outer "receiver": a concave gold-rimmed dish instead
     * of a flat tinted circle, so it reads as a real gamepad stick base
     * rather than a plain disc. */
    private fun drawBase(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val dishPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                cx, cy, r,
                intArrayOf(Color.rgb(8, 8, 10), Color.rgb(42, 40, 36)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r, dishPaint)
        // Inset shadow just inside the rim reinforces the "recessed
        // toward the center" concave read.
        canvas.drawCircle(cx, cy, r * 0.9f, dishInnerShadowPaint)
        canvas.drawCircle(cx, cy, r, goldRimPaint)
    }

    /** The raised knob: gradient-shaded sphere, a shadow that shifts
     * opposite the push direction, a highlight that shifts toward it,
     * and a slight squash along the push axis -- a cheap 2D stand-in for
     * "the stick tilting/leaning" without an actual 3D model. The gold
     * paw mark on top is always drawn upright (never rotated) so it
     * stays legible. */
    private fun drawKnob(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        val knobRadius = baseRadius * 0.42f
        val knobCx = cx + knobOffsetX
        val knobCy = cy + knobOffsetY

        val magnitude = hypot(knobOffsetX, knobOffsetY)
        val pushing = knobActive && magnitude > 0.01f
        val ux = if (pushing) knobOffsetX / magnitude else 0f
        val uy = if (pushing) knobOffsetY / magnitude else 0f

        // Drop shadow: offset opposite the push direction, as if the
        // knob is lifting/leaning toward the finger.
        val shadowShift = knobRadius * 0.30f
        val shadowCx = knobCx - ux * shadowShift
        val shadowCy = knobCy - uy * shadowShift + knobRadius * 0.2f
        canvas.drawOval(
            shadowCx - knobRadius * 0.85f, shadowCy - knobRadius * 0.7f,
            shadowCx + knobRadius * 0.85f, shadowCy + knobRadius * 0.9f,
            knobShadowPaint
        )

        canvas.save()
        if (pushing) {
            // Squash slightly along the push axis and bulge
            // perpendicular to it -- reads as "leaning" that way. The
            // rotate/scale/un-rotate keeps this squash axis-aligned with
            // the push direction while everything drawn below (the paw
            // mark included) stays upright afterward.
            val angleDeg = Math.toDegrees(atan2(uy, ux).toDouble()).toFloat()
            canvas.rotate(angleDeg, knobCx, knobCy)
            canvas.scale(0.92f, 1.06f, knobCx, knobCy)
            canvas.rotate(-angleDeg, knobCx, knobCy)
        }

        val knobFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                knobCx - knobRadius * 0.3f, knobCy - knobRadius * 0.3f, knobRadius * 1.4f,
                intArrayOf(Color.rgb(60, 58, 54), Color.rgb(14, 13, 12)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(knobCx, knobCy, knobRadius, knobFillPaint)
        canvas.drawCircle(knobCx, knobCy, knobRadius, knobRimPaint)

        // Highlight catching the side facing the push direction (or a
        // faint neutral sheen near the top-left while idle).
        val highlightDist = if (pushing) knobRadius * 0.38f else knobRadius * 0.1f
        val highlightUx = if (pushing) ux else -0.6f
        val highlightUy = if (pushing) uy else -0.6f
        val highlightPaint = if (pushing) knobHighlightActivePaint else knobHighlightIdlePaint
        canvas.drawCircle(
            knobCx + highlightUx * highlightDist,
            knobCy + highlightUy * highlightDist,
            knobRadius * 0.32f,
            highlightPaint
        )

        // Gold paw mark, same POI motif as the ACTION button.
        val pawFill = if (knobActive) pawActiveFillPaint else pawIdleFillPaint
        PawShape.draw(canvas, knobCx, knobCy, knobRadius * 0.85f, pawFill, pawOutlinePaint)

        canvas.restore()
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
            // UI-CONTROL-02: one short "tick" exactly here, at the same
            // instant a lock is newly acquired -- never while a lock is
            // merely held (the "else" branch below never reaches this
            // line), so it cannot repeat during a continuous push.
            StickHaptics.tick(context)
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
