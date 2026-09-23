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
    QUBE_BREAK,

    // SOUND-01A: the nine events EFFECT-01A/01B/01C's visual timelines
    // need SE synced to. GameView is the only caller for all nine -- see
    // its own lifeLossSoundSchedule/catEffectSoundSchedule, both derived
    // directly from GlassCrackEffect/LifeLossAzusanEffect/
    // GameOverCatEffect's own existing timing constants rather than a
    // second, hardcoded copy of those millisecond values.

    /** EFFECT-01C: one of あずさん's 3 running frames during the
     * life-loss punch overlay (LifeLossAzusanEffect). Fires 3 times per
     * life-loss HIT, once per RUN_FRAME_MS step. */
    AZUSAN_STEP,

    /** EFFECT-01A: GlassCrackEffect's crack pattern for this HIT starts
     * growing in -- fires once per life-loss HIT, the same instant the
     * life-loss punch overlay's own impact frame begins showing (see
     * LifeLossAzusanEffect.IMPACT_START_MS). SOUND-01A-FIX-01: the
     * current spec has あずさん's life-loss overlay make contact without
     * a distinct "punch" motion of its own, so this is the only event
     * fired at that instant -- there is no AZUSAN_PUNCH. */
    GLASS_CRACK,

    /** EFFECT-01B: one of まり/あんこ/あずさん's 3 running frames during
     * the GAME OVER finishing sequence (GameOverCatEffect). Fires 3 times
     * per character (9 times total across the sequence). */
    GAMEOVER_CAT_STEP,

    /** EFFECT-01B: one of まり/あんこ/あずさん's impact frames begins
     * showing during the GAME OVER finishing sequence. Fires once per
     * character (3 times total). */
    GAMEOVER_CAT_IMPACT,

    /** EFFECT-01B: one of BB's 4 heavy stomps during the GAME OVER
     * finishing sequence. Fires 4 times. */
    BB_STOMP,

    /** EFFECT-01B: BB's final-impact pose begins showing, right before
     * the glass shatter. Fires once. */
    BB_FINAL_IMPACT,

    /** EFFECT-01B: the glass-shatter burst begins (GameOverCatEffect's
     * own SHATTER_START_MS). Fires once. */
    GLASS_SHATTER,

    /** EFFECT-01B: the GAME OVER finishing sequence has fully finished
     * (GameOverCatEffect.TOTAL_DURATION_MS) -- the same instant GameView
     * finally allows the existing GAME OVER text/overlay to show. Fires
     * once per GAME OVER. */
    GAME_OVER
}
