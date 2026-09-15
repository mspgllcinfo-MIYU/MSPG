package com.mspgllc.iqpuchin.render

/**
 * Every display-only tunable in one place. None of these affect game
 * rules (there are none yet) -- they exist purely so board size,
 * position, and player size can be retuned after real-device review
 * without touching board/input logic.
 */
object RenderConfig {
    /** Baseline (unscaled) tile footprint; a 2:1 width:height ratio is
     * what makes the projection read as isometric. GameView multiplies
     * these by a per-frame [displayScale] it computes to fill the screen. */
    const val TILE_WIDTH_PX = 130f
    const val TILE_HEIGHT_PX = 65f

    /** Baseline (unscaled) player marker diameter. */
    const val PLAYER_SIZE_PX = 56f

    /** Fraction of view height reserved above the board -- kept small on
     * purpose so there's no large empty band at the top of the screen. */
    const val TOP_MARGIN_FRACTION = 0.02f

    /** Fraction of view height reserved below the board. */
    const val BOTTOM_MARGIN_FRACTION = 0.02f

    /** Fraction of view width reserved on each side of the board. */
    const val SIDE_MARGIN_FRACTION = 0.03f
}
