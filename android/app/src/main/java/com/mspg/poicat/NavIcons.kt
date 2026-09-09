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
                // Rounded-square badge.
                moveTo(9f, 4f)
                lineTo(15f, 4f)
                quadTo(20f, 4f, 20f, 9f)
                lineTo(20f, 15f)
                quadTo(20f, 20f, 15f, 20f)
                lineTo(9f, 20f)
                quadTo(4f, 20f, 4f, 15f)
                lineTo(4f, 9f)
                quadTo(4f, 4f, 9f, 4f)
                close()
                // Checkmark ribbon cutout.
                moveTo(7.3f, 12f)
                lineTo(9.7f, 14.4f)
                lineTo(16.8f, 7.3f)
                lineTo(18.2f, 8.7f)
                lineTo(9.9f, 16.8f)
                lineTo(8.5f, 15.4f)
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
                // Body.
                moveTo(7.5f, 6f)
                lineTo(16.5f, 6f)
                quadTo(19.5f, 6f, 19.5f, 9f)
                lineTo(19.5f, 17f)
                quadTo(19.5f, 20f, 16.5f, 20f)
                lineTo(7.5f, 20f)
                quadTo(4.5f, 20f, 4.5f, 17f)
                lineTo(4.5f, 9f)
                quadTo(4.5f, 6f, 7.5f, 6f)
                close()
                // Top-left tab.
                moveTo(7.8f, 4f)
                lineTo(9.2f, 4f)
                lineTo(9.2f, 7f)
                lineTo(7.8f, 7f)
                close()
                // Top-right tab.
                moveTo(14.8f, 4f)
                lineTo(16.2f, 4f)
                lineTo(16.2f, 7f)
                lineTo(14.8f, 7f)
                close()
                // Header-band cutout.
                moveTo(6f, 9.5f)
                lineTo(18f, 9.5f)
                lineTo(18f, 10.8f)
                lineTo(6f, 10.8f)
                close()
                // Date-marker cutout.
                moveTo(8.5f, 14f)
                lineTo(10f, 14f)
                lineTo(10f, 15.5f)
                lineTo(8.5f, 15.5f)
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

    /** BB's face — a round black-cat head with two ears (each with a small
     * inner-ear cutout) and heavy-lidded, slightly bored eyes. No nose or
     * whiskers: at nav-icon size those would just blur into noise, so the
     * silhouette + ears + sleepy eyes alone carry "it's BB, and it's a cat".
     * Layered as 3 passes (ears, then head+eyes on top, then pupils on top
     * of that) so the head cleanly covers the ear roots and the pupils sit
     * inside the eye cutouts, without any pass punching an unwanted hole
     * through another. Deliberately not a generic cat mark — this is the
     * one icon among the 5 allowed a touch more character, since 猫AI is
     * this app's one character-branded tab. */
    val CatAi: ImageVector
        get() {
            _catAi?.let { return it }
            val built = ImageVector.Builder(
                name = "NavCatAi",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).path(
                // Ears, each with a small inner-ear cutout.
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(4f, 3f)
                lineTo(6.5f, 10f)
                lineTo(10f, 8.5f)
                close()
                moveTo(5.3f, 5.2f)
                lineTo(6.8f, 8.8f)
                lineTo(8.8f, 8f)
                close()
                moveTo(20f, 3f)
                lineTo(17.5f, 10f)
                lineTo(14f, 8.5f)
                close()
                moveTo(18.7f, 5.2f)
                lineTo(17.2f, 8.8f)
                lineTo(15.2f, 8f)
                close()
            }.path(
                // Head, on top of the ear roots, with heavy-lidded eye cutouts.
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(9.5f, 8f)
                lineTo(14.5f, 8f)
                quadTo(18.5f, 8f, 18.5f, 12f)
                lineTo(18.5f, 17f)
                quadTo(18.5f, 21f, 14.5f, 21f)
                lineTo(9.5f, 21f)
                quadTo(5.5f, 21f, 5.5f, 17f)
                lineTo(5.5f, 12f)
                quadTo(5.5f, 8f, 9.5f, 8f)
                close()
                // Left eye: flat heavy lid on top, rounder underneath.
                moveTo(8.3f, 13.6f)
                quadTo(9.8f, 12.6f, 11.2f, 13.6f)
                quadTo(9.8f, 15.2f, 8.3f, 13.6f)
                close()
                // Right eye, mirrored.
                moveTo(15.8f, 13.6f)
                quadTo(14.2f, 12.6f, 12.8f, 13.6f)
                quadTo(14.2f, 15.2f, 15.8f, 13.6f)
                close()
            }.path(
                // Pupils, sitting inside the eye cutouts, on top of everything.
                fill = SolidColor(Color.Black),
            ) {
                moveTo(9.2f, 13.9f)
                lineTo(10f, 13.9f)
                lineTo(10f, 14.7f)
                lineTo(9.2f, 14.7f)
                close()
                moveTo(14f, 13.9f)
                lineTo(14.8f, 13.9f)
                lineTo(14.8f, 14.7f)
                lineTo(14f, 14.7f)
                close()
            }.build()
            _catAi = built
            return built
        }
}
