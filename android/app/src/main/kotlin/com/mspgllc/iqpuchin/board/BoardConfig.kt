package com.mspgllc.iqpuchin.board

/**
 * Board dimensions, kept as a single easily-adjustable place. STEP 2 only
 * needs size -- there is no floor collapse/removal yet, so every cell in
 * range is walkable.
 */
object BoardConfig {
    const val GRID_WIDTH = 7
    const val GRID_DEPTH = 9
}
