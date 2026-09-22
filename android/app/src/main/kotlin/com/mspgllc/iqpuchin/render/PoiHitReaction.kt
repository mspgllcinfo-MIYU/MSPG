package com.mspgllc.iqpuchin.render

import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure timing for the PLAYER's brief HIT reaction window -- purely
 * cosmetic, never touches game state or HIT judgement itself (see
 * GameStateController, which this class knows nothing about). GameView
 * is responsible for calling [trigger] exactly once, on the PLAYING ->
 * HIT edge. Nothing else in the game (QUBE motion, input, timers) is
 * paused while this plays -- it is advanced by the same per-frame
 * [update] call as everything else, never blocking.
 *
 * AZUSAN-PLAYER-01: since the vector "Poi" design (which used [progress]
 * directly to drive a squash/tail-flick animation) was replaced by
 * sprite images, GameView now only reads [progress] as a simple
 * active/inactive signal (`> 0f`) to gate its own separately-tracked
 * elapsed-time counter, which PlayerRenderer uses to pick between the
 * HIT and RECOVER sprites -- this class's own timing/shape is unchanged.
 */
class PoiHitReaction {
    companion object {
        /** Short on purpose -- a flinch, not a cutscene. */
        const val DURATION_MS = 400L
    }

    private var elapsedMs = 0L
    private var playing = false

    fun trigger() {
        playing = true
        elapsedMs = 0L
    }

    fun update(deltaMs: Long) {
        if (!playing) return
        elapsedMs += deltaMs
        if (elapsedMs >= DURATION_MS) {
            playing = false
            elapsedMs = 0L
        }
    }

    /** 0f when idle, rising to 1f and back down to 0f over
     * [DURATION_MS] while playing -- a single impact-then-settle pulse
     * so PlayerRenderer can drive squash/tail-flick without its own
     * separate easing/timer. */
    fun progress(): Float {
        if (!playing) return 0f
        val t = (elapsedMs.toFloat() / DURATION_MS.toFloat()).coerceIn(0f, 1f)
        return sin(t * PI.toFloat())
    }
}
