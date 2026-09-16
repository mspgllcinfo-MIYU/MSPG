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
 * CATPUNCH-01: [life] now decrements on that exact same rising edge --
 * same detection, same one-shot-per-contact guarantee, just one more
 * side effect of an already-existing edge. When that decrement empties
 * [life], [state] goes to GAME_OVER instead of the usual brief HIT (see
 * [update]: GAME_OVER never times back out on its own, unlike HIT).
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

        /** CATPUNCH-01: internal life count PLAYER starts with. The UI
         * never shows this integer directly (see the churu-count
         * renderer) -- it exists here purely as the plain 3->0 counter
         * the spec asks for. */
        const val STARTING_LIFE = 3
    }

    var state: GameState = GameState.PLAYING
        private set

    var hitCount: Int = 0
        private set

    /** CATPUNCH-01: remaining life, [STARTING_LIFE] down to 0. Never goes
     * negative; reaching 0 is what drives the GAME_OVER transition below. */
    var life: Int = STARTING_LIFE
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
        if (state == GameState.GAME_OVER) return false
        val colliding = qubeCoords.any { it == playerPosition }
        val isNewHit = colliding && !inContact
        if (isNewHit) {
            hitCount++
            life = (life - 1).coerceAtLeast(0)
            hitElapsedMs = 0L
            state = if (life <= 0) GameState.GAME_OVER else GameState.HIT
        }
        inContact = colliding
        return isNewHit
    }

    /** Advances the HIT sub-state's own timer back toward PLAYING; call
     * once per frame alongside [checkCollision]. No-op while PLAYING or
     * GAME_OVER -- unlike HIT, GAME_OVER never times back out. */
    fun update(deltaMs: Long) {
        if (state != GameState.HIT) return
        hitElapsedMs += deltaMs
        if (hitElapsedMs >= HIT_DURATION_MS) {
            state = GameState.PLAYING
        }
    }
}
