package com.mspgllc.iqpuchin.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.mspgllc.iqpuchin.board.Direction

/**
 * AZUSAN-PLAYER-01: draws the PLAYER marker as one of Azusan's eight
 * official sprite images (see [PlayerSpriteSheet]/[PlayerSpriteState]),
 * replacing the previous Canvas Path/Paint vector "Poi" design entirely.
 * Still purely presentational -- like BoardRenderer/QubeRenderer, it
 * never touches game state and is driven entirely by values GameView
 * hands it each frame; GridCoord -> IsoProjection.toScreen() -> screen
 * position is unchanged, this class only decides which bitmap to draw
 * there and how to size/anchor it.
 *
 * Per the explicit architecture request, THIS class -- not GameView --
 * resolves which of the eight sprites is currently appropriate, reading
 * only the raw signals GameView already tracks (last move direction, and
 * three short cosmetic windows -- walk/punch/hit -- GameView maintains
 * via [TimedCosmeticFlag] or, for HIT, [PoiHitReaction]). None of those
 * signals are new game state; GameView still owns every one of them for
 * its own reasons (the SE funnel, the "HIT x{n}" debug banner, etc.) --
 * this only reads them.
 *
 * Priority when more than one window is active at once, per spec:
 * HIT > RECOVER > CAT_PUNCH > WALK > IDLE. HIT and RECOVER are two
 * phases of the same [hitVisualActive] window (see [resolveState]).
 *
 * Sizing/anchoring: each pose's source PNG has a different amount of
 * transparent padding baked in (see [AzusanPose.contentBox]), so two
 * poses drawn at the same literal bitmap scale would appear as
 * different sizes with different "feet" positions. [draw] instead
 * normalizes every pose so its own content box's height maps to
 * [RenderConfig.PLAYER_SPRITE_TARGET_HEIGHT_PX] (scaled) and its content
 * box's bottom-center lands exactly on the projected board position --
 * so switching sprites never makes Azusan jump or change size, even
 * though the underlying images are not uniformly framed.
 */
class PlayerRenderer(context: Context) {

    private companion object {
        /** AZUSAN-PLAYER-01: how far into the HIT/RECOVER cosmetic
         * window (see [hitVisualElapsedMs]) the HIT sprite shows before
         * switching to RECOVER -- purely a sprite-selection split, not a
         * change to any existing HIT timing (GameStateController's
         * HIT_DURATION_MS and PoiHitReaction's own DURATION_MS are both
         * untouched). */
        const val HIT_SPRITE_PHASE_MS = 160L
    }

    private val spriteSheet = PlayerSpriteSheet(context)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Reused every frame (mutated via [RectF.set], never reallocated)
     * so drawing Azusan doesn't add a per-frame allocation to the render
     * loop -- avoids unnecessary GC pressure on real hardware. */
    private val reusableDst = RectF()

    /**
     * VIRTUAL-STICK-ROTATIONAL-PROTOTYPE-01: [gridX]/[gridZ] are now
     * continuous Float grid coordinates instead of a discrete [GridCoord]
     * -- the only change this round makes here. Everything below already
     * worked in Float internally (the old call site was just
     * `position.x.toFloat()`/`position.z.toFloat()`), so a continuous
     * position glides smoothly instead of jumping cell-to-cell; nothing
     * about how a pose is chosen or drawn/sized/anchored changes.
     */
    fun draw(
        canvas: Canvas,
        projection: IsoProjection,
        gridX: Float,
        gridZ: Float,
        scale: Float,
        lastMoveDirection: Direction,
        walkVisualActive: Boolean,
        punchVisualActive: Boolean,
        hitVisualActive: Boolean,
        hitVisualElapsedMs: Long
    ) {
        val state = resolveState(
            lastMoveDirection, walkVisualActive, punchVisualActive, hitVisualActive, hitVisualElapsedMs
        )
        val pose = spriteSheet[state]
        val p = projection.toScreen(gridX, gridZ)
        drawPose(canvas, pose, p[0], p[1], scale)
    }

    /** HIT/RECOVER take priority over everything (the player just got
     * crushed, that's always what's most important to show); within
     * that window, the first [HIT_SPRITE_PHASE_MS] is HIT, the rest is
     * RECOVER. CAT_PUNCH beats WALK (a punch mid-step still reads as a
     * punch); WALK only shows while its own short window is active;
     * otherwise IDLE. */
    private fun resolveState(
        lastMoveDirection: Direction,
        walkVisualActive: Boolean,
        punchVisualActive: Boolean,
        hitVisualActive: Boolean,
        hitVisualElapsedMs: Long
    ): PlayerSpriteState = when {
        hitVisualActive -> {
            if (hitVisualElapsedMs < HIT_SPRITE_PHASE_MS) PlayerSpriteState.HIT else PlayerSpriteState.RECOVER
        }
        punchVisualActive -> PlayerSpriteState.CAT_PUNCH
        walkVisualActive -> PlayerSpriteState.walkFor(lastMoveDirection)
        else -> PlayerSpriteState.IDLE
    }

    /**
     * Scales the whole bitmap uniformly so [AzusanPose.contentBox]'s own
     * height equals the target on-screen height, then positions it so
     * that box's bottom-center lands exactly at ([anchorX], [anchorY])
     * -- the same projected point BoardRenderer centers that cell's
     * floor tile on, i.e. "Azusan's feet are standing on this cell".
     */
    private fun drawPose(canvas: Canvas, pose: AzusanPose, anchorX: Float, anchorY: Float, scale: Float) {
        val box = pose.contentBox
        val targetContentHeightPx = RenderConfig.PLAYER_SPRITE_TARGET_HEIGHT_PX * scale
        val renderScale = targetContentHeightPx / box.height()

        val dstWidth = pose.bitmap.width * renderScale
        val dstHeight = pose.bitmap.height * renderScale

        val contentBottomCenterX = (box.left + box.right) / 2f * renderScale
        val contentBottomCenterY = box.bottom * renderScale

        val dstLeft = anchorX - contentBottomCenterX
        val dstTop = anchorY - contentBottomCenterY

        reusableDst.set(dstLeft, dstTop, dstLeft + dstWidth, dstTop + dstHeight)
        canvas.drawBitmap(pose.bitmap, null, reusableDst, bitmapPaint)
    }
}
