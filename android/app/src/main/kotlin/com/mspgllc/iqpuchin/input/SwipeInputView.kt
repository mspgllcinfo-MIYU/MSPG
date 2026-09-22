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
 * touch feedback. A drag past [SWIPE_THRESHOLD_DP] from that gesture's
 * start point resolves to exactly one of the four cardinal directions by
 * dominant axis -- same tie-break rule as [VirtualStickView.updateKnob]
 * (horizontal wins only on strict inequality, else vertical; same
 * dx>0->EAST/dx<0->WEST/dy>0->SOUTH/dy<0->NORTH sign convention) so a
 * player switching between the two controls sees identical direction
 * behavior for the same drag -- and fires
 * [InputActionListener.onMoveRequested] exactly once, plus the same
 * single "kotsu" [StickHaptics] tick VirtualStickView fires on its own
 * direction lock. After that single fire, the rest of this same
 * finger-down gesture is inert -- no matter how far or which way the
 * finger keeps moving -- until it lifts and a new gesture begins. A tap
 * that never crosses the threshold produces no move at all.
 */
class SwipeInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        /** How far, in dp, a drag must travel from its gesture's start
         * point before a direction is resolved and fired -- deliberately
         * short ("短くスワイプ"), analogous in role to VirtualStickView's
         * DEADZONE_FRACTION but expressed as an absolute distance since
         * this view has no fixed base radius to take a fraction of. */
        const val SWIPE_THRESHOLD_DP = 26f

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

    /** True once this gesture (since the last ACTION_DOWN) has already
     * fired a move -- the single-fire-per-gesture latch that stops a
     * long continued drag from producing more than the one cell earned
     * by crossing the threshold once. Cleared only on ACTION_DOWN. */
    private var firedThisGesture = false

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
                firedThisGesture = false
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                if (!firedThisGesture) {
                    val dx = touchX - startX
                    val dy = touchY - startY
                    if (max(abs(dx), abs(dy)) >= thresholdPx) {
                        // Same dominant-axis resolution as VirtualStickView:
                        // horizontal wins only on a strict inequality, so an
                        // exact tie (or anything closer to vertical)
                        // resolves to up/down -- never diagonal either way.
                        val direction = if (abs(dx) > abs(dy)) {
                            if (dx > 0f) Direction.EAST else Direction.WEST
                        } else {
                            if (dy > 0f) Direction.SOUTH else Direction.NORTH
                        }
                        listener?.onMoveRequested(direction)
                        StickHaptics.tick(context)
                        firedThisGesture = true
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Releasing always ends the gesture -- the next touch-down
                // starts a fresh one, with its own fresh threshold check.
                pointerDown = false
                firedThisGesture = false
                invalidate()
            }
            else -> return false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pointerDown) {
            canvas.drawCircle(touchX, touchY, touchDotRadiusPx, touchDotPaint)
        }
    }
}
