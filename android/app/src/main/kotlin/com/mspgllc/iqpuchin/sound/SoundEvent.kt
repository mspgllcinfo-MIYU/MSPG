package com.mspgllc.iqpuchin.sound

/**
 * Every distinct game moment that can trigger an SE. Game code (GameView
 * and the small per-system trackers in this package) only ever names
 * *which* of these just happened; [SoundEventPlayer] decides what, if
 * anything, to actually play for it -- adding a new sound, or changing
 * what an existing one sounds like, never requires touching a call site.
 */
enum class SoundEvent {
    /** A QUBE begins its 90-degree topple toward the next cell. */
    QUBE_ROLL_START,

    /** A QUBE's topple finishes and it lands on the next cell (the
     * settle time that follows is deliberately silent -- no event fires
     * for it). */
    QUBE_LAND,

    /** MARK is placed on PLAYER's current cell. */
    MARK_SET,

    /** ACTIVATE actually captured a QUBE (never fires on a non-matching
     * ACTIVATE press). */
    CAPTURE_SUCCESS,

    /** PLAYER's contact with a QUBE just began (HIT-01's hitCount just
     * rose) -- never fires again while that same contact continues. */
    POI_HIT
}
