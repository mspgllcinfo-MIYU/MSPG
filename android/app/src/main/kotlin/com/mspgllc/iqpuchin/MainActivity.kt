package com.mspgllc.iqpuchin

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import com.mspgllc.iqpuchin.input.ActionInputSource
import com.mspgllc.iqpuchin.input.DirectionalInputSource

/** Button sizing for the placeholder D-pad and the single ACTION button,
 * in dp so it reads the same physical size across Galaxy devices at
 * different densities. Adjustable independently of anything in
 * render/RenderConfig, which only concerns the board itself. */
private object UiConfig {
    const val BUTTON_SIZE_DP = 64
    const val DPAD_MARGIN_DP = 16
    const val ACTION_BUTTON_SIZE_DP = 130
    const val ACTION_BUTTON_MARGIN_DP = 16

    /** UI-POSITION: how far below dead-center (vertically) the D-pad and
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
        val buttonSizePx = (UiConfig.BUTTON_SIZE_DP * density).toInt()
        val dpadMarginPx = (UiConfig.DPAD_MARGIN_DP * density).toInt()
        val dpadSizePx = buttonSizePx * 3

        val upButton = directionButton("▲")
        val downButton = directionButton("▼")
        val leftButton = directionButton("◄")
        val rightButton = directionButton("►")

        val dpad = FrameLayout(this).apply {
            addView(upButton, FrameLayout.LayoutParams(buttonSizePx, buttonSizePx, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(downButton, FrameLayout.LayoutParams(buttonSizePx, buttonSizePx, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            addView(leftButton, FrameLayout.LayoutParams(buttonSizePx, buttonSizePx, Gravity.CENTER_VERTICAL or Gravity.START))
            addView(rightButton, FrameLayout.LayoutParams(buttonSizePx, buttonSizePx, Gravity.CENTER_VERTICAL or Gravity.END))
        }

        val actionButtonSizePx = (UiConfig.ACTION_BUTTON_SIZE_DP * density).toInt()
        val actionButtonMarginPx = (UiConfig.ACTION_BUTTON_MARGIN_DP * density).toInt()
        // STEP 7: MARK and ACTIVATE share this one button now -- its
        // label toggles between the two (see ActionInputSource) so the
        // pending action stays visible during testing. Starts on "MARK"
        // since no mark is pending at launch.
        val actionButton = Button(this).apply {
            text = "MARK"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(140, 110, 20))
            contentDescription = "ACTION"
        }

        // UI-POSITION: both controls moved from the bottom edge to
        // vertical-center-plus-a-bit-lower, level with each other, so
        // they sit in peripheral view of the board (around where PLAYER
        // starts, mid-board) instead of below it. Horizontal placement
        // (side margins, sizes) is unchanged from before this step.
        val uiVerticalOffsetPx = UiConfig.UI_VERTICAL_CENTER_OFFSET_DP * density

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(gameView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(
                dpad,
                FrameLayout.LayoutParams(dpadSizePx, dpadSizePx, Gravity.CENTER_VERTICAL or Gravity.START).apply {
                    leftMargin = dpadMarginPx
                }
            )
            addView(
                actionButton,
                FrameLayout.LayoutParams(actionButtonSizePx, actionButtonSizePx, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                    rightMargin = actionButtonMarginPx
                }
            )
        }
        dpad.translationY = uiVerticalOffsetPx
        actionButton.translationY = uiVerticalOffsetPx

        DirectionalInputSource(upButton, downButton, leftButton, rightButton).attach(gameView)
        ActionInputSource(actionButton).attach(gameView) { gameView.isAwaitingMark() }

        setContentView(root)
    }

    private fun directionButton(label: String): Button = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(40, 46, 56))
        contentDescription = label
    }
}
