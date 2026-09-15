package com.mspgllc.iqpuchin.input

import com.mspgllc.iqpuchin.board.Direction

/** Action GameView understands, independent of whatever device produced it. */
interface InputActionListener {
    fun onMoveRequested(direction: Direction)
}
