package com.mspgllc.iqpuchin.render

import com.mspgllc.iqpuchin.board.Direction

/**
 * QUBE-BREAK-VISUAL-01: a short-lived, render-only snapshot of a QUBE at
 * the instant its second cat punch destroys it (durability 1 -> 0).
 *
 * GameView captures one of these in [performPunch]-equivalent code
 * *before* the real [com.mspgllc.iqpuchin.board.Qube] is removed from its
 * `qubes` list -- by the time this object exists, the logical QUBE is
 * already gone from that list, so collision (GameStateController.
 * checkCollision reads `qubes.map { it.qube.coord }`), MARK/ACTIVATE
 * (CaptureSystem/MarkController only ever look at `qubes`), movement, and
 * "how many QUBEs remain" all never see this object. It carries no
 * GridCoord of its own and is never written back into any board-logic
 * structure -- it exists purely so [QubeRenderer.drawBroken] can redraw
 * the QUBE's exact last pose (previous/current cell, direction, and the
 * toppling rotation progress at the moment it broke, so the break visual
 * starts from exactly what was on screen the instant before, with no
 * pop) plus a short crumble/fragment animation on top.
 *
 * [elapsedMs] is advanced by GameView's existing per-frame deltaMs loop,
 * the same pattern already used by [TimedCosmeticFlag] elsewhere in this
 * codebase -- this class has no `update()` of its own, it is a plain
 * data holder, not a second game-logic clock.
 */
class BrokenQubeVisual(
    val previousCoordX: Int,
    val previousCoordZ: Int,
    val coordX: Int,
    val coordZ: Int,
    val direction: Direction,
    val rotationProgressAtBreak: Float
) {
    companion object {
        /** QUBE-BREAK-VISUAL-01 spec: ~200ms, deliberately short so the
         * break never stalls play -- see the round's explicit "150-250ms,
         * no long animation" requirement. */
        const val DURATION_MS = 200L
    }

    var elapsedMs: Long = 0L

    /** 0f at the instant of destruction, 1f once the break animation has
     * fully played out. GameView removes this object from its list once
     * this reaches 1f; QubeRenderer never needs to know that -- it just
     * draws whatever progress it's given. */
    fun progress(): Float = (elapsedMs.toFloat() / DURATION_MS.toFloat()).coerceIn(0f, 1f)

    fun finished(): Boolean = elapsedMs >= DURATION_MS
}
