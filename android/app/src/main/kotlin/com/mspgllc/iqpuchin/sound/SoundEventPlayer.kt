package com.mspgllc.iqpuchin.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.mspgllc.iqpuchin.R

/**
 * The single place game code reports "this happened" and sound code
 * decides what, if anything, to actually play. SOUND-02: now backed by a
 * real [SoundPool] loaded from res/raw (every asset, including
 * CATPUNCH-01's punch_hit/qube_break, is original, procedurally-
 * synthesized SE -- nothing sampled or reused), but the SOUND-01 funnel
 * contract is unchanged: every caller (GameView,
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

        // CATPUNCH-FIX-01: real-device feedback was that both QUBE SE
        // still read as too light even after SOUND-03's added bands --
        // bumped further alongside the assets' own strengthened
        // 150-300Hz/300-600Hz content (see the regenerated WAVs; QUBE_LAND
        // sits near SoundPool's ceiling since its own peak is already
        // near-maximal).
        // SWIPE-TEST-01: "heavier, not yet finished" on real hardware --
        // both WAVs got one more notch on the same two bands via relative
        // mix balance (those layers louder relative to the rest of the
        // mix, same normalize peak as before), so these volumes are
        // deliberately left unchanged rather than pushed further, per the
        // explicit instruction not to raise volume to the point of
        // clipping or burying other SE.
        const val VOLUME_QUBE_ROLL_START = 0.78f
        const val VOLUME_QUBE_LAND = 0.97f
        const val VOLUME_MARK_SET = 0.6f
        const val VOLUME_CAPTURE_SUCCESS = 0.65f

        // SOUND-03: the real-device finding was that Poi's yelp was
        // inaudible under the impact thud -- diagnosis (see the SOUND-03
        // commit message) showed the two assets' own spectra were already
        // well separated (impact >99% below 300Hz, squeal now >99% in
        // 1.5-4kHz), but the *level* balance had it backwards: the impact
        // played louder (0.8) than the squeal (0.7) despite the squeal
        // being the signal that must always read clearly. Inverted here.
        // CATPUNCH-FIX-01/SWIPE-TEST-01: squeal.wav itself has been
        // redesigned twice since (see the generation script's notes --
        // SWIPE-TEST-01's version rebuilds it around an actual plosive
        // "g" burst + bright glide-into-vowel + hard cutoff, aimed at the
        // syllable "gya!" rather than just its spectrum) -- this level
        // balance still holds either way, so the volumes are unchanged.
        const val VOLUME_POI_HIT_IMPACT = 0.68f
        const val VOLUME_POI_HIT_SQUEAL = 1.0f

        // CATPUNCH-01
        const val VOLUME_PUNCH_HIT = 0.8f
        const val VOLUME_QUBE_BREAK = 0.85f
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
    private val punchHitId = load(R.raw.punch_hit)
    private val qubeBreakId = load(R.raw.qube_break)

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
            SoundEvent.PUNCH_HIT -> playSample(punchHitId, VOLUME_PUNCH_HIT)
            SoundEvent.QUBE_BREAK -> {
                playSample(punchHitId, VOLUME_PUNCH_HIT)
                playSample(qubeBreakId, VOLUME_QUBE_BREAK)
            }

            // SOUND-01A: the funnel/trigger plumbing for these nine is now
            // fully wired from GameView (see lifeLossSoundSchedule/
            // catEffectSoundSchedule there) at the exact instants their
            // own class docs describe, but no asset exists for any of
            // them yet -- per this round's own explicit "don't fetch
            // external material" instruction, each is a deliberate no-op
            // for now. A future SOUND round fills these in one at a time
            // (synthesize/load + playSample(), same shape as every event
            // above) without touching any call site, exactly like
            // SOUND-02 once did for this file's original SOUND-01 funnel.
            SoundEvent.AZUSAN_STEP -> {}
            SoundEvent.GLASS_CRACK -> {}
            SoundEvent.GAMEOVER_CAT_STEP -> {}
            SoundEvent.GAMEOVER_CAT_IMPACT -> {}
            SoundEvent.BB_STOMP -> {}
            SoundEvent.BB_FINAL_IMPACT -> {}
            SoundEvent.GLASS_SHATTER -> {}
            SoundEvent.GAME_OVER -> {}
        }
    }

    private fun playSample(id: Int, volume: Float) {
        if (loaded[id] != true) return
        soundPool.play(id, volume, volume, 1, 0, 1.0f)
    }
}
