package com.mspgllc.iqpuchin.input

import com.mspgllc.iqpuchin.board.Direction

/** Actions GameView understands, independent of whatever device produced them. */
interface InputActionListener {
    fun onMoveRequested(direction: Direction)

    /**
     * CONTROL-SIMPLE-01: fired by a plain TAP on the ACTION paw only
     * (see [PawActionButtonView]) -- CAT_PUNCH is no longer decided in
     * here (see [onPunchGestureRequested]), so this is unconditionally
     * MARK/ACTIVATE: no mark pending -> marks the player's current cell.
     * A mark pending -> judges it (ACTIVATE) against every QUBE's
     * current coord, then clears the mark either way so the next press
     * starts a fresh MARK.
     */
    fun onActionRequested()

    /**
     * CONTROL-SIMPLE-01: fired by the ACTION paw's distinct downward-
     * slide gesture only (see [PawActionButtonView]) -- never by a plain
     * tap, and a single touch gesture only ever fires one of
     * [onActionRequested]/this, never both. Always attempts CAT_PUNCH
     * (via the existing punch-range check) and nothing else -- it never
     * places or judges a MARK, regardless of whether one is pending.
     */
    fun onPunchGestureRequested()
}
