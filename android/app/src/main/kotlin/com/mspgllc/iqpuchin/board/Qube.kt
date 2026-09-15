package com.mspgllc.iqpuchin.board

/**
 * A single QUBE's authoritative logical position. [coord] only ever
 * changes in discrete, whole-cell jumps via [step] -- there is no
 * intermediate logical position while it topples. The visible toppling
 * motion (see QubeMotion / the renderer) is purely a function of elapsed
 * time between [previousCoord] and [coord]; it never feeds back into
 * this class.
 */
class Qube(startCoord: GridCoord, val direction: Direction) {
    var previousCoord: GridCoord = startCoord
        private set

    var coord: GridCoord = startCoord
        private set

    fun step(next: GridCoord) {
        previousCoord = coord
        coord = next
    }
}
