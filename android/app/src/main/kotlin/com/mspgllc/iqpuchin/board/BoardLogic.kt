package com.mspgllc.iqpuchin.board

/**
 * STEP 2 game state: just the player's logical grid position and
 * boundary-checked movement. No QUBE, no MARK/ACTIVATE, no game rules --
 * those come in a later step. Kept deliberately free of any Android/UI
 * type so input and rendering can each depend on it without depending on
 * each other.
 */
class BoardLogic(
    startPosition: GridCoord = GridCoord(BoardConfig.GRID_WIDTH / 2, BoardConfig.GRID_DEPTH / 2)
) {
    var playerPosition: GridCoord = startPosition
        private set

    private fun isInsideBoard(coord: GridCoord): Boolean =
        coord.x in 0 until BoardConfig.GRID_WIDTH && coord.z in 0 until BoardConfig.GRID_DEPTH

    /** Moves the player one cell if that cell is on the board. Returns
     * whether the position actually changed, so the caller only needs to
     * redraw when something moved. */
    fun movePlayer(direction: Direction): Boolean {
        val next = direction.step(playerPosition)
        if (!isInsideBoard(next)) return false
        playerPosition = next
        return true
    }
}
