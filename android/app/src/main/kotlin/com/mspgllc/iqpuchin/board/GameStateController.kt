package com.mspgllc.iqpuchin.board

/**
 * Owns [state] and the one rule that changes it so far: PLAYING -> HIT
 * the instant PLAYER and any NORMAL QUBE share the same logical
 * [GridCoord]. Judges strictly by logical grid coordinates -- never
 * render/animation position, same discipline as [CaptureSystem] -- and
 * takes a plain list of QUBE coordinates so it can scan any number of
 * QUBEs without knowing anything about how GameView stores them.
 *
 * HIT is sticky: once reached, [checkCollision] becomes a no-op, so
 * nothing here currently reverts it back to PLAYING. Kept as its own
 * class (separate from CaptureSystem, which judges an unrelated MARK/
 * ACTIVATE rule) so future states -- FAILED, CLEAR, floor collapse --
 * can be added as more transitions on this same controller instead of
 * scattering state flags through GameView.
 */
class GameStateController {
    var state: GameState = GameState.PLAYING
        private set

    fun checkCollision(playerPosition: GridCoord, qubeCoords: List<GridCoord>) {
        if (state != GameState.PLAYING) return
        if (qubeCoords.any { it == playerPosition }) {
            state = GameState.HIT
        }
    }
}
