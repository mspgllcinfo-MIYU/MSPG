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
        // 150-300Hz/300-600Hz content (see the regenerated WAVs).
        // SWIPE-TEST-01: "heavier, not yet finished" on real hardware --
        // both WAVs got one more notch on the same two bands via relative
        // mix balance (those layers louder relative to the rest of the
        // mix, same normalize peak as before).
        const val VOLUME_QUBE_ROLL_START = 0.78f
        // SOUND-QUALITY-02: qube_land.wav's own asset was rewritten (see
        // that round's notes below and its own commit) to carry a real
        // 100Hz-3kHz impact transient + 400Hz-1.5kHz material texture
        // instead of a near-single-band 150-400Hz tone, so it now reads
        // as heavy through its own spectral content rather than needing
        // near-maximal gain -- lowered from 0.97 to sit in this round's
        // explicit AZUSAN_STEP < QUBE_LAND < GLASS_CRACK ordering (with
        // headroom: asset peak 0.86 * 0.80 = 0.688, well under clipping).
        const val VOLUME_QUBE_LAND = 0.80f
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

        // SOUND-01B: azusan_step.wav plays quietly -- it fires 3 times
        // per life-loss HIT (see GameView's lifeLossSoundSchedule), so it
        // must read as a light running footstep, not compete with the
        // impact SE that follows.
        // SOUND-QUALITY-02: azusan_step.wav/glass_crack.wav/qube_land.wav
        // were all rewritten for material realism (see this round's own
        // commit for the full acoustic design) with an explicit relative
        // hierarchy -- AZUSAN_STEP < QUBE_LAND < GLASS_CRACK, so the
        // pane-cracking moment reads as the most important of the three
        // without simply being the loudest number: glass_crack.wav's own
        // 2.5-9kHz content already cuts through a phone speaker's weak
        // low end more than raw gain would, so its volume only needed a
        // small nudge (0.85->0.88) rather than pushing toward 1.0 (asset
        // peak 0.82 * 0.88 = 0.722, still comfortable headroom below
        // clipping).
        const val VOLUME_AZUSAN_STEP = 0.5f
        const val VOLUME_GLASS_CRACK = 0.88f

        // SOUND-QUALITY-04: the first 3 of SOUND-01A's six remaining
        // GAME OVER-side no-ops get a real asset. Explicit hierarchy per
        // this round's own instruction -- GAMEOVER_CAT_STEP <
        // GAMEOVER_CAT_IMPACT < BB_STOMP -- via effective peak (asset
        // peak * volume): STEP 0.72*0.55=0.396, IMPACT 0.75*0.65=0.488,
        // STOMP 0.85*0.80=0.68. BB_STOMP is deliberately kept below
        // QUBE_BREAK's own 0.765 and GLASS_CRACK's own 0.722, leaving
        // clear headroom under 1.0 for BB_FINAL_IMPACT/GLASS_SHATTER (a
        // future round) to read as the larger event -- BB_STOMP is not
        // pushed to be the loudest SE in the game.
        const val VOLUME_GAMEOVER_CAT_STEP = 0.55f
        const val VOLUME_GAMEOVER_CAT_IMPACT = 0.65f
        const val VOLUME_BB_STOMP = 0.80f
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

    // SOUND-01B: same procedurally-synthesized-only method every existing
    // asset above uses (numpy sine/noise oscillators + hand-authored
    // envelopes, no sampled or third-party material) -- azusan_step.wav
    // and glass_crack.wav are the first two of SOUND-01A's nine no-op
    // events to get a real asset; the other seven remain deliberate
    // no-ops below, unchanged.
    private val azusanStepId = load(R.raw.azusan_step)
    private val glassCrackId = load(R.raw.glass_crack)

    // SOUND-QUALITY-04: same procedurally-synthesized-only method as
    // every asset above -- the next 3 of SOUND-01A's six remaining GAME
    // OVER-side no-ops (GAMEOVER_CAT_STEP/GAMEOVER_CAT_IMPACT/BB_STOMP)
    // get a real asset. BB_FINAL_IMPACT/GLASS_SHATTER/GAME_OVER remain
    // deliberate no-ops below, unchanged.
    private val gameoverCatStepId = load(R.raw.gameover_cat_step)
    private val gameoverCatImpactId = load(R.raw.gameover_cat_impact)
    private val bbStompId = load(R.raw.bb_stomp)

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

            // SOUND-01B: the first two of SOUND-01A's originally-nine
            // no-op events now play a real, procedurally-synthesized
            // asset -- the funnel/trigger plumbing itself (GameView's
            // lifeLossSoundSchedule) is unchanged, exactly as SOUND-01A's
            // own comment predicted this would work.
            SoundEvent.AZUSAN_STEP -> playSample(azusanStepId, VOLUME_AZUSAN_STEP)
            SoundEvent.GLASS_CRACK -> playSample(glassCrackId, VOLUME_GLASS_CRACK)

            // SOUND-QUALITY-04: the まり/あんこ/あずさん run-in steps and
            // impacts, plus BB's 4 stomps, now play real assets -- the
            // funnel/trigger plumbing itself (GameView's own
            // catEffectSoundSchedule, and GameOverCatEffect's own visual
            // timing) is unchanged, exactly as SOUND-01A's own comment
            // predicted this would work for every one of these events.
            SoundEvent.GAMEOVER_CAT_STEP -> playSample(gameoverCatStepId, VOLUME_GAMEOVER_CAT_STEP)
            SoundEvent.GAMEOVER_CAT_IMPACT -> playSample(gameoverCatImpactId, VOLUME_GAMEOVER_CAT_IMPACT)
            SoundEvent.BB_STOMP -> playSample(bbStompId, VOLUME_BB_STOMP)

            // SOUND-01A: the remaining three GAME OVER-finale events stay
            // deliberate no-ops for now -- out of scope this round, which
            // is explicitly "cat entrance through BB's 4 stomps" only. A
            // future SOUND round fills these in the same way
            // (synthesize/load + playSample()) without touching any call
            // site.
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
