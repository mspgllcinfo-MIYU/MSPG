package com.mspgllc.iqpuchin.board

/**
 * Every QUBE movement timing number in one place, so movement feel can be
 * retuned from real-device review without touching QubeMotion's logic.
 */
object QubeConfig {
    /** Time to topple 90 degrees onto the next cell. */
    const val ROTATION_DURATION_MS = 900L

    /** Time the QUBE sits fully settled on a cell before the next topple starts. */
    const val SETTLE_DURATION_MS = 600L

    /** Derived: total time to advance one cell (rotation + settle, ~1500ms
     * at the defaults above). Deliberately not its own independent field
     * -- duplicating it would let it drift out of sync with the two
     * values that actually define it. */
    val CYCLE_DURATION_MS: Long get() = ROTATION_DURATION_MS + SETTLE_DURATION_MS

    /** CATPUNCH-01: hits a NORMAL QUBE can take from a cat punch before it
     * breaks. Lives here (not as a literal in Qube's constructor default)
     * so it's retunable from the same place as every other QUBE-feel
     * number, without touching Qube.kt itself. */
    const val NORMAL_QUBE_DURABILITY = 2
}
