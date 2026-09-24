package com.mspgllc.iqpuchin.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.mspgllc.iqpuchin.R
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
 *
 * CAT-PAW-IMAGE-TITLE-01: the knob is now the real
 * `azusan_paw_back` photo (Azusan's paw as seen from above -- fur, no
 * pad, no claws) instead of a Canvas-drawn silhouette -- drawn upright,
 * never rotated, only translated. The touch-detection geometry (view
 * bounds, dead zone, angle math) is completely unchanged from before;
 * only the drawn ring/knob's own on-screen *size* now comes from
 * [ringRadiusPx]/[pawSizePx] (screen-width-relative, much smaller and
 * cuter than the old view-filling dish) -- see those fields' own docs
 * for exactly what does and doesn't change as a result.
 */
class RotationalStickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Touch distance (fraction of base radius) treated as dead --
         * no vector is reported, no haptic, no movement below this.
         * Still measured against the view's own (unchanged) touch
         * bounds -- see [updateKnob] -- never against [ringRadiusPx]. */
        const val DEAD_ZONE_FRACTION = 0.22f

        /** Knob's own max *visual* travel (fraction of [ringRadiusPx]).
         * CAT-PAW-IMAGE-TITLE-01 re-bases this fraction from the old
         * (large) touch-view radius onto the new (small) visual ring
         * radius, so the drawn paw now visually maxes out within its own
         * small ring rather than the old big dish -- this only changes
         * how far the *drawn* knob travels in pixels; the raw input
         * angle reported to [listener] is never clamped by this at all,
         * and neither is the dead zone (still touch-view-based, above),
         * so 360-degree input/movement/haptics are unaffected. */
        const val MAX_KNOB_FRACTION = 0.72f

        /** The visual stick's own outer diameter, as a fraction of the
         * device's screen width -- 15-16% per this round's spec (this is
         * the midpoint). Deliberately independent of the touch view's
         * own (much larger, unchanged) size -- see class doc. */
        const val RING_DIAMETER_FRACTION_OF_SCREEN_WIDTH = 0.155f

        /** The paw image's own drawn diameter, as a fraction of the
         * ring's diameter above -- 42-45% per this round's spec (this is
         * the midpoint). [PawActionButtonView] derives its own button
         * size from this same constant (times 1.1-1.15) so the two
         * controls' relative sizing stays anchored to one source. */
        const val PAW_SIZE_FRACTION_OF_RING_DIAMETER = 0.435f

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

    /** CAT-PAW-IMAGE-TITLE-01: the provided "fur/knuckle side" paw photo
     * -- loaded once (never re-decoded per frame), same
     * `inScaled = false` convention [render.PlayerSpriteSheet] already
     * established for res/drawable-nodpi art. */
    private val pawBitmap: Bitmap = BitmapFactory.decodeResource(
        context.resources, R.drawable.azusan_paw_back, BitmapFactory.Options().apply { inScaled = false }
    )
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val reusableDst = RectF()

    /** The visual ring's own radius in px -- screen-width-relative (see
     * [RING_DIAMETER_FRACTION_OF_SCREEN_WIDTH]), computed once since
     * screen width doesn't change over this view's lifetime. */
    private val ringRadiusPx =
        resources.displayMetrics.widthPixels * RING_DIAMETER_FRACTION_OF_SCREEN_WIDTH / 2f

    /** The drawn paw image's own diameter in px -- see
     * [PAW_SIZE_FRACTION_OF_RING_DIAMETER]. */
    private val pawSizePx = ringRadiusPx * 2f * PAW_SIZE_FRACTION_OF_RING_DIAMETER

    // CAT-PAW-CONTROL-UI-01: the outer ring is kept (per that round's
    // own spec -- it still marks the control's operating bounds) but
    // made visually weaker -- translucent and thinner than before -- so
    // the paw knob itself is the visual focus rather than this rim.
    // CAT-PAW-IMAGE-TITLE-01 only changes what radius this is drawn at
    // (see onDraw/[ringRadiusPx]), not this Paint itself.
    private val goldRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 205, 60)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private var knobOffsetX = 0f
    private var knobOffsetY = 0f

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
        // CAT-PAW-IMAGE-TITLE-01: the big Canvas-drawn dark dish is gone
        // entirely -- only a thin translucent ring (unchanged Paint, new
        // smaller radius) plus the paw photo itself remain.
        canvas.drawCircle(cx, cy, ringRadiusPx, goldRimPaint)
        drawPawKnob(canvas, cx, cy)
    }

    /** Draws [pawBitmap] centered on the current knob offset, upright
     * and never rotated (per this round's own spec) -- sized to
     * [pawSizePx] while preserving the source photo's own aspect ratio
     * (it happens to be ~1:1, so this is effectively square) rather than
     * stretching it. */
    private fun drawPawKnob(canvas: Canvas, cx: Float, cy: Float) {
        val knobCx = cx + knobOffsetX
        val knobCy = cy + knobOffsetY
        val aspect = pawBitmap.width.toFloat() / pawBitmap.height.toFloat()
        val dstW = if (aspect >= 1f) pawSizePx else pawSizePx * aspect
        val dstH = if (aspect >= 1f) pawSizePx / aspect else pawSizePx
        reusableDst.set(knobCx - dstW / 2f, knobCy - dstH / 2f, knobCx + dstW / 2f, knobCy + dstH / 2f)
        canvas.drawBitmap(pawBitmap, null, reusableDst, bitmapPaint)
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
        // CAT-PAW-IMAGE-TITLE-01: touch geometry (view-size-based) is
        // completely unchanged -- still the sole basis for the dead
        // zone. Only maxKnobRadius (the *drawn* knob's own clamp) is
        // re-based onto the new, smaller visual ring -- see
        // MAX_KNOB_FRACTION's own doc.
        val baseRadius = min(width, height) / 2f
        val maxKnobRadius = ringRadiusPx * MAX_KNOB_FRACTION
        val deadzoneRadius = baseRadius * DEAD_ZONE_FRACTION

        val dx = touchX - cx
        val dy = touchY - cy
        val distance = hypot(dx, dy)

        if (distance < deadzoneRadius) {
            knobOffsetX = 0f
            knobOffsetY = 0f
            committedSectorDeg = null
            listener?.onStickIdle()
            invalidate()
            return
        }

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
        committedSectorDeg = null
        listener?.onStickIdle()
        invalidate()
    }
}
