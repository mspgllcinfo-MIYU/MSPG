package com.mspg.poicat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * BottomTabBar's 5 nav icons, hand-drawn as a matched set (shared 24x24
 * viewport, shared filled-silhouette-with-cutouts technique — the same
 * single-tint-friendly approach androidx.compose.material.icons.Icons
 * .Default.Face already uses for its eye holes) so they read as one
 * family instead of 4 generic Material glyphs plus 1 custom character.
 * Every path's own fill color is an unused placeholder: BottomTabBar
 * always renders these via Icon(tint = ...), which recolors the whole
 * vector regardless of what's set here.
 *
 * Cached the same way Material's generated icons are (a nullable backing
 * field built once on first access) rather than rebuilding the path data
 * on every recomposition.
 */
object NavIcons {
    private var _home: ImageVector? = null
    private var _poi: ImageVector? = null
    private var _calendar: ImageVector? = null
    private var _memo: ImageVector? = null
    private var _catAi: ImageVector? = null

    /** A house with two small peaks in the roofline — a light cat-ear echo,
     * not a literal cat, so it still reads as "home" first. */
    val Home: ImageVector
        get() {
            _home?.let { return it }
            val built = ImageVector.Builder(
                name = "NavHome",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                // Roofline with two small peaks (ear echo) and a dip between them.
                moveTo(7f, 3f)
                lineTo(9.5f, 7.5f)
                lineTo(12f, 6f)
                lineTo(14.5f, 7.5f)
                lineTo(17f, 3f)
                lineTo(20.5f, 12.5f)
                lineTo(18f, 12.5f)
                lineTo(18f, 19.5f)
                quadTo(18f, 21f, 16.5f, 21f)
                lineTo(7.5f, 21f)
                quadTo(6f, 21f, 6f, 19.5f)
                lineTo(6f, 12.5f)
                lineTo(3.5f, 12.5f)
                close()
                // Door cutout.
                moveTo(10.5f, 15f)
                lineTo(13.5f, 15f)
                lineTo(13.5f, 21f)
                lineTo(10.5f, 21f)
                close()
            }.build()
            _home = built
            return built
        }

    /** A rounded-square "pad" (a paw-pad nuance rather than a bare circle)
     * with a bold checkmark cut through it. */
    val Poi: ImageVector
        get() {
            _poi?.let { return it }
            val built = ImageVector.Builder(
                name = "NavPoi",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                // Rounded-square badge — rounder than before (radius 6 vs 5),
                // reading more like a single pad than a plain square.
                moveTo(10f, 4f)
                lineTo(14f, 4f)
                quadTo(20f, 4f, 20f, 10f)
                lineTo(20f, 14f)
                quadTo(20f, 20f, 14f, 20f)
                lineTo(10f, 20f)
                quadTo(4f, 20f, 4f, 14f)
                lineTo(4f, 10f)
                quadTo(4f, 4f, 10f, 4f)
                close()
                // Checkmark ribbon cutout.
                moveTo(7.3f, 12f)
                lineTo(9.7f, 14.4f)
                lineTo(16.8f, 7.3f)
                lineTo(18.2f, 8.7f)
                lineTo(9.9f, 16.8f)
                lineTo(8.5f, 15.4f)
                close()
            }.path(
                // Two small toe-bean bumps straddling the top edge — a subtle,
                // abstract paw-pad cue (not a literal 4-toe cartoon print). A
                // separate layer, same reasoning as Memo's pen: drawn on top
                // so it never punches a hole through the badge underneath.
                fill = SolidColor(Color.Black),
            ) {
                moveTo(8.7f, 1.8f)
                lineTo(10.3f, 1.8f)
                quadTo(11f, 1.8f, 11f, 2.5f)
                lineTo(11f, 4.3f)
                quadTo(11f, 5f, 10.3f, 5f)
                lineTo(8.7f, 5f)
                quadTo(8f, 5f, 8f, 4.3f)
                lineTo(8f, 2.5f)
                quadTo(8f, 1.8f, 8.7f, 1.8f)
                close()
                moveTo(13.7f, 1.8f)
                lineTo(15.3f, 1.8f)
                quadTo(16f, 1.8f, 16f, 2.5f)
                lineTo(16f, 4.3f)
                quadTo(16f, 5f, 15.3f, 5f)
                lineTo(13.7f, 5f)
                quadTo(13f, 5f, 13f, 4.3f)
                lineTo(13f, 2.5f)
                quadTo(13f, 1.8f, 13.7f, 1.8f)
                close()
            }.build()
            _poi = built
            return built
        }

