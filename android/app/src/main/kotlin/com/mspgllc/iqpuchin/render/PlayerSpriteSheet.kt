package com.mspgllc.iqpuchin.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.mspgllc.iqpuchin.R
import com.mspgllc.iqpuchin.board.Direction

/**
 * AZUSAN-PLAYER-01: every distinct visual pose PlayerRenderer can show for
 * the PLAYER marker (アズさん), independent of the [PlayerSpriteSheet] that
 * maps each one to a decoded [Bitmap]. Adding a state here does not change
 * any game rule -- it's purely "which picture", picked by PlayerRenderer
 * from existing GameView signals (see [PlayerRenderer.draw]).
 */
enum class PlayerSpriteState {
    IDLE, WALK_NORTH, WALK_SOUTH, WALK_EAST, WALK_WEST, CAT_PUNCH, HIT, RECOVER;

    companion object {
        fun walkFor(direction: Direction): PlayerSpriteState = when (direction) {
            Direction.NORTH -> WALK_NORTH
            Direction.SOUTH -> WALK_SOUTH
            Direction.EAST -> WALK_EAST
            Direction.WEST -> WALK_WEST
        }
    }
}

/**
 * One decoded Azusan pose plus its pre-computed opaque-content bounding
 * box, in the bitmap's own pixel coordinates. The source PNGs are full
 * transparent-background artwork with differing amounts of padding baked
 * in per pose (a crouching pose's canvas isn't framed the same as a
 * standing one) -- [contentBox] is what lets [PlayerRenderer] normalize
 * every pose to the same on-screen character size and the same "feet"
 * anchor point (contentBox's bottom-center), so switching poses never
 * makes Azusan jump or change size on screen.
 *
 * The box is computed once here (a single pixel scan over an already-
 * decoded bitmap, at load time), never per frame -- [PlayerRenderer]
 * only ever reads the cached [contentBox] afterward.
 */
class AzusanPose(val bitmap: Bitmap) {
    val contentBox: Rect = computeOpaqueBounds(bitmap)

    private companion object {
        /** Every STRIDE-th pixel in each direction is enough to find an
         * accurate-to-a-few-pixels bounding box on artwork this size,
         * at a fraction of the cost of scanning every pixel -- fine
         * since anchor/size precision only needs to be visually exact,
         * not literally pixel-exact. */
        const val STRIDE = 3

        fun computeOpaqueBounds(bitmap: Bitmap): Rect {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            var left = w
            var right = -1
            var top = h
            var bottom = -1
            var y = 0
            while (y < h) {
                val rowStart = y * w
                var x = 0
                while (x < w) {
                    val alpha = pixels[rowStart + x] ushr 24
                    if (alpha > 10) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                    x += STRIDE
                }
                y += STRIDE
            }
            if (right < left || bottom < top) return Rect(0, 0, w, h)
            return Rect(left, top, right + 1, bottom + 1)
        }
    }
}

/**
 * Loads and caches all eight Azusan pose bitmaps exactly once (in the
 * constructor), for [PlayerRenderer] to reuse every frame afterward --
 * never re-decoded mid-game, per the explicit "no per-frame Bitmap
 * decode" requirement. Uses [BitmapFactory.Options.inScaled] = false
 * because these PNGs live in res/drawable-nodpi (deliberately, so
 * Android's own density-based auto-scaling never runs on them) --
 * GameView already computes its own [displayScale] for every other
 * renderer, and Azusan's sprites are sized through that same manual
 * pipeline (see [PlayerRenderer]), not Android's density system.
 */
class PlayerSpriteSheet(context: Context) {

    private val poses: Map<PlayerSpriteState, AzusanPose> = mapOf(
        PlayerSpriteState.IDLE to load(context, R.drawable.azusan_idle),
        PlayerSpriteState.WALK_SOUTH to load(context, R.drawable.azusan_walk_s),
        PlayerSpriteState.WALK_NORTH to load(context, R.drawable.azusan_walk_n),
        PlayerSpriteState.WALK_WEST to load(context, R.drawable.azusan_walk_w),
        PlayerSpriteState.WALK_EAST to load(context, R.drawable.azusan_walk_e),
        PlayerSpriteState.CAT_PUNCH to load(context, R.drawable.azusan_cat_punch),
        PlayerSpriteState.HIT to load(context, R.drawable.azusan_hit),
        PlayerSpriteState.RECOVER to load(context, R.drawable.azusan_recover)
    )

    operator fun get(state: PlayerSpriteState): AzusanPose =
        poses.getValue(state)

    private fun load(context: Context, resId: Int): AzusanPose {
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = BitmapFactory.decodeResource(context.resources, resId, options)
        return AzusanPose(bitmap)
    }
}
