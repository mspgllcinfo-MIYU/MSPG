package com.mspgllc.iqpuchin.board

/**
 * Board dimensions, kept as a single easily-adjustable place. There is no
 * floor collapse/removal yet, so every cell in range is walkable -- both
 * the player (BoardLogic) and the QUBE (QubeMotion) share [isInside] as
 * the one definition of "on the board".
 */
object BoardConfig {
    const val GRID_WIDTH = 7
    const val GRID_DEPTH = 12

    fun isInside(coord: GridCoord): Boolean =
        coord.x in 0 until GRID_WIDTH && coord.z in 0 until GRID_DEPTH
}
