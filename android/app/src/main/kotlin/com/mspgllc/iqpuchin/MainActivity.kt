package com.mspgllc.iqpuchin

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import com.mspgllc.iqpuchin.input.ActionInputSource
import com.mspgllc.iqpuchin.input.PawActionButtonView
import com.mspgllc.iqpuchin.input.VirtualStickView

/** Sizing for the virtual stick and the single ACTION button, in dp so
 * it reads the same physical size across Galaxy devices at different
 * densities. Adjustable independently of anything in render/RenderConfig,
 * which only concerns the board itself. */
private object UiConfig {
    const val STICK_SIZE_DP = 160
    const val STICK_MARGIN_DP = 16
    const val ACTION_BUTTON_SIZE_DP = 130
    const val ACTION_BUTTON_MARGIN_DP = 16

    /** UI-POSITION: how far below dead-center (vertically) the stick and
     * ACTION button sit, in dp. Both use the same value so their centers
     * land at the same height on opposite sides of the screen -- roughly
     * where PLAYER starts (BoardConfig.GRID_DEPTH / 2, i.e. mid-board) to
     * a bit below it, within peripheral view of the board while playing. */
    const val UI_VERTICAL_CENTER_OFFSET_DP = 60
}

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gameView = GameView(this)

        val density = resources.displayMetrics.density

        // VIRTUAL-STICK-01: replaces the old 4-button cross D-pad. Its
        // own knob position is touch/visual feedback only -- internally
        // it still calls InputActionListener.onMoveRequested with the
        // same discrete NORTH/SOUTH/EAST/WEST values as before (see
        // VirtualStickView's class doc for the deadzone/direction-
        // resolution rules), so PLAYER movement itself is unchanged.
        val stickSizePx = (UiConfig.STICK_SIZE_DP * density).toInt()
        val stickMarginPx = (UiConfig.STICK_MARGIN_DP * density).toInt()
        val virtualStick = VirtualStickView(this)

        val actionButtonSizePx = (UiConfig.ACTION_BUTTON_SIZE_DP * density).toInt()
        val actionButtonMarginPx = (UiConfig.ACTION_BUTTON_MARGIN_DP * density).toInt()
        // STEP 7: MARK and ACTIVATE share this one button; UI-CUTE-01
        // replaced the old rectangular label Button with a paw-shaped
        // PawActionButtonView -- same position/size/touch area, same
        // MARK->ACTIVATE->clear logic below, just a gold paw (glowing
        // once a MARK is pending) instead of text (see ActionInputSource).
        val actionButton = PawActionButtonView(this)

        // UI-POSITION: both controls sit at vertical-center-plus-a-bit-
        // lower, level with each other, so they sit in peripheral view of
        // the board (around where PLAYER starts, mid-board) instead of
        // below it.
        val uiVerticalOffsetPx = UiConfig.UI_VERTICAL_CENTER_OFFSET_DP * density

        // UI-SWAP-03: virtual stick (continuous movement) is now on the
        // left; ACTION (one-shot MARK/ACTIVATE) is now on the right --
        // swapped back from the previous round per updated real-device
        // feedback. Only Gravity/margin sides change below; sizes, the
        // shared vertical offset, and both views' own drawing/touch/
        // haptic logic are untouched.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(gameView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(
                virtualStick,
                FrameLayout.LayoutParams(stickSizePx, stickSizePx, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                    leftMargin = stickMarginPx
                }
            )
            addView(
                actionButton,
                FrameLayout.LayoutParams(actionButtonSizePx, actionButtonSizePx, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                    rightMargin = actionButtonMarginPx
                }
            )
        }
        virtualStick.translationY = uiVerticalOffsetPx
        actionButton.translationY = uiVerticalOffsetPx

        virtualStick.attach(gameView)
        ActionInputSource(actionButton).attach(gameView) { gameView.isAwaitingMark() }

        setContentView(root)
    }
}
