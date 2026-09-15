package com.mspgllc.iqpuchin.render

import kotlin.math.sqrt

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

    /**
     * Baseline screen-px per world-height unit (1.0 = the QUBE's own full
     * resting height), used only for the QUBE's toppling motion -- the
     * floor tiles and player marker never use height.
     *
     * Derived, not tuned by feel: TILE_WIDTH_PX/TILE_HEIGHT_PX already
     * define how a 1-world-unit edge along the ground (X or Z) projects
     * to screen pixels -- that projected length is sqrt(halfW^2 + halfH^2)
     * (Pythagorean, since the projection mixes both axes). For a QUBE
     * that is genuinely 1x1x1 in world space (see QubeRenderer -- its
     * half-extent is a fixed 0.5 in every direction) to actually look
     * like a cube rather than a slab, its vertical (Y) edge needs to
     * project to that *same* screen length. That equality is exactly
     * what this formula guarantees; it is intentionally a computed
     * property (not a constant) so it can never drift out of sync with
     * TILE_WIDTH_PX/TILE_HEIGHT_PX if those are ever retuned.
     */
    val QUBE_HEIGHT_SCALE_PX: Float
        get() {
            val halfW = TILE_WIDTH_PX / 2f
            val halfH = TILE_HEIGHT_PX / 2f
            return sqrt(halfW * halfW + halfH * halfH)
        }

    /**
     * Cosmetic-only shrink applied to the QUBE after its rotation is
     * computed (see QubeRenderer). The rotation itself always pivots the
     * QUBE as if it exactly filled one cell -- that's what keeps every
     * topple landing flush with no visual jump between cycles -- so this
     * factor never touches the rotation math, it only pulls the final
     * on-screen corners slightly toward the QUBE's own center. 1.0 = no
     * gap (fills the cell edge-to-edge); lower = more visible gap between
     * the QUBE and the cell boundary.
     */
    const val QUBE_VISUAL_SCALE = 0.95f

    /**
     * A toppling QUBE's highest corner rises to exactly sqrt(2) (~1.414)
     * world-height units above the ground at the midpoint of its
     * rotation (a fixed property of a half-extent-0.5 cube pivoting 90
     * degrees about its own edge -- not something to retune). Rounded up
     * for a small safety margin. GameView.boardBounds uses this, together
     * with [QUBE_HEIGHT_SCALE_PX], to make sure a QUBE toppling through
     * the board's back row never draws above the top of the screen.
     */
    const val QUBE_MAX_LIFT_WORLD_UNITS = 1.5f
}
