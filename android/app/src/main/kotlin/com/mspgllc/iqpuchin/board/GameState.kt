package com.mspgllc.iqpuchin.board

/**
 * Overall run state, tracked independently of any single system (MARK/
 * ACTIVATE/CAPTURE, PLAYER movement, QUBE motion). STEP 7 only adds
 * PLAYING and HIT; future states such as FAILED/CLEAR are expected to
 * join this enum later without changing how the existing ones are
 * checked or transitioned.
 */
enum class GameState {
    PLAYING,
    HIT
}
