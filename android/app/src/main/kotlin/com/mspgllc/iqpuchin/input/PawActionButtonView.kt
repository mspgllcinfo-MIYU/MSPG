package com.mspgllc.iqpuchin.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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
 * CONTROL-SIMPLE-01: this view now distinguishes two gestures instead
 * of reporting every completed press as one generic click -- a plain
 * TAP (small total displacement) versus a deliberate downward slide
 * (see [PUNCH_SLIDE_THRESHOLD_DP]), reported via [setOnTapClick] and
 * [setOnPunchGesture] respectively. Exactly one of the two fires per
 * touch gesture, decided once at ACTION_UP from the ACTION_DOWN start
 * point -- never both (see [onTouchEvent]). This is what lets GameView
 * stop guessing intent from board state (a QUBE happening to be
 * adjacent no longer changes what a tap does): input method alone now
 * decides MARK/ACTIVATE (tap) versus CAT_PUNCH (slide). The MARK ->
 * ACTIVATE -> mark-clear and CAT_PUNCH game logic itself still lives
 * entirely in GameView/MarkController/CaptureSystem and is untouched by
 * this class; it only *reports* which gesture happened and *displays*
 * whichever MARK state [ActionInputSource] tells it about via
 * [setAwaitingActivate].
 */
class PawActionButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** UI-CONTROL-02: the brand mark stamped on the paw's main pad. */
        private const val BRAND_TEXT = "MIYU × AI"

        /**
         * CONTROL-SIMPLE-01: minimum downward travel (dp) from
         * ACTION_DOWN before a release counts as the CAT_PUNCH slide
         * gesture instead of an ordinary tap. Deliberately smaller than
         * [SwipeInputView]'s SWIPE_THRESHOLD_DP (26dp, tuned for a
         * full-screen move gesture) -- this button is only ~130dp
         * across, and the spec explicitly asks for a light "shut" flick
         * rather than a deliberate full swipe. 20dp sits comfortably
         * above ordinary tap jitter/touch-slop (commonly ~8dp on
         * Android) while staying well short of the move-swipe threshold,
         * so a real flick registers reliably without the gesture feeling
         * heavy.
         */
        private const val PUNCH_SLIDE_THRESHOLD_DP = 20f
    }

    private val punchSlideThresholdPx = PUNCH_SLIDE_THRESHOLD_DP * resources.displayMetrics.density

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
     * cosmetic (drives the "pressed/sinks in" animation). CONTROL-SIMPLE-01:
     * unlike before, this no longer gates whether ACTION_UP actually
     * fires anything -- a real downward punch flick will often carry the
     * finger past the button's own bottom edge before release, and
     * gating on "still inside bounds" would silently swallow that
     * gesture. Which of TAP/PUNCH_GESTURE fires (see [onTouchEvent]) is
     * now decided purely from the ACTION_DOWN/ACTION_UP displacement. */
    private var pressed = false

    /** ACTION_DOWN's position -- the fixed reference point [onTouchEvent]
     * measures ACTION_UP's displacement from to classify TAP vs the
     * downward CAT_PUNCH slide. */
    private var downX = 0f
    private var downY = 0f

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

    /** UI-CONTROL-02: "MIYU × AI" stamped on the main pad -- dark so it
     * reads against the gold pad without competing with it, auto-shrunk
     * (see [drawBrandText]) so it always fits regardless of the button's
     * actual on-screen size. */
    private val brandTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 22, 8)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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

        drawBrandText(canvas, cx, cy, baseRadius)

        canvas.restore()
    }

    /** Centers [BRAND_TEXT] on the main pad (see PawShape's own main-pad
     * geometry), shrinking it as needed to stay within the pad's width --
     * the toe beans above are left untouched, and the text never grows
     * the button itself since it's confined to a fraction of baseRadius. */
    private fun drawBrandText(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val textCy = cy + r * 0.35f
        val maxTextWidth = r * 0.95f
        var textSize = r * 0.20f
        brandTextPaint.textSize = textSize
        while (textSize > r * 0.08f && brandTextPaint.measureText(BRAND_TEXT) > maxTextWidth) {
            textSize -= 1f
            brandTextPaint.textSize = textSize
        }
        val fm = brandTextPaint.fontMetrics
        val baselineY = textCy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(BRAND_TEXT, cx, baselineY, brandTextPaint)
    }

    /**
     * CONTROL-SIMPLE-01: ACTION_DOWN records the gesture's start point;
     * ACTION_UP measures displacement from it and fires exactly one of
     * [onTapClick]/[onPunchGesture] -- never both, and never neither (a
     * completed touch that started here always resolves to one of the
     * two, matching the "TAPかPUNCHのどちらか1つだけ" requirement). A
     * downward slide is dy past [punchSlideThresholdPx] *and* dominantly
     * vertical (dy > abs(dx)), the same dominant-axis shape
     * [SwipeInputView]/[VirtualStickView] already use elsewhere in this
     * project; everything else -- including a small jitter, or a drag
     * that isn't dominantly downward -- resolves to TAP, per spec (only
     * these two buckets exist this round).
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
                if (dy >= punchSlideThresholdPx && dy > abs(dx)) {
                    onPunchGesture?.invoke()
                } else {
                    onTapClick?.invoke()
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
