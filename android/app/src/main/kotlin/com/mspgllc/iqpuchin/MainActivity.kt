package com.mspgllc.iqpuchin

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import com.mspgllc.iqpuchin.input.ActionInputSource
import com.mspgllc.iqpuchin.input.DirectionalPadView
import com.mspgllc.iqpuchin.input.PawActionButtonView
import com.mspgllc.iqpuchin.input.RotationalStickView
import com.mspgllc.iqpuchin.input.SwipeInputView
import com.mspgllc.iqpuchin.input.VirtualStickView
import kotlin.math.min

/** Sizing for the move-input controls and the single ACTION button, in dp
 * so it reads the same physical size across Galaxy devices at different
 * densities. Adjustable independently of anything in render/RenderConfig,
 * which only concerns the board itself. */
private object UiConfig {
    const val STICK_SIZE_DP = 160
    const val STICK_MARGIN_DP = 16
    const val ACTION_BUTTON_SIZE_DP = 130
    const val ACTION_BUTTON_MARGIN_DP = 16

    /** SWIPE-TEST-01: the swipe-input zone's width, spanning the left
     * side of the screen from x=0 -- deliberately a fixed dp value (this
     * project's existing sizing convention for every other control)
     * rather than a screen-width fraction, chosen conservatively so it
     * never reaches the ACTION button's own touch bounds even on the
     * narrowest common Galaxy width (~360dp): ACTION_BUTTON_SIZE_DP +
     * 2 * ACTION_BUTTON_MARGIN_DP = 162dp reserved on the right, leaving
     * at least a ~8dp dead gap between the two zones at 360dp width, and
     * more on any wider screen. */
    const val SWIPE_ZONE_WIDTH_DP = 190

    /** UI-POSITION: how far below dead-center (vertically) the ACTION
     * button (and, when active, the virtual stick) sits, in dp -- roughly
     * where PLAYER starts (BoardConfig.GRID_DEPTH / 2, i.e. mid-board) to
     * a bit below it, within peripheral view of the board while playing.
     * SWIPE_ZONE_WIDTH_DP intentionally does NOT use this offset -- its
     * whole point is "anywhere on the left side", full height. */
    const val UI_VERTICAL_CENTER_OFFSET_DP = 60

    /**
     * TITLE-SCREEN-FINAL-01: the final title art (title_qube_zero.png)
     * has its own "START" button design baked into the image itself --
     * these four fractions mark that design's own bounding box, measured
     * directly against the source PNG's pixel coordinates (1672x941):
     * the button's pill shape spans x=[532,1134] (0.318-0.678 of width)
     * and y=[775,889] (0.824-0.945 of height), padded outward here
     * (per this round's own "slightly larger than the drawn button"
     * instruction) to a comfortable tap target. Fractions of the
     * *displayed image area* (see isTouchOnTitleStart), not of the
     * screen -- so this stays correct however the ImageView's own
     * FIT_CENTER letterboxing lands on a given device's aspect ratio.
     */
    const val TITLE_START_LEFT_FRACTION = 0.30f
    const val TITLE_START_RIGHT_FRACTION = 0.70f
    const val TITLE_START_TOP_FRACTION = 0.79f
    const val TITLE_START_BOTTOM_FRACTION = 0.975f
}

/**
 * TITLE-SCREEN-FINAL-01: true iff ([touchX], [touchY]), in [view]'s own
 * local pixel coordinates, lands within the title image's own drawn
 * START button -- computed from [view]'s *actual displayed* image rect
 * under FIT_CENTER (which letterboxes around the image rather than
 * stretching it), not from the view's raw bounds, so this stays
 * correctly aligned with the art on any screen aspect ratio. Returns
 * false (never crashes) if the view has no measured size or drawable
 * yet, or if the touch falls in a letterbox bar outside the image.
 */
