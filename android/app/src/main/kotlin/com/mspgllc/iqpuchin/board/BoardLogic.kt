package com.mspgllc.iqpuchin.board

/**
 * The player's logical grid position and boundary-checked movement. No
 * MARK/ACTIVATE, no game rules -- those come in a later step. Kept
 * deliberately free of any Android/UI type so input and rendering can
 * each depend on it without depending on each other. Player/QUBE contact
 * is not handled yet (STEP 3 scope): they can currently occupy the same
 * cell with no effect.
 */
class BoardLogic(
    startPosition: GridCoord = GridCoord(BoardConfig.GRID_WIDTH / 2, BoardConfig.GRID_DEPTH / 2)
) {
    var playerPosition: GridCoord = startPosition
        private set

    /** Moves the player one cell if that cell is on the board. Returns
     * whether the position actually changed, so the caller only needs to
     * redraw when something moved. */
    fun movePlayer(direction: Direction): Boolean {
        val next = direction.step(playerPosition)
        if (!BoardConfig.isInside(next)) return false
        playerPosition = next
        return true
    }
}
