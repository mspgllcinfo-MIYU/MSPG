package com.mspgllc.iqpuchin.board

/** A single logical position on the board grid. Purely a coordinate --
 * nothing here knows about screen pixels or drawing. */
data class GridCoord(val x: Int, val z: Int)
