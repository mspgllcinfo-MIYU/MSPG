package com.mspgllc.iqpuchin.input

import com.mspgllc.iqpuchin.board.Direction

/** Actions GameView understands, independent of whatever device produced them. */
interface InputActionListener {
    fun onMoveRequested(direction: Direction)

    /** Marks the player's current cell (STEP 4: replaces any previous mark). */
    fun onMarkRequested()
}
