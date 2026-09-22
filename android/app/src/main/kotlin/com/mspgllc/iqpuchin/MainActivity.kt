package com.mspgllc.iqpuchin

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import com.mspgllc.iqpuchin.input.ActionInputSource
import com.mspgllc.iqpuchin.input.DirectionalPadView
import com.mspgllc.iqpuchin.input.PawActionButtonView
import com.mspgllc.iqpuchin.input.SwipeInputView
import com.mspgllc.iqpuchin.input.VirtualStickView

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
}

/** SWIPE-TEST-01: which move-input control is actually wired up.
 * [VirtualStickView]/[SwipeInputView] are kept fully intact and
 * selectable here (never deleted) specifically so this can be flipped
 * back for comparison. CONTROL-SIMPLE-01 adds [DPAD] as a third option
 * -- a classic 4-direction cross pad, this build's new default per the
 * "don't make the player think about which control does what" goal --
 * without removing either prior mode. Neither GameView nor any
 * game-logic file reads this -- all three controls only ever reach the
 * game through the same InputActionListener.onMoveRequested call. */
private enum class MoveInputMode { SWIPE, VIRTUAL_STICK, DPAD }

class MainActivity : Activity() {

    private val moveInputMode = MoveInputMode.DPAD

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
        }

        setContentView(root)
    }
}