private fun isTouchOnTitleStart(view: ImageView, touchX: Float, touchY: Float): Boolean {
    val drawable = view.drawable ?: return false
    val bitmapWidth = drawable.intrinsicWidth.toFloat()
    val bitmapHeight = drawable.intrinsicHeight.toFloat()
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()
    if (bitmapWidth <= 0f || bitmapHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return false

    val scale = min(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
    val displayedWidth = bitmapWidth * scale
    val displayedHeight = bitmapHeight * scale
    val offsetX = (viewWidth - displayedWidth) / 2f
    val offsetY = (viewHeight - displayedHeight) / 2f

    if (touchX < offsetX || touchX > offsetX + displayedWidth ||
        touchY < offsetY || touchY > offsetY + displayedHeight
    ) {
        return false
    }

    val fracX = (touchX - offsetX) / displayedWidth
    val fracY = (touchY - offsetY) / displayedHeight
    return fracX in UiConfig.TITLE_START_LEFT_FRACTION..UiConfig.TITLE_START_RIGHT_FRACTION &&
        fracY in UiConfig.TITLE_START_TOP_FRACTION..UiConfig.TITLE_START_BOTTOM_FRACTION
}

/** SWIPE-TEST-01: which move-input control is actually wired up.
 * [VirtualStickView]/[SwipeInputView]/[DirectionalPadView] are kept
 * fully intact and selectable here (never deleted) specifically so this
 * can be flipped back for comparison. CONTROL-SIMPLE-01 added [DPAD] (a
 * classic 4-direction cross pad); real-device feedback preferred the
 * feel of a free-anywhere swipe gesture over needing to land a touch on
 * a fixed arm, so CONTROL-SIMPLE-02 moves the default back to [SWIPE]
 * without deleting DPAD's code or this enum entry -- it stays fully
 * selectable for future comparison. SWIPE/VIRTUAL_STICK/DPAD only ever
 * reach the game through InputActionListener.onMoveRequested, unchanged
 * this round. VIRTUAL-STICK-ROTATIONAL-PROTOTYPE-01 adds
 * [ROTATIONAL_STICK] (a 360-degree analog stick, [RotationalStickView])
 * as this build's own active default, purely to evaluate real-device
 * feel -- it reaches the game through the separate RotationalMoveListener
 * interface instead, so it can be flipped back to [SWIPE] by changing
 * only the one line below, with zero risk to the other three modes'
 * code or behavior. */
private enum class MoveInputMode { SWIPE, VIRTUAL_STICK, DPAD, ROTATIONAL_STICK }

class MainActivity : Activity() {

    private val moveInputMode = MoveInputMode.ROTATIONAL_STICK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gameView = GameView(this)

        val density = resources.displayMetrics.density

        val actionButtonSizePx = (UiConfig.ACTION_BUTTON_SIZE_DP * density).toInt()
        val actionButtonMarginPx = (UiConfig.ACTION_BUTTON_MARGIN_DP * density).toInt()
        // STEP 7: MARK and ACTIVATE share this one button; UI-CUTE-01
        // replaced the old rectangular label Button with a paw-shaped
        // PawActionButtonView -- same position/size/touch area, same
        // MARK->ACTIVATE->clear logic below, just a gold paw (glowing
        // once a MARK is pending) instead of text (see ActionInputSource).
        val actionButton = PawActionButtonView(this)

        // UI-POSITION: the ACTION button (and the virtual stick, when
        // that's the active mode) sits at vertical-center-plus-a-bit-
        // lower, so it's in peripheral view of the board (around where
        // PLAYER starts, mid-board) instead of below it.
        val uiVerticalOffsetPx = UiConfig.UI_VERTICAL_CENTER_OFFSET_DP * density

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(gameView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(
                actionButton,
                FrameLayout.LayoutParams(actionButtonSizePx, actionButtonSizePx, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                    rightMargin = actionButtonMarginPx
                }
            )
        }
        actionButton.translationY = uiVerticalOffsetPx
        ActionInputSource(actionButton).attach(gameView) { gameView.isAwaitingMark() }

        // SWIPE-TEST-01: exactly one of the two move-input controls is
        // ever added to `root` -- the other's class is untouched and
        // simply unused this build, so restoring it later is a one-line
        // mode flip, not a re-implementation.
        when (moveInputMode) {
            MoveInputMode.SWIPE -> {
                val swipeZoneWidthPx = (UiConfig.SWIPE_ZONE_WIDTH_DP * density).toInt()
                val swipeInput = SwipeInputView(this)
                root.addView(
                    swipeInput,
                    FrameLayout.LayoutParams(swipeZoneWidthPx, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
                )
                swipeInput.attach(gameView)
            }
            MoveInputMode.VIRTUAL_STICK -> {
                // VIRTUAL-STICK-01: its own knob position is touch/visual
                // feedback only -- internally it still calls
                // InputActionListener.onMoveRequested with the same
                // discrete NORTH/SOUTH/EAST/WEST values as before (see
                // VirtualStickView's class doc), so PLAYER movement
                // itself is unchanged versus this build's SWIPE default.
                val stickSizePx = (UiConfig.STICK_SIZE_DP * density).toInt()
                val stickMarginPx = (UiConfig.STICK_MARGIN_DP * density).toInt()
                val virtualStick = VirtualStickView(this)
                root.addView(
                    virtualStick,
                    FrameLayout.LayoutParams(stickSizePx, stickSizePx, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                        leftMargin = stickMarginPx
                    }
                )
                virtualStick.translationY = uiVerticalOffsetPx
                virtualStick.attach(gameView)
            }
            MoveInputMode.DPAD -> {
                // CONTROL-SIMPLE-01: same left-side placement/sizing
                // convention as VIRTUAL_STICK above (reusing
                // STICK_SIZE_DP/STICK_MARGIN_DP -- this is a like-for-like
                // replacement slot for whichever one control is active,
                // not a new UI region). DirectionalPadView resolves and
                // fires a direction itself; GameView only ever sees the
                // same onMoveRequested(Direction) call the other two
                // controls already produce.
                val dpadSizePx = (UiConfig.STICK_SIZE_DP * density).toInt()
                val dpadMarginPx = (UiConfig.STICK_MARGIN_DP * density).toInt()
                val dpad = DirectionalPadView(this)
                root.addView(
                    dpad,
                    FrameLayout.LayoutParams(dpadSizePx, dpadSizePx, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                        leftMargin = dpadMarginPx
                    }
                )
                dpad.translationY = uiVerticalOffsetPx
                dpad.attach(gameView)
            }
            MoveInputMode.ROTATIONAL_STICK -> {
                // VIRTUAL-STICK-ROTATIONAL-PROTOTYPE-01: same left-side
                // placement/sizing convention as VIRTUAL_STICK/DPAD above.
                // RotationalStickView reaches the game through the
                // separate RotationalMoveListener interface (attach()
                // below), never InputActionListener -- gameView already
                // implements both.
                val stickSizePx = (UiConfig.STICK_SIZE_DP * density).toInt()
                val stickMarginPx = (UiConfig.STICK_MARGIN_DP * density).toInt()
                val rotationalStick = RotationalStickView(this)
                root.addView(
                    rotationalStick,
                    FrameLayout.LayoutParams(stickSizePx, stickSizePx, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                        leftMargin = stickMarginPx
                    }
                )
                rotationalStick.translationY = uiVerticalOffsetPx
                rotationalStick.attach(gameView)
            }
        }

        // CAT-PAW-IMAGE-TITLE-01: added last, so it's the topmost view in
        // `root` -- it fully covers and consumes every touch over every
        // control added above, so a touch that misses the title image's
        // own drawn START button is simply absorbed here rather than
        // falling through to RotationalStickView/PawActionButtonView/
        // GameView underneath. GameView's own [GameView.titleActive]
        // (frozen from construction) is the actual gameplay-side freeze;
        // this overlay is the second, independent layer that stops a
        // touch on the title screen from ever reaching a control
        // underneath. Removed entirely, once, the instant START is
        // tapped -- never re-added, so this is strictly a first-launch-
        // only screen (see GameView.beginPlay's own doc).
        //
        // TITLE-SCREEN-FINAL-01: the final title art has its own "START"
        // button fully drawn into the image (see UiConfig's doc on the
        // measured fraction constants) -- no separate Button widget is
        // drawn on top of it any more. Instead, titleImage's own
        // OnTouchListener below hit-tests ACTION_UP against that drawn
        // button's own position (via isTouchOnTitleStart, which accounts
        // for FIT_CENTER's letterboxing), so the transparent tap region
        // draws nothing of its own (no background/text/border/standard
        // Button chrome) and stays visually aligned with the art on any
        // screen aspect ratio. It still returns true unconditionally
        // (matching isClickable's prior touch-absorbing role exactly),
        // so a miss anywhere else on the title screen is still swallowed
        // rather than leaking through.
        val titleImage = ImageView(this).apply {
            setImageResource(R.drawable.title_qube_zero)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
        }
        val titleOverlay = FrameLayout(this).apply {
            isClickable = true
            setBackgroundColor(Color.BLACK)
            addView(titleImage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        titleImage.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP &&
                isTouchOnTitleStart(view as ImageView, event.x, event.y)
            ) {
                root.removeView(titleOverlay)
                gameView.beginPlay()
            }
            true
        }
        root.addView(titleOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setContentView(root)
    }
}
