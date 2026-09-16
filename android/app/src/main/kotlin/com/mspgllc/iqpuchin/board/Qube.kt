package com.mspgllc.iqpuchin.board

/**
 * A single QUBE's authoritative logical position. [coord] only ever
 * changes in discrete, whole-cell jumps via [step] -- there is no
 * intermediate logical position while it topples. The visible toppling
 * motion (see QubeMotion / the renderer) is purely a function of elapsed
 * time between [previousCoord] and [coord]; it never feeds back into
 * this class.
 *
 * CATPUNCH-01: [durability] is a second, completely independent piece of
 * state -- a cat punch only ever changes this field via [punch], never
 * [coord]/[previousCoord], and QubeMotion never reads it. That's what
 * guarantees a punch can never stall or otherwise affect the QUBE's
 * movement cycle.
 */
class Qube(startCoord: GridCoord, val direction: Direction, durability: Int = QubeConfig.NORMAL_QUBE_DURABILITY) {
    var previousCoord: GridCoord = startCoord
        private set

    var coord: GridCoord = startCoord
        private set

    var durability: Int = durability
        private set

    fun step(next: GridCoord) {
        previousCoord = coord
        coord = next
    }

    /** Registers one cat-punch hit. Returns true if this hit just
     * destroyed the QUBE (durability reached 0) -- the caller is
     * responsible for actually removing it from play. */
    fun punch(): Boolean {
        if (durability > 0) durability -= 1
        return durability <= 0
    }
}
