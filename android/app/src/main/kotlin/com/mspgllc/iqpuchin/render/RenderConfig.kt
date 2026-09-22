package com.mspgllc.iqpuchin.render

import kotlin.math.sqrt

/**
 * Every display-only tunable in one place. None of these affect game
 * rules (there are none yet) -- they exist purely so board size,
 * position, and player size can be retuned after real-device review
 * without touching board/input logic.
 */
object RenderConfig {
    /**
     * Baseline (unscaled) per-grid-cell screen displacement, split into a
     * dominant component and a small cross-axis component -- see
     * [IsoProjection] for how these two combine into the actual basis
     * vectors for gridX and gridZ. gridZ (the board's depth axis, also
     * the direction every NORMAL QUBE travels) uses [BOARD_AXIS_MAJOR_PX]
     * as its *vertical* step and [BOARD_AXIS_MINOR_PX] as its horizontal
     * step, so advancing in gridZ reads as movement down the screen with
     * only a small sideways lean -- this is what makes the board a
     * vertical corridor instead of a 45-degree diagonal diamond. gridX
     * (the board's width axis) uses the same two numbers with the roles
     * swapped (dominant horizontal, minor vertical).
     *
     * The two numbers are deliberately shared between both axes (rather
     * than each axis getting its own independent pair) because that is
     * what makes gridX's basis vector (MAJOR, MINOR) and gridZ's basis
     * vector (-MINOR, MAJOR) have *exactly* equal screen length by
     * construction (sqrt(MAJOR^2 + MINOR^2) either way) -- required for
     * [QUBE_HEIGHT_SCALE_PX]'s derivation below to produce a true cube.
     */
    const val BOARD_AXIS_MAJOR_PX = 90f
    const val BOARD_AXIS_MINOR_PX = 6.3f

    /**
     * FRONT-ALIGNED-TEST-01: the alternative "minor" value used when
     * GameView's RenderMode is FRONT_ALIGNED instead of
     * CURRENT_ISOMETRIC -- zero, so [IsoProjection]'s basis vectors
     * become gridX -> (+MAJOR, 0) and gridZ -> (0, +MAJOR): a plain
     * axis-aligned grid with no diagonal lean, so "up/down/left/right on
     * screen" reads as exactly "up/down/left/right on the board" with no
     * mental rotation required.
     *
     * Trade-off, accepted deliberately per this round's own priority
     * (direction clarity over 3D polish): [IsoProjection]'s own class doc
     * already explains that a QUBE's dark "right" side face only varies
     * in gridZ/worldHeight, so it needs a non-zero minor contribution to
     * screen X to have any width at all -- at [BOARD_AXIS_MINOR_PX] = 0
     * that face collapses to a zero-width line (in practice invisible).
     * The top and front faces are unaffected (their own varying axes are
     * gridX and worldHeight, both still fully in effect), so a QUBE still
     * reads as a raised block with real depth from its front face plus
     * the height-driven topple lift -- just without a rendered third
     * side face.
     */
    const val BOARD_AXIS_MINOR_PX_FRONT_ALIGNED = 0f

    /**
     * AZUSAN-PLAYER-01: baseline (unscaled) on-screen height every Azusan
     * sprite's own opaque-content bounding box is normalized to (see
     * [AzusanPose.contentBox] and [PlayerRenderer]) -- each source PNG
     * has different amounts of transparent padding baked in per pose, so
     * without this every pose would render at a different apparent size.
     * Chosen close to one board cell's own on-screen span
     * ([BOARD_AXIS_MAJOR_PX]) so Azusan reads as roughly cell-sized, with
     * a modest overshoot deliberately allowed (per spec, a crown/tail
     * peeking past the cell edge is fine) rather than shrinking her to
     * fit strictly inside the tile.
     */
    const val PLAYER_SPRITE_TARGET_HEIGHT_PX = 108f

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
     * Derived, not tuned by feel: BOARD_AXIS_MAJOR_PX/BOARD_AXIS_MINOR_PX
     * already define how a 1-world-unit edge along the ground (X or Z)
     * projects to screen pixels -- both project to the same length,
     * sqrt(MAJOR^2 + MINOR^2), by construction (see the doc above). For a
     * QUBE that is genuinely 1x1x1 in world space (see QubeRenderer --
     * its half-extent is a fixed 0.5 in every direction) to actually look
     * like a cube rather than a slab, its vertical (Y) edge needs to
     * project to that *same* screen length. That equality is exactly
     * what this formula guarantees; it is intentionally a computed
     * property (not a constant) so it can never drift out of sync with
     * BOARD_AXIS_MAJOR_PX/BOARD_AXIS_MINOR_PX if those are ever retuned.
     */
    val QUBE_HEIGHT_SCALE_PX: Float
        get() {
            val major = BOARD_AXIS_MAJOR_PX
            val minor = BOARD_AXIS_MINOR_PX
            return sqrt(major * major + minor * minor)
        }

    /**
     * The height scale actually used when *drawing* a QUBE (GameView
     * feeds this, not [QUBE_HEIGHT_SCALE_PX], into the height argument of
     * the [IsoProjection] instance it hands to QubeRenderer -- floor
     * tiles and the player marker never pass a worldHeight, so nothing
     * else is affected by this).
     *
     * Deliberately smaller than the true [QUBE_HEIGHT_SCALE_PX], which
     * stays exactly as derived above and is still what
     * BoardRenderer.boardBounds uses for clearance. Under the current
     * near-vertical camera (BOARD_AXIS_MINOR_PX small relative to
     * BOARD_AXIS_MAJOR_PX), a QUBE drawn at its true height reads as
     * visibly elongated: its always-thin dark side face (width fixed at
     * BOARD_AXIS_MINOR_PX, since gridZ barely moves screen X under this
     * camera) has a screen *height* of BOARD_AXIS_MAJOR_PX + trueHeight
     * -- the board-depth span alone already contributes
     * BOARD_AXIS_MAJOR_PX to that, regardless of the height chosen -- so
     * that face towers over the top/front faces no matter what. The top
     * face's own shape depends only on the X/Z axes, never on height, so
     * it stays exactly as square as before at any value here; verified
     * numerically that this fraction meaningfully shortens the overall
     * silhouette (and the side face's spike) while keeping the front
     * face -- the one carrying the QUBE's face decoration -- still
     * clearly readable as its own face, not flattened to a sliver.
     */
    const val QUBE_VISUAL_HEIGHT_FRACTION = 0.6f
    val QUBE_VISUAL_HEIGHT_SCALE_PX: Float
        get() = QUBE_HEIGHT_SCALE_PX * QUBE_VISUAL_HEIGHT_FRACTION

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
