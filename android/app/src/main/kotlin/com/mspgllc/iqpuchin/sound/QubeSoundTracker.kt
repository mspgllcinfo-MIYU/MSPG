package com.mspgllc.iqpuchin.sound

import com.mspgllc.iqpuchin.board.GridCoord
import com.mspgllc.iqpuchin.board.Qube
import com.mspgllc.iqpuchin.board.QubeConfig
import com.mspgllc.iqpuchin.board.QubeMotion
import kotlin.math.hypot

/**
 * Watches one QUBE's already-existing, unmodified motion state
 * ([Qube.coord], [QubeMotion.elapsedSinceStepMs]/[QubeMotion.stopped])
 * and fires [SoundEvent.QUBE_ROLL_START] / [SoundEvent.QUBE_LAND] at the
 * right instants. A pure external observer -- exactly like GameView's
 * own HIT-edge detection -- it never touches QubeMotion/Qube's own
 * timing, pivot math, or the 900ms/600ms cycle those already define.
 *
 * One instance per QUBE (see GameView), so each QUBE's rolls/lands are
 * tracked and fired completely independently -- multiple QUBEs mid-
 * topple at once just means multiple trackers each firing on their own
 * schedule; nothing here assumes there is only one.
 *
 * Timing, derived from the existing unmodified fields rather than any
 * new one of this class's own:
 * - QUBE_ROLL_START fires the instant [Qube.coord] actually changes --
 *   that update happens (inside QubeMotion.update, untouched) at the
 *   exact moment a topple's destination cell is committed, which is
 *   also the exact moment the 90-degree rotation *toward* it begins.
 * - QUBE_LAND fires once elapsedSinceStepMs first reaches
 *   [QubeConfig.ROTATION_DURATION_MS] (900ms) *after* that same
 *   roll-start -- the instant the rotation finishes and the QUBE is
 *   resting on its new cell, right before the 600ms silent settle.
 *   [armedForLand] scopes this to the roll that actually started it, so
 *   the QUBE's very first cycle (previousCoord == coord, no real move
 *   yet -- see Qube's own doc) never gets a spurious land, and a QUBE
 *   that freezes at the board edge (QubeMotion.stopped) never gets one
 *   either, since no further roll ever arms it again.
 */
class QubeSoundTracker(private val qube: Qube, private val motion: QubeMotion) {
    private var lastObservedCoord: GridCoord = qube.coord
    private var armedForLand = false

    /** Call once per frame, alongside `motion.update(deltaMs)`.
     * [playerPosition] only feeds the (currently unused)
     * [SoundEventContext.emitterDistance] hook -- see that class's doc. */
    fun update(soundEventPlayer: SoundEventPlayer, playerPosition: GridCoord) {
        if (qube.coord != lastObservedCoord) {
            lastObservedCoord = qube.coord
            armedForLand = true
            soundEventPlayer.play(SoundEvent.QUBE_ROLL_START, contextFor(playerPosition))
        }
        if (armedForLand && !motion.stopped && motion.elapsedSinceStepMs >= QubeConfig.ROTATION_DURATION_MS) {
            armedForLand = false
            soundEventPlayer.play(SoundEvent.QUBE_LAND, contextFor(playerPosition))
        }
    }

    private fun contextFor(playerPosition: GridCoord): SoundEventContext {
        val dx = (qube.coord.x - playerPosition.x).toFloat()
        val dz = (qube.coord.z - playerPosition.z).toFloat()
        return SoundEventContext(emitterDistance = hypot(dx, dz))
    }
}
