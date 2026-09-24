package com.mspgllc.iqpuchin.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.mspgllc.iqpuchin.R
import kotlin.math.abs

/**
 * UI-CUTE-01: POI-themed replacement for the old rectangular MARK/
 * ACTIVATE [android.widget.Button] -- draws a single gold cat-paw
 * instead of text. This view fills exactly the same LayoutParams
 * bounds MainActivity already gave the old button (same position,
 * same size, same touch area -- the whole view is the tap target, same
 * as a Button), so only the paint/shape and the press animation are
 * new.
 *
 * CONTROL-SIMPLE-01 first split this view's single generic click into
 * distinct gestures so GameView never has to guess intent from board
 * state (a QUBE happening to be adjacent no longer changes what a tap
 * does): input method alone decides MARK/ACTIVATE versus CAT_PUNCH.
 * CONTROL-SIMPLE-02 changes which slide direction means CAT_PUNCH (real-
 * device feedback: a horizontal "shut" flick reads more naturally on
 * this button than a downward one) and adds a third, deliberately inert
 * bucket for an up/down slide -- see [classifyGesture]. Three outcomes
 * per gesture, decided once at ACTION_UP from the ACTION_DOWN start
 * point:
 * - TAP (small total displacement): [setOnTapClick].
 * - HORIZONTAL_PUNCH (dominant, past-threshold horizontal movement,
 *   either direction -- left and right are treated identically, no
 *   punch-direction concept exists): [setOnPunchGesture].
 * - IGNORED_GESTURE (dominant, past-threshold *vertical* movement): it
 *   fires neither callback. This is what stops an up/down slide from
 *   ever being misread as a TAP (which would incorrectly place/judge a
 *   MARK) or as a punch.
 * Exactly one of these three outcomes per touch gesture, never two (see
 * [onTouchEvent]). The MARK -> ACTIVATE -> mark-clear and CAT_PUNCH game
 * logic itself still lives entirely in GameView/MarkController/
 * CaptureSystem and is untouched by this class; it only *reports* which
 * gesture happened and *displays* whichever MARK state
 * [ActionInputSource] tells it about via [setAwaitingActivate].
 */
class PawActionButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /**
         * Minimum travel (dp), on whichever axis is dominant, from
         * ACTION_DOWN before a release counts as a deliberate slide
         * (HORIZONTAL_PUNCH or IGNORED_GESTURE) rather than an ordinary
         * TAP -- see [classifyGesture]. Carried over unchanged from
         * CONTROL-SIMPLE-01's PUNCH_SLIDE_THRESHOLD_DP (still 20dp,
         * still deliberately smaller than [SwipeInputView]'s
         * SWIPE_THRESHOLD_DP of 26dp, which is tuned for a full-screen
         * move gesture -- this button is only ~130dp across, and the
         * spec asks for a light "shut" flick rather than a deliberate
         * full swipe). 20dp sits comfortably above ordinary tap jitter/
         * touch-slop (commonly ~8dp on Android) while staying well short
         * of the move-swipe threshold, so a real flick registers
         * reliably without the gesture feeling heavy. CONTROL-SIMPLE-02
         * starts real-device retuning from this same value rather than
         * guessing a new one, since the underlying "how far is a light
         * flick" answer shouldn't depend on which axis it's measured on.
         */
        private const val GESTURE_THRESHOLD_DP = 20f
    }

    private val gestureThresholdPx = GESTURE_THRESHOLD_DP * resources.displayMetrics.density

    /** The three possible outcomes of one ACTION paw touch gesture -- see
     * [classifyGesture]. */
    private enum class PawGesture { TAP, HORIZONTAL_PUNCH, IGNORED_GESTURE }

    private var onTapClick: (() -> Unit)? = null
    private var onPunchGesture: (() -> Unit)? = null
    fun setOnTapClick(listener: () -> Unit) {
        onTapClick = listener
    }
    fun setOnPunchGesture(listener: () -> Unit) {
        onPunchGesture = listener
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

    /** True while a finger is down and still over the button -- purely
     * cosmetic (drives the "pressed/sinks in" animation). This does not
     * gate whether ACTION_UP actually fires anything -- a real flick can
     * carry the finger past the button's own edge before release, and
     * gating on "still inside bounds" would silently swallow that
     * gesture. Which [PawGesture] fires (see [onTouchEvent]) is decided
     * purely from the ACTION_DOWN/ACTION_UP displacement. */
    private var pressed = false

    /** ACTION_DOWN's position -- the fixed reference point [onTouchEvent]
     * measures ACTION_UP's displacement from to classify the gesture (see
     * [classifyGesture]). */
    private var downX = 0f
    private var downY = 0f

    init {
        isClickable = true
        contentDescription = "ACTION"
    }

    /** CAT-PAW-IMAGE-TITLE-01: the provided "pad/palm side" paw photo --
     * loaded once, same `inScaled = false` convention as
     * [RotationalStickView]'s own `azusan_paw_back`/
     * [render.PlayerSpriteSheet]. Deliberately the *other* of the two
     * provided paw photos, so this button reads as clearly different
     * from the left stick's knob at a glance (pad-forward vs.
     * fur-from-above), per this round's own spec. */
    private val pawBitmap: Bitmap = BitmapFactory.decodeResource(
        context.resources, R.drawable.azusan_paw_pad, BitmapFactory.Options().apply { inScaled = false }
    )
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val reusableDst = RectF()

    /** This button's own drawn diameter in px -- anchored to
     * [RotationalStickView]'s own paw-knob size (same screen-width-
     * relative formula) times 1.125 (the 10-15% "larger" this round's
     * spec asks for, midpoint), so the two controls' relative sizing
     * stays correct on any screen without duplicating a separate magic
     * number here. Independent of this view's own (unchanged) touch
     * bounds -- see [onDraw]/class doc. */
    private val buttonPawSizePx =
        resources.displayMetrics.widthPixels *
            RotationalStickView.RING_DIAMETER_FRACTION_OF_SCREEN_WIDTH *
            RotationalStickView.PAW_SIZE_FRACTION_OF_RING_DIAMETER * 1.125f

    /** Soft outer glow, only drawn while awaiting ACTIVATE -- readable
     * at a glance even with a thumb covering the paw itself. Kept from
     * before CAT-PAW-IMAGE-TITLE-01 -- this is a functional MARK/
     * ACTIVATE cue, not the "circular medal/pedestal" look this round
     * asks to remove (the gold pad shape/outline/brand text below it). */
    private val glowRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 235, 150)
        style = Paint.Style.FILL
    }

    /** Bright highlight near the paw, also ACTIVATE-only -- a second,
     * more central glow cue in case the outer ring is occluded. */
    private val glowCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 250, 210)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        if (awaitingActivate) {
            canvas.drawCircle(cx, cy, buttonPawSizePx * 0.58f, glowRingPaint)
        }

        // "Muniっと押した感覚": the whole paw photo visibly sinks/shrinks
        // while held -- same pressScale mechanism/value as before
        // CAT-PAW-IMAGE-TITLE-01, just now applied to the bitmap draw
        // instead of the old Canvas paw shape.
        val pressScale = if (pressed) 0.88f else 1f
        canvas.save()
        canvas.scale(pressScale, pressScale, cx, cy)

        val aspect = pawBitmap.width.toFloat() / pawBitmap.height.toFloat()
        val dstW = if (aspect >= 1f) buttonPawSizePx else buttonPawSizePx * aspect
        val dstH = if (aspect >= 1f) buttonPawSizePx / aspect else buttonPawSizePx
        reusableDst.set(cx - dstW / 2f, cy - dstH / 2f, cx + dstW / 2f, cy + dstH / 2f)
        canvas.drawBitmap(pawBitmap, null, reusableDst, bitmapPaint)

        if (awaitingActivate) {
            canvas.drawCircle(cx, cy + dstH * 0.3f, dstH * 0.08f, glowCenterPaint)
        }

        canvas.restore()
    }

    /**
     * CONTROL-SIMPLE-02: classifies one gesture's ACTION_DOWN->ACTION_UP
     * displacement into exactly one of [PawGesture]'s three outcomes --
     * HORIZONTAL_PUNCH requires the horizontal component to both clear
     * [gestureThresholdPx] *and* dominate the vertical one; IGNORED_GESTURE
     * is the mirror image (vertical clears the threshold and is
     * dominant-or-tied); anything short of either threshold, on any axis,
     * is TAP. A tie at exactly equal |dx|/|dy| (both past threshold)
     * resolves to IGNORED_GESTURE rather than a punch -- deliberately the
     * safer default for an ambiguous diagonal-ish drag, since it fires
     * nothing rather than guessing.
     */
    private fun classifyGesture(dx: Float, dy: Float): PawGesture {
        val absDx = abs(dx)
        val absDy = abs(dy)
        return when {
            absDx >= gestureThresholdPx && absDx > absDy -> PawGesture.HORIZONTAL_PUNCH
            absDy >= gestureThresholdPx && absDy >= absDx -> PawGesture.IGNORED_GESTURE
            else -> PawGesture.TAP
        }
    }

    /**
     * ACTION_DOWN records the gesture's start point; ACTION_UP classifies
     * the displacement via [classifyGesture] and fires at most one of
     * [onTapClick]/[onPunchGesture] -- TAP fires the former, HORIZONTAL_
     * PUNCH the latter, and IGNORED_GESTURE fires neither. Exactly one
     * outcome per gesture, decided once -- never two, and an up/down
     * slide can never be misread as a TAP (which would otherwise
     * incorrectly place/judge a MARK).
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
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
                pressed = false
                invalidate()
                val dx = event.x - downX
                val dy = event.y - downY
                when (classifyGesture(dx, dy)) {
                    PawGesture.HORIZONTAL_PUNCH -> onPunchGesture?.invoke()
                    PawGesture.TAP -> onTapClick?.invoke()
                    PawGesture.IGNORED_GESTURE -> Unit
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
