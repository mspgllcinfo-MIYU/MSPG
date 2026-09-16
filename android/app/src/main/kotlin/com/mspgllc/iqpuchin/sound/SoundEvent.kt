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
     * rose) -- never fires again while that same contact continues.
     * CATPUNCH-01: this is also PLAYER's "crushed" event now that
     * collisions cost a life -- unchanged asset/meaning, just a second
     * consequence riding the same edge (see GameStateController.life). */
    POI_HIT,

    /** CATPUNCH-01: a cat punch landed on a QUBE that survived it
     * (durability dropped but didn't reach 0). Fires once per punch. */
    PUNCH_HIT,

    /** CATPUNCH-01: a cat punch just destroyed a QUBE (durability reached
     * 0). Fires once, instead of [PUNCH_HIT], for the punch that breaks
     * it. */
    QUBE_BREAK
}
