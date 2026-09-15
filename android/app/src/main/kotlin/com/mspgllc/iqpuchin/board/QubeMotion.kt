package com.mspgllc.iqpuchin.board

/**
 * Advances a [Qube]'s *logical* position on a fixed cycle
 * ([QubeConfig.CYCLE_DURATION_MS]): each cycle is exactly one
 * "rotate 90 degrees, then land" step onto the next cell. The logical
 * coordinate only ever changes at the instant a cycle completes -- it is
 * never nudged mid-animation. [rotationProgress] exposes how far through
 * the *current* cycle's rotation portion we are, purely for the
 * renderer; nothing here knows or cares how a rotation looks on screen.
 *
 * When the next cell would be off the board, the QUBE simply stops
 * (STEP 3 scope: no despawn/fall/penalty yet).
 */
class QubeMotion(private val qube: Qube) {

    var elapsedSinceStepMs: Long = 0L
        private set

    var stopped: Boolean = false
        private set

    fun update(deltaMs: Long) {
        if (stopped) return
        elapsedSinceStepMs += deltaMs
        // A while loop (not a single if) so a single large deltaMs
        // (e.g. after the app was backgrounded) can't skip a step --
        // each full cycle still lands exactly once before the next check.
        while (!stopped && elapsedSinceStepMs >= QubeConfig.CYCLE_DURATION_MS) {
            val next = qube.direction.step(qube.coord)
            if (BoardConfig.isInside(next)) {
                qube.step(next)
                elapsedSinceStepMs -= QubeConfig.CYCLE_DURATION_MS
            } else {
                stopped = true
                elapsedSinceStepMs = QubeConfig.ROTATION_DURATION_MS
            }
        }
    }

    /** 0f at the instant a topple begins, 1f once the 90-degree rotation
     * portion of this cycle has finished (the remaining settle time
     * keeps this pinned at 1f, so the renderer keeps drawing a
     * fully-rested QUBE until the next topple starts). */
    fun rotationProgress(): Float {
        val t = elapsedSinceStepMs.toFloat() / QubeConfig.ROTATION_DURATION_MS.toFloat()
        return t.coerceIn(0f, 1f)
    }
}
