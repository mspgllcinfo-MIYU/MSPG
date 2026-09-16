package com.mspgllc.iqpuchin

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import com.mspgllc.iqpuchin.input.ActivateInputSource
import com.mspgllc.iqpuchin.input.DirectionalInputSource
import com.mspgllc.iqpuchin.input.MarkInputSource

/** Button sizing for the placeholder D-pad and MARK/ACTIVATE buttons, in
 * dp so it reads the same physical size across Galaxy devices at
 * different densities. Adjustable independently of anything in
 * render/RenderConfig, which only concerns the board itself. */
private object UiConfig {
    const val BUTTON_SIZE_DP = 64
    const val DPAD_MARGIN_DP = 16
    const val ACTION_BUTTON_WIDTH_DP = 120
    const val ACTION_BUTTON_HEIGHT_DP = 64
    const val ACTION_BUTTON_MARGIN_DP = 16
    const val ACTION_BUTTON_GAP_DP = 12
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

        val actionButtonWidthPx = (UiConfig.ACTION_BUTTON_WIDTH_DP * density).toInt()
        val actionButtonHeightPx = (UiConfig.ACTION_BUTTON_HEIGHT_DP * density).toInt()
        val actionButtonMarginPx = (UiConfig.ACTION_BUTTON_MARGIN_DP * density).toInt()
        val actionButtonGapPx = (UiConfig.ACTION_BUTTON_GAP_DP * density).toInt()
        val markButton = Button(this).apply {
            text = "MARK"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(140, 110, 20))
            contentDescription = "MARK"
        }
        // Distinct color/button from MARK -- STEP 5 requires ACTIVATE to
        // be functionally and visually separate. Stacked directly above
        // MARK (same bottom-right corner) so both stay reachable with one
        // thumb without overlapping.
        val activateButton = Button(this).apply {
            text = "ACTIVATE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(150, 40, 40))
            contentDescription = "ACTIVATE"
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(gameView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(
                dpad,
                FrameLayout.LayoutParams(dpadSizePx, dpadSizePx, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dpadMarginPx
                    bottomMargin = dpadMarginPx
                }
            )
            addView(
                markButton,
                FrameLayout.LayoutParams(actionButtonWidthPx, actionButtonHeightPx, Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = actionButtonMarginPx
                    bottomMargin = actionButtonMarginPx + actionButtonHeightPx + actionButtonGapPx
                }
            )
            addView(
                activateButton,
                FrameLayout.LayoutParams(actionButtonWidthPx, actionButtonHeightPx, Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = actionButtonMarginPx
                    bottomMargin = actionButtonMarginPx
                }
            )
        }

        DirectionalInputSource(upButton, downButton, leftButton, rightButton).attach(gameView)
        MarkInputSource(markButton).attach(gameView)
        ActivateInputSource(activateButton).attach(gameView)

        setContentView(root)
    }

    private fun directionButton(label: String): Button = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(40, 46, 56))
        contentDescription = label
    }
}
