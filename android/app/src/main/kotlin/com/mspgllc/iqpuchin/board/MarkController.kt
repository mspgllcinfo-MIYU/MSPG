package com.mspgllc.iqpuchin.board

/**
 * Tracks the single floor cell currently MARKed, by logical grid
 * coordinate. STEP 4 only ever has one mark at a time -- placing a new
 * one simply replaces whatever was marked before, and a mark stays put
 * once the player walks away from it. No game rule (ACTIVATE/CAPTURE)
 * reacts to this yet; it exists purely as state for the renderer to
 * show. Kept free of any Android/UI type, same as BoardLogic/QubeMotion.
 */
class MarkController {
    var markedCoord: GridCoord? = null
        private set

    fun markAt(coord: GridCoord) {
        markedCoord = coord
    }
}
