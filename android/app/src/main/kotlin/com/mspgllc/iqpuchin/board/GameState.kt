package com.mspgllc.iqpuchin.board

/**
 * Overall run state, tracked independently of any single system (MARK/
 * ACTIVATE/CAPTURE, PLAYER movement, QUBE motion). HIT is a brief,
 * self-reverting sub-state (see GameStateController.HIT_DURATION_MS) --
 * a collision does not end or block gameplay. Future states such as
 * FAILED/CLEAR are expected to join this enum later without changing how
 * the existing ones are checked or transitioned.
 *
 * CATPUNCH-01: GAME_OVER joins as the one non-reverting state -- reached
 * only when a collision drops life to 0 (see GameStateController). Unlike
 * HIT it never times back out on its own.
 */
enum class GameState {
    PLAYING,
    HIT,
    GAME_OVER
}
