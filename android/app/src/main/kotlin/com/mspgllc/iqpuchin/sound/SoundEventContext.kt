package com.mspgllc.iqpuchin.sound

/**
 * Optional per-call context for [SoundEventPlayer.play] -- extra,
 * non-identifying information a future mix might react to for a given
 * firing of a [SoundEvent], without changing that event's meaning or
 * any call site's signature again when a field actually gets used.
 *
 * Only [emitterDistance] exists today (the straight-line distance, in
 * grid cells, from the sounding QUBE to PLAYER -- see
 * [com.mspgllc.iqpuchin.sound.QubeSoundTracker]), and nothing in
 * [SoundEventPlayer] reads it yet. It exists purely so "quieter/duller
 * when far, louder/heavier when close" (the planned distance-based mix)
 * can be added later inside SoundEventPlayer alone.
 */
data class SoundEventContext(
    val emitterDistance: Float? = null
)
