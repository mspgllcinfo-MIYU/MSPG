package com.mspgllc.iqpuchin.sound

/**
 * The single place game code reports "this happened" and sound code
 * decides what, if anything, to actually play. Every caller in this app
 * (GameView, [QubeSoundTracker]) only ever names a [SoundEvent] and
 * optionally a [SoundEventContext]; no audio asset exists yet, so [play]
 * is an intentional no-op rather than a placeholder tone.
 *
 * This is deliberately the *only* funnel point for every SE in the game
 * -- which is what makes the mix-level concerns explicitly deferred this
 * round straightforward to add later without touching any call site:
 * - Which asset, volume, pitch, or EQ a given [SoundEvent] maps to.
 * - Simultaneous-voice limiting (e.g. several QUBEs landing the same
 *   frame) -- one place already sees every firing, in order.
 * - [SoundEventContext.emitterDistance]-based mix (quieter/duller when
 *   the sounding QUBE is far from PLAYER, per the game's planned sound
 *   design) -- the value already arrives here per call, just unread.
 * Wiring in real playback (e.g. SoundPool) later means changing this
 * file (or the audio assets/tuning tables it references), never any of
 * its callers.
 */
class SoundEventPlayer {
    fun play(event: SoundEvent, context: SoundEventContext = SoundEventContext()) {
        // No audio asset available yet -- intentionally left as a no-op
        // trigger point (see class doc). `event` and `context` already
        // carry everything a future implementation needs.
    }
}
