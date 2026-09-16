package com.mspgllc.iqpuchin.input

import com.mspgllc.iqpuchin.board.Direction

/** Actions GameView understands, independent of whatever device produced them. */
interface InputActionListener {
    fun onMoveRequested(direction: Direction)

    /**
     * STEP 7: the single ACTION button. No mark pending -> marks the
     * player's current cell. A mark pending -> judges it (ACTIVATE)
     * against every QUBE's current coord, then clears the mark either
     * way so the next press starts a fresh MARK.
     */
    fun onActionRequested()
}
