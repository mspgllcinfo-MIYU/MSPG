package com.mspgllc.iqpuchin.sound

/**
 * Hook point for one-shot sound effects. No audio asset exists yet, so
 * each method here is an intentional no-op rather than a placeholder
 * tone (per request: don't force in a stand-in sound) -- this exists
 * purely so GameView has one place to call into when an SE should fire,
 * ready to wire up real playback (e.g. SoundPool) later without
 * touching any caller.
 */
class SoundEventPlayer {
    /**
     * Fires exactly once, the instant PLAYER collides with a QUBE
     * (PLAYING -> HIT) -- never again while already HIT. See
     * GameView.checkCollision for the single call site.
     */
    fun playHitMeow() {
        // No audio asset available yet -- intentionally left as a no-op
        // trigger point (see class doc).
    }
}
