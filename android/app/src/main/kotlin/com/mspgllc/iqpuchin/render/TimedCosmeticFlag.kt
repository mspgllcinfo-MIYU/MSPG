package com.mspgllc.iqpuchin.render

/**
 * AZUSAN-PLAYER-01: a generic one-shot cosmetic timer -- [trigger] starts
 * it, [update] counts it down, [active] is true until [durationMs]
 * elapses. Carries no game-logic meaning of its own; it's the same
 * trigger/update/active shape [PoiHitReaction] already established for
 * HIT, reused here (rather than duplicated) for GameView's WALK and
 * CAT_PUNCH sprite windows -- both are purely "show this picture for a
 * moment," nothing more.
 */
class TimedCosmeticFlag(private val durationMs: Long) {
    private var elapsedMs = 0L

    var active = false
        private set

    fun trigger() {
        active = true
        elapsedMs = 0L
    }

    fun update(deltaMs: Long) {
        if (!active) return
        elapsedMs += deltaMs
        if (elapsedMs >= durationMs) {
            active = false
            elapsedMs = 0L
        }
    }
}
