package com.mspgllc.iqpuchin.board

/**
 * Owns [state] and [hitCount]. HIT-01: a collision no longer ends the
 * game or sticks forever -- it's a brief, non-blocking sub-state
 * (PLAYING -> HIT -> back to PLAYING on its own, after
 * [HIT_DURATION_MS]) that also increments [hitCount] exactly once per
 * contact. Nothing here or elsewhere reads [state] to block player
 * movement, QUBE motion, or MARK/ACTIVATE -- gameplay continues through
 * a HIT exactly as before; [state] only drives the debug "HIT" banner.
 * A second/later hit's own consequences (a fleeing QUBE, FAILED, etc.)
 * are explicitly out of scope here -- every hit currently runs this same
 * brief flow and increments the same counter.
 *
 * Judges collision purely from logical GridCoords -- PLAYER's current
 * cell against every QUBE's current cell -- never render/animation
 * position, same discipline as [CaptureSystem] -- and takes a plain list
 * of QUBE coordinates so it can scan any number of QUBEs without knowing
 * anything about how GameView stores them. Kept as its own class
 * (separate from CaptureSystem, an unrelated MARK/ACTIVATE rule) so
 * future states -- FAILED, CLEAR, floor collapse -- can be added as more
 * transitions on this same controller instead of scattering state flags
 * through GameView.
 */
class GameStateController {
    companion object {
        /** How long [state] stays HIT before returning to PLAYING on its
         * own -- a logic-side timer, independent of (though similar in
         * length to) PlayerRenderer/PoiHitReaction's own visual squash/
         * recover timing; see that class for the animation itself. */
        const val HIT_DURATION_MS = 500L
    }

    var state: GameState = GameState.PLAYING
        private set

    var hitCount: Int = 0
        private set

    private var hitElapsedMs = 0L

    /** Whether PLAYER is *currently* sharing a cell with any QUBE, as of
     * the last [checkCollision] call. */
    private var inContact = false

    /**
     * [inContact] tracks whether PLAYER is currently overlapping any
     * QUBE so a single continuous contact only ever increments
     * [hitCount] once, at the instant contact begins -- the count can
     * only rise again after contact is fully released (PLAYER or that
     * QUBE moves off the shared cell) and then made again. This is what
     * stops the same still-overlapping QUBE from re-triggering the
     * instant Poi's recovery animation ends, even though [state] itself
     * has already reverted to PLAYING well before contact necessarily
     * breaks.
     *
     * Returns true exactly when this call registered a *new* hit (the
     * rising edge), so callers can trigger a one-shot reaction/SE
     * without duplicating this edge-detection themselves.
     */
    fun checkCollision(playerPosition: GridCoord, qubeCoords: List<GridCoord>): Boolean {
        val colliding = qubeCoords.any { it == playerPosition }
        val isNewHit = colliding && !inContact
        if (isNewHit) {
            hitCount++
            state = GameState.HIT
            hitElapsedMs = 0L
        }
        inContact = colliding
        return isNewHit
    }

    /** Advances the HIT sub-state's own timer back toward PLAYING; call
     * once per frame alongside [checkCollision]. No-op while PLAYING. */
    fun update(deltaMs: Long) {
        if (state != GameState.HIT) return
        hitElapsedMs += deltaMs
        if (hitElapsedMs >= HIT_DURATION_MS) {
            state = GameState.PLAYING
        }
    }
}
