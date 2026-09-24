package com.mspgllc.iqpuchin.input

import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
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

        // CAT-PAW-FEEL-03: purely visual press-feedback tuning -- none of
        // these are read by onTouchEvent/classifyGesture, which still
        // only ever use raw event.x/event.y and the untouched
        // GESTURE_THRESHOLD_DP above. "ムニョッ -> ミューン -> プルンッ":
        // a quick squash on ACTION_DOWN, then a 4-keyframe elastic
        // recovery on release, all applied only inside onDraw's own
        // canvas transform (see drawScaleX/drawScaleY/drawTranslateYPx) --
        // never via real View.scaleX/scaleY/translationY properties,
        // which Android's own touch dispatch would otherwise remap
        // ACTION_DOWN/ACTION_UP coordinates through.
        private const val SQUASH_DURATION_MS = 70L
        private const val SQUASH_SCALE_X = 1.15f
        private const val SQUASH_SCALE_Y = 0.75f
        private const val SQUASH_SINK_DP = 5f

        private const val RECOVER_DURATION_MS = 220L
        // "1 ミューン" -- overshoots past 1.0 the other way from the
        // squash (wider->taller) before settling.
        private const val RECOVER_KF1_FRACTION = 0.35f
        private const val RECOVER_KF1_SCALE_X = 0.94f
        private const val RECOVER_KF1_SCALE_Y = 1.12f
        // "2 プルン" -- a second, smaller overshoot back the other way.
        private const val RECOVER_KF2_FRACTION = 0.60f
        private const val RECOVER_KF2_SCALE_X = 1.05f
        private const val RECOVER_KF2_SCALE_Y = 0.97f
        // "3 小さな揺り戻し" -- a barely-there final wobble.
        private const val RECOVER_KF3_FRACTION = 0.82f
        private const val RECOVER_KF3_SCALE_X = 0.98f
        private const val RECOVER_KF3_SCALE_Y = 1.02f
        // "4 REST" -- scaleX=scaleY=1, translateY=0 -- the animator's own
        // final keyframe (fraction 1f) below, not a separate constant.
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

    private val density = resources.displayMetrics.density

    // CAT-PAW-FEEL-03: the paw's current drawn squash/stretch/sink,
    // applied only inside onDraw's own canvas.scale/translate (never a
    // real View property) -- see the companion constants' own doc for
    // why. [onDraw] reads these every frame it's invalidated; nothing
    // else in this class reads them.
    private var drawScaleX = 1f
    private var drawScaleY = 1f
    private var drawTranslateYPx = 0f
    private var pressAnimator: ValueAnimator? = null

    /** ACTION_DOWN, or a MOVE that re-enters the button's own bounds
     * while still down -- animates [drawScaleX]/[drawScaleY]/
     * [drawTranslateYPx] from wherever they currently are to the "ムニョ"
     * squash target over [SQUASH_DURATION_MS], never touching touch
     * coordinates/[pressed]/[downX]/[downY] at all. */
    private fun startSquashAnimation() {
        pressAnimator?.cancel()
        val fromScaleX = drawScaleX
        val fromScaleY = drawScaleY
        val fromTranslate = drawTranslateYPx
        val sinkPx = SQUASH_SINK_DP * density
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SQUASH_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                drawScaleX = fromScaleX + (SQUASH_SCALE_X - fromScaleX) * t
                drawScaleY = fromScaleY + (SQUASH_SCALE_Y - fromScaleY) * t
                drawTranslateYPx = fromTranslate + (sinkPx - fromTranslate) * t
                invalidate()
            }
        }
        pressAnimator = animator
        animator.start()
    }

    /** ACTION_UP/ACTION_CANCEL, or a MOVE that leaves the button's own
     * bounds while still down -- plays the four-stage "ミューン -> プルン
     * -> 小さな揺り戻し -> REST" elastic recovery over
     * [RECOVER_DURATION_MS], starting from wherever [drawScaleX]/
     * [drawScaleY]/[drawTranslateYPx] currently are (so a release mid-
     * squash still recovers smoothly, never popping). Purely visual --
     * [onTouchEvent] fires [onPunchGesture]/[onTapClick] synchronously,
     * independent of this animator, so gameplay response is never
     * delayed by it. */
    private fun startRecoverAnimation() {
        pressAnimator?.cancel()
        val startScaleX = drawScaleX
        val startScaleY = drawScaleY
        val startTranslate = drawTranslateYPx

        val scaleXHolder = PropertyValuesHolder.ofKeyframe(
            "scaleX",
            Keyframe.ofFloat(0f, startScaleX),
            Keyframe.ofFloat(RECOVER_KF1_FRACTION, RECOVER_KF1_SCALE_X),
            Keyframe.ofFloat(RECOVER_KF2_FRACTION, RECOVER_KF2_SCALE_X),
            Keyframe.ofFloat(RECOVER_KF3_FRACTION, RECOVER_KF3_SCALE_X),
            Keyframe.ofFloat(1f, 1f)
        )
        val scaleYHolder = PropertyValuesHolder.ofKeyframe(
            "scaleY",
            Keyframe.ofFloat(0f, startScaleY),
            Keyframe.ofFloat(RECOVER_KF1_FRACTION, RECOVER_KF1_SCALE_Y),
            Keyframe.ofFloat(RECOVER_KF2_FRACTION, RECOVER_KF2_SCALE_Y),
            Keyframe.ofFloat(RECOVER_KF3_FRACTION, RECOVER_KF3_SCALE_Y),
            Keyframe.ofFloat(1f, 1f)
        )
        // translateY has no explicit per-stage numbers in this round's
        // spec (only "REST: translationY=0") -- eases back across the
        // same keyframe timeline, with a small upward overshoot around
        // the "プルン" stage for a touch of bounce, proportional to
        // whatever sink distance it's actually recovering from (0 if
        // released without ever squashing).
        val translateHolder = PropertyValuesHolder.ofKeyframe(
            "translateY",
            Keyframe.ofFloat(0f, startTranslate),
            Keyframe.ofFloat(RECOVER_KF1_FRACTION, startTranslate * 0.3f),
            Keyframe.ofFloat(RECOVER_KF2_FRACTION, -abs(startTranslate) * 0.12f),
            Keyframe.ofFloat(RECOVER_KF3_FRACTION, startTranslate * 0.06f),
            Keyframe.ofFloat(1f, 0f)
        )
        val animator = ValueAnimator.ofPropertyValuesHolder(scaleXHolder, scaleYHolder, translateHolder).apply {
            duration = RECOVER_DURATION_MS
            // The keyframes above already encode the overshoot/settle
            // shape; a linear interpolator between them keeps that shape
            // exact rather than doubling up on easing.
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                drawScaleX = anim.getAnimatedValue("scaleX") as Float
                drawScaleY = anim.getAnimatedValue("scaleY") as Float
                drawTranslateYPx = anim.getAnimatedValue("translateY") as Float
                invalidate()
            }
        }
        pressAnimator = animator
        animator.start()
    }

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
     * relative formula, now enlarged per CAT-PAW-UI-FIX-02's own
     * [RotationalStickView.PAW_SIZE_FRACTION_OF_RING_DIAMETER] retune)
     * times 1.075 (CAT-PAW-UI-FIX-02's own 5-10% "larger" ask, midpoint
     * -- was 1.125 under CAT-PAW-IMAGE-TITLE-01's looser 10-15%), so the
     * two controls' relative sizing stays correct on any screen without
     * duplicating a separate magic number here. Independent of this
     * view's own (unchanged) touch bounds -- see [onDraw]/class doc. */
    private val buttonPawSizePx =
        resources.displayMetrics.widthPixels *
            RotationalStickView.RING_DIAMETER_FRACTION_OF_SCREEN_WIDTH *
            RotationalStickView.PAW_SIZE_FRACTION_OF_RING_DIAMETER * 1.075f

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

        // CAT-PAW-FEEL-03: "ムニョッ -> ミューン -> プルンッ" -- drawScaleX/
        // drawScaleY/drawTranslateYPx are animated by startSquashAnimation/
        // startRecoverAnimation (see onTouchEvent), never a flat step
        // value. translate happens first so the already-squashed paw
        // shifts down as a whole; scale pivots on the view's own static
        // center (cx, cy), never the touch point.
        canvas.save()
        canvas.translate(0f, drawTranslateYPx)
        canvas.scale(drawScaleX, drawScaleY, cx, cy)

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
                startSquashAnimation()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // CAT-PAW-FEEL-03: still the exact same in/out-of-bounds
                // check as before, over the same untouched view width/
                // height -- only what it now triggers (an animation
                // instead of a flat pressScale flip) changed. Never reads
                // dx/dy or touches downX/downY/gesture classification.
                val inside = event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height
                if (pressed != inside) {
                    pressed = inside
                    if (pressed) startSquashAnimation() else startRecoverAnimation()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressed = false
                startRecoverAnimation()
                // CAT-PAW-FEEL-03: dx/dy and classifyGesture below are
                // byte-for-byte the same computation as before this round
                // -- the recovery animation above only ever writes
                // drawScaleX/drawScaleY/drawTranslateYPx (onDraw-only
                // state), so it can never delay or alter this gesture
                // outcome, which still fires synchronously right here.
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
                startRecoverAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