    /** A small rounded notebook/calendar (soft corners, two top tabs, a
     * thin header band and a date-marker dot) rather than a rigid grid. */
    val Calendar: ImageVector
        get() {
            _calendar?.let { return it }
            val built = ImageVector.Builder(
                name = "NavCalendar",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                // Body — enlarged to match Home/Poi's viewport coverage
                // (previously a noticeably smaller, weaker-looking silhouette).
                moveTo(7f, 4.5f)
                lineTo(17f, 4.5f)
                quadTo(20.5f, 4.5f, 20.5f, 8f)
                lineTo(20.5f, 17.5f)
                quadTo(20.5f, 21f, 17f, 21f)
                lineTo(7f, 21f)
                quadTo(3.5f, 21f, 3.5f, 17.5f)
                lineTo(3.5f, 8f)
                quadTo(3.5f, 4.5f, 7f, 4.5f)
                close()
                // Top-left tab.
                moveTo(7.3f, 2.5f)
                lineTo(9f, 2.5f)
                lineTo(9f, 5.5f)
                lineTo(7.3f, 5.5f)
                close()
                // Top-right tab.
                moveTo(15f, 2.5f)
                lineTo(16.7f, 2.5f)
                lineTo(16.7f, 5.5f)
                lineTo(15f, 5.5f)
                close()
                // Header-band cutout.
                moveTo(5f, 8f)
                lineTo(19f, 8f)
                lineTo(19f, 9.5f)
                lineTo(5f, 9.5f)
                close()
                // Date-marker cutout.
                moveTo(8f, 13f)
                lineTo(10f, 13f)
                lineTo(10f, 15f)
                lineTo(8f, 15f)
                close()
            }.build()
            _calendar = built
            return built
        }

    /** A small notepad (two ruled-line cutouts) with a pen laid diagonally
     * across it, rather than a single bare pencil. */
    val Memo: ImageVector
        get() {
            _memo?.let { return it }
            val built = ImageVector.Builder(
                name = "NavMemo",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                // Notepad body.
                moveTo(6.2f, 5.5f)
                lineTo(13.3f, 5.5f)
                quadTo(15.5f, 5.5f, 15.5f, 7.7f)
                lineTo(15.5f, 16.8f)
                quadTo(15.5f, 19f, 13.3f, 19f)
                lineTo(6.2f, 19f)
                quadTo(4f, 19f, 4f, 16.8f)
                lineTo(4f, 7.7f)
                quadTo(4f, 5.5f, 6.2f, 5.5f)
                close()
                // Ruled-line cutouts.
                moveTo(6.5f, 9.3f)
                lineTo(12f, 9.3f)
                lineTo(12f, 10.3f)
                lineTo(6.5f, 10.3f)
                close()
                moveTo(6.5f, 12.6f)
                lineTo(10.5f, 12.6f)
                lineTo(10.5f, 13.6f)
                lineTo(6.5f, 13.6f)
                close()
            }.path(
                // Pen: a separate layer drawn on top, so it never punches a
                // hole through the notepad body where the two shapes overlap.
                fill = SolidColor(Color.Black),
            ) {
                moveTo(18.8f, 6.3f)
                lineTo(20.6f, 7.6f)
                lineTo(14.6f, 20f)
                lineTo(13.6f, 20.6f)
                lineTo(13f, 19f)
                close()
            }.build()
            _memo = built
            return built
        }

