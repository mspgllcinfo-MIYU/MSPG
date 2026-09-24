package com.mspgllc.iqpuchin.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * VIRTUAL-STICK-ROTATIONAL-PROTOTYPE-01: a 360-degree analog stick,
 * separate from [VirtualStickView] (kept fully intact/unused this build,
 * per this round's own "don't touch existing input classes" scope). The
 * outer ring is fixed; the knob follows the touch continuously (clamped
 * to [MAX_KNOB_FRACTION] of the base radius) rather than snapping to one
 * of 4 locked axes. Reports a raw, unquantized angle via
 * [RotationalMoveListener.onStickVector] -- GameView is the only place
 * that turns this into actual grid movement.
 *
 * Dead zone: [DEAD_ZONE_FRACTION] of the base radius, per this round's
 * own spec -- inside it, no vector is reported and [listener]'s
 * onStickIdle() fires instead.
 */
class RotationalStickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Touch distance (fraction of base radius) treated as dead --
         * no vector is reported, no haptic, no movement below this. */
        const val DEAD_ZONE_FRACTION = 0.22f

        /** Knob's own max visual travel (fraction of base radius) -- per
         * this round's own spec, distinct from VirtualStickView's 0.85f.
         * The raw input angle is unaffected by this clamp; only the
         * drawn knob position is clamped. */
        const val MAX_KNOB_FRACTION = 0.72f

        /** Degrees past a 45-degree sector boundary the angle must move
         * before the haptic sector actually changes -- stops a thumb
         * held right at a boundary from chattering. */
        const val SECTOR_HYSTERESIS_DEG = 10f

        /** Minimum time between two sector-change haptics -- a rapid
         * sweep across several sectors still only buzzes at most once
         * per this interval, never once per sector crossed. */
        const val SECTOR_HAPTIC_REFRACTORY_MS = 80L

        private const val SECTOR_STEP_DEG = 45f
    }

    private var listener: RotationalMoveListener? = null

    fun attach(listener: RotationalMoveListener) {
        this.listener = listener
    }

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
    private val knobRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pawIdleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 110, 20)
        style = Paint.Style.FILL
    }
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

    /** Null while centered/in the dead zone; otherwise the currently
     * "committed" 45-degree sector center (0/45/90/.../315), used only
     * for haptic B's hysteresis -- never read by movement itself, which
     * always uses the raw continuous angle reported to [listener]. */
    private var committedSectorDeg: Float? = null
    private var lastSectorHapticUptimeMs = 0L

    init {
        isClickable = true
        contentDescription = "ROTATIONAL_STICK"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) / 2f
        drawBase(canvas, cx, cy, baseRadius)
        drawKnob(canvas, cx, cy, baseRadius)
    }

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
        canvas.drawCircle(cx, cy, r * 0.9f, dishInnerShadowPaint)
        canvas.drawCircle(cx, cy, r, goldRimPaint)
    }

    private fun drawKnob(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        val knobRadius = baseRadius * 0.42f
        val knobCx = cx + knobOffsetX
        val knobCy = cy + knobOffsetY

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

        val pawFill = if (knobActive) pawActiveFillPaint else pawIdleFillPaint
        PawShape.draw(canvas, knobCx, knobCy, knobRadius * 0.85f, pawFill, pawOutlinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Section 8.A: one light tick on touch down, regardless
                // of where inside the base the finger first lands.
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                updateKnob(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
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
        val deadzoneRadius = baseRadius * DEAD_ZONE_FRACTION

        val dx = touchX - cx
        val dy = touchY - cy
        val distance = hypot(dx, dy)

        if (distance < deadzoneRadius) {
            knobActive = false
            knobOffsetX = 0f
            knobOffsetY = 0f
            committedSectorDeg = null
            listener?.onStickIdle()
            invalidate()
            return
        }

        knobActive = true
        val knobScale = if (distance > maxKnobRadius) maxKnobRadius / distance else 1f
        knobOffsetX = dx * knobScale
        knobOffsetY = dy * knobScale

        // Raw angle is always reported as-is -- the dead zone/clamp above
        // only ever gate *whether* a vector is reported, never its value.
        val angleRad = atan2(dy, dx)
        listener?.onStickVector(angleRad)
        updateSectorHaptic(angleRad)
        invalidate()
    }

    /** Section 8.B: an 8-sector (45-degree) haptic notification layered
     * on top of the fully continuous movement direction -- movement
     * itself never quantizes, only this feedback does. */
    private fun updateSectorHaptic(angleRad: Float) {
        val angleDeg = normalizeDeg(Math.toDegrees(angleRad.toDouble()).toFloat())
        val current = committedSectorDeg
        if (current == null) {
            committedSectorDeg = nearestSectorCenter(angleDeg)
            fireSectorHapticIfAllowed()
            return
        }
        if (angularDistanceDeg(angleDeg, current) > SECTOR_STEP_DEG / 2f + SECTOR_HYSTERESIS_DEG) {
            val next = nearestSectorCenter(angleDeg)
            if (next != current) {
                committedSectorDeg = next
                fireSectorHapticIfAllowed()
            }
        }
    }

    private fun fireSectorHapticIfAllowed() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSectorHapticUptimeMs >= SECTOR_HAPTIC_REFRACTORY_MS) {
            lastSectorHapticUptimeMs = now
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    private fun nearestSectorCenter(deg: Float): Float =
        normalizeDeg((deg / SECTOR_STEP_DEG).roundToInt() * SECTOR_STEP_DEG)

    private fun angularDistanceDeg(a: Float, b: Float): Float {
        var diff = (a - b) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return abs(diff)
    }

    private fun resetKnob() {
        knobOffsetX = 0f
        knobOffsetY = 0f
        knobActive = false
        committedSectorDeg = null
        listener?.onStickIdle()
        invalidate()
    }
}
