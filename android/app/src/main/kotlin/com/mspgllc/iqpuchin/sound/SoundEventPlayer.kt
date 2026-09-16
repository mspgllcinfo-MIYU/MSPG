package com.mspgllc.iqpuchin.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.mspgllc.iqpuchin.R

/**
 * The single place game code reports "this happened" and sound code
 * decides what, if anything, to actually play. SOUND-02: now backed by a
 * real [SoundPool] loaded from res/raw (all six assets are original,
 * procedurally-synthesized SE -- see the SOUND-02 generation script kept
 * alongside the assets' own notes; nothing sampled or reused), but the
 * SOUND-01 funnel contract is unchanged: every caller (GameView,
 * [QubeSoundTracker]) only ever names a [SoundEvent] [+ SoundEventContext];
 * which asset(s), at what volume, play for it lives entirely in this one
 * file, so a future mix change (asset swap, volume/pitch/EQ retune,
 * [SoundEventContext.emitterDistance]-based falloff, simultaneous-voice
 * limiting) never requires touching a call site.
 *
 * [SoundEvent.POI_HIT] plays two independent assets together
 * ([poiHitImpactId] + [poiHitSquealId]) rather than one pre-mixed file, so
 * the low QUBE-impact layer and Poi's high yelp stay separately tunable
 * later (e.g. "make the yelp higher") without re-synthesizing anything
 * else -- matching how every other event here is just one funnel call.
 *
 * SoundPool is used (over MediaPlayer) specifically for its low playback
 * latency, appropriate for one-shot SE this short; [MAX_STREAMS] is sized
 * generously so several QUBEs rolling/landing independently, plus a
 * MARK/CAPTURE/POI_HIT SE, can all sound at once without one stealing
 * another's voice.
 */
class SoundEventPlayer(context: Context) {

    private companion object {
        const val MAX_STREAMS = 8

        const val VOLUME_QUBE_ROLL_START = 0.55f
        const val VOLUME_QUBE_LAND = 0.85f
        const val VOLUME_MARK_SET = 0.6f
        const val VOLUME_CAPTURE_SUCCESS = 0.65f
        const val VOLUME_POI_HIT_IMPACT = 0.8f
        const val VOLUME_POI_HIT_SQUEAL = 0.7f
    }

    private val appContext = context.applicationContext

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    /** True for a given SoundPool sample ID only once it has actually
     * finished decoding -- SoundPool.play() on an unloaded ID is a silent
     * no-op, so without this a play() requested moments after this class
     * is constructed (e.g. the very first QUBE_ROLL_START on a cold
     * start) could be dropped. Loading these few, very short WAV files is
     * effectively instant in practice, but this makes that a guarantee
     * rather than a race. */
    private val loaded = HashMap<Int, Boolean>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded[sampleId] = true
        }
    }

    private val rollStartId = load(R.raw.qube_roll_start)
    private val landId = load(R.raw.qube_land)
    private val markSetId = load(R.raw.mark_set)
    private val captureSuccessId = load(R.raw.capture_success)
    private val poiHitImpactId = load(R.raw.poi_hit_impact)
    private val poiHitSquealId = load(R.raw.poi_hit_squeal)

    private fun load(resId: Int): Int {
        val id = soundPool.load(appContext, resId, 1)
        loaded[id] = false
        return id
    }

    fun play(event: SoundEvent, context: SoundEventContext = SoundEventContext()) {
        when (event) {
            SoundEvent.QUBE_ROLL_START -> playSample(rollStartId, VOLUME_QUBE_ROLL_START)
            SoundEvent.QUBE_LAND -> playSample(landId, VOLUME_QUBE_LAND)
            SoundEvent.MARK_SET -> playSample(markSetId, VOLUME_MARK_SET)
            SoundEvent.CAPTURE_SUCCESS -> playSample(captureSuccessId, VOLUME_CAPTURE_SUCCESS)
            SoundEvent.POI_HIT -> {
                playSample(poiHitImpactId, VOLUME_POI_HIT_IMPACT)
                playSample(poiHitSquealId, VOLUME_POI_HIT_SQUEAL)
            }
        }
    }

    private fun playSample(id: Int, volume: Float) {
        if (loaded[id] != true) return
        soundPool.play(id, volume, volume, 1, 0, 1.0f)
    }
}