    /** BB, full-body — not a function icon like Home/Poi/Calendar/Memo, but
     * BB himself: a round, plush-toy-like black cat with a big round head
     * (roughly 6:4 head-to-body), small ears growing naturally out of the
     * head, a short dumpy body, two simple round leg-bumps, a small round
     * tail, and big, wide-set, heavy-lidded eyes — sleepy, faintly annoyed,
     * uninterested, never smiling and never cute-generic-kitten. This app's
     * one character, so BottomTabBar renders it life-sized rather than as a
     * matched function icon (see AppRoot.kt's dedicated BB overlay, which
     * skips the pill background and label the other 4 tabs use). Whiskers
     * and the chest mark from the reference art are dropped — at nav-icon
     * scale they'd just blur into noise. Layered bottom-to-top (body, ears,
     * head, pupils) so each later layer's opaque fill cleanly covers the
     * seam where it meets the one before, the same technique used elsewhere
     * in this file (e.g. Memo's pen, Poi's toe-beans). */
    val CatAi: ImageVector
        get() {
            _catAi?.let { return it }
            val built = ImageVector.Builder(
                name = "NavCatAiFull",
                defaultWidth = 20.dp,
                defaultHeight = 27.dp,
                viewportWidth = 20f,
                viewportHeight = 27f,
            ).path(
                // Body: a short, round torso — no tapering into pointy limbs.
                // Legs and tail are simple round bumps, not pointed shapes.
                fill = SolidColor(Color.Black),
            ) {
                // Torso.
                moveTo(7.5f, 18f)
                lineTo(12.5f, 18f)
                quadTo(15.5f, 18f, 15.5f, 20.5f)
                lineTo(15.5f, 23.5f)
                quadTo(15.5f, 26f, 12.5f, 26f)
                lineTo(7.5f, 26f)
                quadTo(4.5f, 26f, 4.5f, 23.5f)
                lineTo(4.5f, 20.5f)
                quadTo(4.5f, 18f, 7.5f, 18f)
                close()
                // Left leg: a simple round bump, not a pointed foot.
                moveTo(6f, 24.3f)
                lineTo(8.5f, 24.3f)
                quadTo(9.3f, 24.3f, 9.3f, 25.3f)
                lineTo(9.3f, 26f)
                quadTo(9.3f, 27f, 8.3f, 27f)
                lineTo(6.5f, 27f)
                quadTo(5.5f, 27f, 5.5f, 26f)
                lineTo(5.5f, 25.3f)
                quadTo(5.5f, 24.3f, 6f, 24.3f)
                close()
                // Right leg, mirrored.
                moveTo(14f, 24.3f)
                lineTo(11.5f, 24.3f)
                quadTo(10.7f, 24.3f, 10.7f, 25.3f)
                lineTo(10.7f, 26f)
                quadTo(10.7f, 27f, 11.7f, 27f)
                lineTo(13.5f, 27f)
                quadTo(14.5f, 27f, 14.5f, 26f)
                lineTo(14.5f, 25.3f)
                quadTo(14.5f, 24.3f, 14f, 24.3f)
                close()
                // Small round tail.
                moveTo(15f, 19.5f)
                lineTo(16.8f, 19.5f)
                quadTo(17.8f, 19.5f, 17.8f, 20.8f)
                quadTo(17.8f, 22f, 16.8f, 22f)
                lineTo(15f, 22f)
                close()
            }.path(
                // Ears — smaller than before, and rooted close to the head
                // rather than sticking far out, so they read as growing out
                // of the head instead of two separate spikes.
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(3f, 0.5f)
                lineTo(5.2f, 6.5f)
                lineTo(8f, 5.5f)
                close()
                moveTo(3.8f, 2.3f)
                lineTo(5.2f, 5.7f)
                lineTo(6.8f, 5.1f)
                close()
                moveTo(17f, 0.5f)
                lineTo(14.8f, 6.5f)
                lineTo(12f, 5.5f)
                close()
                moveTo(16.2f, 2.3f)
                lineTo(14.8f, 5.7f)
                lineTo(13.2f, 5.1f)
                close()
            }.path(
                // Head — big, wide, and rounded (an oval, not a square), on
                // top of the ear roots and the body's top edge, with big,
                // wide-set, heavy-lidded eye cutouts.
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(6f, 2f)
                lineTo(14f, 2f)
                quadTo(18.5f, 2f, 18.5f, 7f)
                lineTo(18.5f, 12f)
                quadTo(18.5f, 17f, 14f, 17f)
                lineTo(6f, 17f)
                quadTo(1.5f, 17f, 1.5f, 12f)
                lineTo(1.5f, 7f)
                quadTo(1.5f, 2f, 6f, 2f)
                close()
                // Left eye: big, set toward the outer edge of the face, with
                // a heavy flat-ish lid drooping over a rounder underside.
                moveTo(3.8f, 9.3f)
                quadTo(6.2f, 7.6f, 8.5f, 9.3f)
                quadTo(6.2f, 11.4f, 3.8f, 9.3f)
                close()
                // Right eye, mirrored.
                moveTo(16.2f, 9.3f)
                quadTo(13.8f, 7.6f, 11.5f, 9.3f)
                quadTo(13.8f, 11.4f, 16.2f, 9.3f)
                close()
            }.path(
                // Pupils — small, sitting low in the eyes for a bored,
                // uninterested gaze — on top of everything.
                fill = SolidColor(Color.Black),
            ) {
                moveTo(5.6f, 9.6f)
                lineTo(6.7f, 9.6f)
                lineTo(6.7f, 10.7f)
                lineTo(5.6f, 10.7f)
                close()
                moveTo(13.3f, 9.6f)
                lineTo(14.4f, 9.6f)
                lineTo(14.4f, 10.7f)
                lineTo(13.3f, 10.7f)
                close()
            }.build()
            _catAi = built
            return built
        }
}
