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
import kotlin.math.max

/**
 * SWIPE-TEST-01: an alternative left-side move input, tried *alongside*
 * (not in place of) [VirtualStickView] -- IQ Puchin's movement is
 * already strictly 4-direction/1-input-per-cell digital movement (see
 * [InputActionListener.onMoveRequested]), so this tests whether a
 * discrete swipe-per-cell gesture feels more natural than dragging an
 * analog-looking stick for the same discrete output. Which of the two
 * is actually wired up is decided entirely in MainActivity; neither
 * class knows the other exists, and GameView/BoardLogic/Direction are
 * completely unaware of which is in use -- both ultimately only ever
 * call [InputActionListener.onMoveRequested] with one discrete
 * [Direction], so this is purely an input-method swap, not a game-logic
 * change.
 *
 * Touch anywhere inside this view's bounds (MainActivity sizes/positions
 * it as a fixed left-side zone, kept clear of the ACTION button's own
 * bounds on the right -- see MainActivity's swipe-zone sizing) starts a
 * gesture; no fixed-position stick graphic is drawn (per spec), just a
 * small dot at the current touch point while a finger is down, purely as
 * touch feedback.
 *
 * SWIPE-FORGIVING-TEST-01: direction is judged only once, at
 * ACTION_UP/ACTION_CANCEL, from the gesture's start point to wherever the
 * finger actually lifted -- never mid-drag. Real-device feedback was
 * that a thumb swiping "up" naturally arcs sideways before straightening
 * out, so judging the instant a threshold is first crossed (the old
 * behavior) could lock in a direction before the finger ever reached
 * where the player actually meant to go; waiting for release and reading
 * the whole start->final vector lets the gesture finish before anything
 * is decided. See [resolveDirection] for the actual classification (a
 * dominant-axis test, deliberately given extra horizontal tolerance for
 * NORTH specifically). This still fires
 * [InputActionListener.onMoveRequested] at most once per finger-down
 * gesture (never more, regardless of how far or which way the finger
 * traveled first) and only when the release point is at least
 * [SWIPE_THRESHOLD_DP] from the start -- short of that, a tap or small
 * jitter, it's a no-op. The same single "kotsu" [StickHaptics] tick
 * VirtualStickView fires on its own direction lock now fires at the
 * instant a move is actually confirmed, never while still dragging.
 */
class SwipeInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        /** How far, in dp, the release point must be from the gesture's
         * start point before any direction is resolved at all -- below
         * this, ACTION_UP is a no-op (a tap or small jitter). Unchanged
         * from SWIPE-TEST-01's value. */
        const val SWIPE_THRESHOLD_DP = 26f

        /**
         * SWIPE-FORGIVING-TEST-01: how much more horizontal drift NORTH
         * tolerates versus the plain dominant-axis test SOUTH/EAST/WEST
         * still use. A drag classifies as NORTH whenever
         * `abs(dy) * NORTH_FORGIVENESS_RATIO >= abs(dx)` (and dy is
         * upward) -- at 1.0 that's the ordinary +/-45 degree cone every
         * other direction gets; 1.4 widens NORTH's cone to roughly
         * +/-54.5 degrees off straight-up (atan(1.4)). Deliberately
         * NORTH-only and a moderate widening, not applied to SOUTH/EAST/
         * WEST and not pushed further than this -- per the explicit
         * "don't overcorrect" instruction, this reflects a real (but not
         * extreme) allowance for how a thumb's natural upward arc drifts
         * sideways, without meaningfully eating into EAST/WEST's own
         * territory for gestures that aren't primarily upward at all
         * (this ratio only ever applies when dy is already negative).
         */
        const val NORTH_FORGIVENESS_RATIO = 1.4f

        const val TOUCH_DOT_RADIUS_DP = 22f
    }

    private val density = resources.displayMetrics.density
    private val thresholdPx = SWIPE_THRESHOLD_DP * density
    private val touchDotRadiusPx = TOUCH_DOT_RADIUS_DP * density

    private var listener: InputActionListener? = null

    private var startX = 0f
    private var startY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var pointerDown = false

    private val touchDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 205, 60)
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        contentDescription = "SWIPE"
    }

    /** Mirrors VirtualStickView/ActionInputSource's own attach(listener) pattern. */
    fun attach(listener: InputActionListener) {
        this.listener = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                touchX = event.x
                touchY = event.y
                pointerDown = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Touch-point feedback only -- no direction is resolved
                // or fired here anymore; see ACTION_UP.
                touchX = event.x
                touchY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (max(abs(dx), abs(dy)) >= thresholdPx) {
                    val direction = resolveDirection(dx, dy)
                    listener?.onMoveRequested(direction)
                    // Fires exactly here -- the instant (and only
                    // instant) a move is actually confirmed, never
                    // mid-drag.
                    StickHaptics.tick(context)
                }
                pointerDown = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                // A cancelled gesture never resolves a direction, no
                // matter how far it had already moved.
                pointerDown = false
                invalidate()
            }
            else -> return false
        }
        return true
    }

    /**
     * Start->final dominant-axis classification, with NORTH given a
     * wider cone than the other three (see [NORTH_FORGIVENESS_RATIO]).
     * Always resolves to exactly one of the four cardinal directions --
     * never diagonal -- since the two branches below are a clean,
     * gap-free partition of every (dx, dy) that isn't (0, 0).
     */
    private fun resolveDirection(dx: Float, dy: Float): Direction {
        val absDx = abs(dx)
        val absDy = abs(dy)
        return if (dy < 0f) {
            // Upward: the widened NORTH cone.
            if (absDy * NORTH_FORGIVENESS_RATIO >= absDx) {
                Direction.NORTH
            } else if (dx > 0f) Direction.EAST else Direction.WEST
        } else {
            // Downward (or perfectly horizontal, dy == 0): the ordinary
            // dominant-axis cone, same as SWIPE-TEST-01/VirtualStickView.
            if (absDy >= absDx) {
                Direction.SOUTH
            } else if (dx > 0f) Direction.EAST else Direction.WEST
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pointerDown) {
            canvas.drawCircle(touchX, touchY, touchDotRadiusPx, touchDotPaint)
        }
    }
}
