package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.mspgllc.iqpuchin.board.Qube
import com.mspgllc.iqpuchin.board.QubeMotion
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a single QUBE toppling from [Qube.previousCoord] to [Qube.coord].
 *
 * This computes the QUBE's 8 corners as real 3D points rotated about the
 * shared edge between the two cells, then projects each corner through
 * the same [IsoProjection] used for the floor -- not by rotating a flat
 * icon. The rotation pivot is always placed as if the QUBE exactly fills
 * one cell (half-extent 0.5), which is what makes every topple advance
 * exactly one grid unit with a flush landing; [RenderConfig.QUBE_VISUAL_SCALE]
 * is applied afterward as a cosmetic shrink toward the shape's own
 * center, never touching that pivot math.
 *
 * Which physical face of the cube is "the top" and "the face turned
 * toward the camera" changes *during* a topple -- e.g. at the instant a
 * topple finishes, what was the cube's back face is now its top, and
 * what was its top face is now turned toward the camera. Earlier this
 * class rendered a fixed group of corners as "the top face" regardless
 * of rotation angle, which is what made a mid/late-rotation (and the
 * settled, fully-landed) QUBE look like two flat vertical panels plus a
 * bottom instead of a cube. The fix: at every frame, compute each
 * rotating face's current outward normal and pick whichever face is
 * actually pointing up (drawn brightest, "top" role) and whichever
 * remaining face is pointing most toward the camera (drawn mid-tone,
 * "front" role) -- this is standard back-face culling by normal
 * direction. The +X face is unaffected by this rotation (the pivot axis
 * runs parallel to it) so it is always the visible darkest side face; -X
 * and the "currently facing away" candidate are never drawn at all,
 * which is the culling. The three chosen faces are then depth-sorted
 * (farthest first) before drawing, as a defensive measure against any
 * edge-on overlap during rotation.
 *
 * VISUAL-01: the three faces are now painted as a cardboard shipping box
 * (tape band on top, a small "grumpy" face on whichever face is
 * currently playing the front role, a shipping mark on the always-dark
 * side face) instead of flat blue. All of that is drawn as decoration
 * *on top of* the already-computed face quads below -- none of the
 * rotation/face-selection/depth-sort math above is touched, so the
 * QUBE's motion and shape are exactly as before.
 *
 * QUBE-DAMAGE-VISUAL-01: [Qube.durability] already existed (CATPUNCH-01)
 * as pure logic state a cat punch decrements, completely independent of
 * [coord]/[previousCoord]/rotation -- this round only makes the
 * durability==1 state read as an obvious "ベコッ" at a glance instead of
 * the small corner patch CATPUNCH-01 first drew. Everything here is
 * still decoration layered on the same already-computed corner points
 * (`s`, `upQuad`/`frontQuad`/`rightQuad`) -- no new state, no change to
 * the rotation/pivot/normal-selection/depth-sort math, and nothing here
 * is read anywhere else (durability's own value, movement, collision,
 * MARK/ACTIVATE/CaptureSystem are all untouched). The one exception is
 * [DENTED_SQUASH_X]/[DENTED_SQUASH_Y] below, a small *uniform* extra
 * scale applied to every one of the 8 corner points together (exactly
 * where [RenderConfig.QUBE_VISUAL_SCALE]'s own cosmetic shrink already
 * happens) -- since it moves all 8 shared corners together, adjacent
 * faces never separate/gap, so the cube mesh stays sealed; it reads as
 * "this box got knocked slightly out of true," not a hole in the shape.
 */
class QubeRenderer {

    private companion object {
        /**
         * QUBE-DAMAGE-VISUAL-01: applied only while [Qube.durability] == 1,
         * on top of [RenderConfig.QUBE_VISUAL_SCALE]'s existing per-frame
         * shrink -- a mild, deliberately asymmetric squash (narrower than
         * tall) so a dented QUBE's whole silhouette reads as slightly
         * knocked-in even at a glance, not just the front-face patch.
         * Small enough that the box never reads as broken/unrecognizable
         * (per the explicit "don't crush it past recognition" limit).
         */
        const val DENTED_SQUASH_X = 0.92f
        const val DENTED_SQUASH_Y = 1.03f
    }

    private enum class Face { TOP, BOTTOM, FRONT, BACK }

    private val brightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 164, 118) }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(168, 128, 82) }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(128, 94, 58) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 40, 24)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(222, 206, 174) }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 24, 16)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 68, 42)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // QUBE-DAMAGE-VISUAL-01: layered to fake real inward depth with flat
    // 2D fills alone (no actual geometry is pushed inward -- see the
    // class doc's note on why the mesh itself stays untouched). dentRim
    // is a lighter ring right at the pit's edge (a punched-up lip
    // catching light), dentCore is the darkest patch at the pit's
    // deepest point, and dentFillPaint (kept from CATPUNCH-01) sits
    // between them as the pit's sloped wall.
    private val dentRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(214, 184, 140)
        style = Paint.Style.FILL
    }
    private val dentFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(96, 70, 44)
        style = Paint.Style.FILL
    }
    private val dentCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 40, 24)
        style = Paint.Style.FILL
    }
    private val dentCrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 26, 14)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    /** Cosine-based ease-in-out: slow start, fast middle, slow finish. */
    private fun easeInOut(linearT: Float): Float =
        (1f - cos(linearT * Math.PI.toFloat())) / 2f

    fun draw(canvas: Canvas, qube: Qube, motion: QubeMotion, projection: IsoProjection) {
        val easedT = easeInOut(motion.rotationProgress())
        val dz = qube.direction.dz.toFloat()
        val theta = easedT * (Math.PI.toFloat() / 2f) * dz // signed 0..90 degrees, in radians

        val half = 0.5f
        val fromX = qube.previousCoord.x.toFloat()
        val fromZ = qube.previousCoord.z.toFloat()
        val pivotZ = half * dz
        val cosT = cos(theta)
        val sinT = sin(theta)

        fun cornerScreen(localX: Float, localY: Float, localZ: Float): FloatArray {
            val relY = localY
            val relZ = localZ - pivotZ
            val worldY = cosT * relY - sinT * relZ
            val relZ2 = sinT * relY + cosT * relZ
            val worldZ = fromZ + pivotZ + relZ2
            val worldX = fromX + localX
            return projection.toScreen(worldX, worldZ, worldY)
        }

        val bLL = cornerScreen(-half, 0f, -half)
        val bLR = cornerScreen(half, 0f, -half)
        val bFL = cornerScreen(-half, 0f, half)
        val bFR = cornerScreen(half, 0f, half)
        val tLL = cornerScreen(-half, 2f * half, -half)
        val tLR = cornerScreen(half, 2f * half, -half)
        val tFL = cornerScreen(-half, 2f * half, half)
        val tFR = cornerScreen(half, 2f * half, half)

        // Cosmetic-only shrink toward the shared center -- see class doc.
        // QUBE-DAMAGE-VISUAL-01: while durability==1, an extra small
        // anisotropic factor rides along on the same transform (applied
        // to every one of the 8 shared corners together, so the mesh
        // never gaps) -- see DENTED_SQUASH_X/Y's own doc.
        val dented = qube.durability == 1
        val raw = arrayOf(bLL, bLR, bFL, bFR, tLL, tLR, tFL, tFR)
        val centerX = raw.sumOf { it[0].toDouble() }.toFloat() / raw.size
        val centerY = raw.sumOf { it[1].toDouble() }.toFloat() / raw.size
        val vScale = RenderConfig.QUBE_VISUAL_SCALE
        val scaleX = vScale * (if (dented) DENTED_SQUASH_X else 1f)
        val scaleY = vScale * (if (dented) DENTED_SQUASH_Y else 1f)
        val s = Array(raw.size) { i ->
            floatArrayOf(centerX + (raw[i][0] - centerX) * scaleX, centerY + (raw[i][1] - centerY) * scaleY)
        }
        // s indices: 0=bLL 1=bLR 2=bFL 3=bFR 4=tLL 5=tLR 6=tFL 7=tFR

        // Outward normals of the four faces this rotation actually
        // affects (TOP/BOTTOM/FRONT/BACK all live in the Y-Z plane the
        // pivot rotates). The +X (right) face's normal is untouched by a
        // rotation about an axis parallel to X, so it needs no normal
        // check -- it is simply always the visible dark side face.
        val normalY = mapOf(Face.TOP to cosT, Face.BOTTOM to -cosT, Face.FRONT to -sinT, Face.BACK to sinT)
        val normalZ = mapOf(Face.TOP to sinT, Face.BOTTOM to -sinT, Face.FRONT to cosT, Face.BACK to -cosT)

        val upFace = normalY.maxByOrNull { it.value }!!.key
        val frontFace = normalZ.filterKeys { it != upFace }.maxByOrNull { it.value }!!.key

        fun quadOf(face: Face): Array<FloatArray> = when (face) {
            Face.TOP -> arrayOf(s[4], s[5], s[7], s[6])    // tLL,tLR,tFR,tFL
            Face.BOTTOM -> arrayOf(s[0], s[1], s[3], s[2]) // bLL,bLR,bFR,bFL
            Face.FRONT -> arrayOf(s[2], s[3], s[7], s[6])  // bFL,bFR,tFR,tFL
            Face.BACK -> arrayOf(s[1], s[0], s[4], s[5])   // bLR,bLL,tLL,tLR
        }

        val rightQuad = arrayOf(s[1], s[5], s[7], s[3]) // bLR,tLR,tFR,bFR -- always the +X face
        val upQuad = quadOf(upFace)
        val frontQuad = quadOf(frontFace)

        fun avgScreenY(quad: Array<FloatArray>) = quad.sumOf { it[1].toDouble() }.toFloat() / quad.size

        val drawOrder = listOf(
            brightPaint to upQuad,
            midPaint to frontQuad,
            darkPaint to rightQuad
        ).sortedBy { (_, quad) -> avgScreenY(quad) } // farther/higher (smaller screen Y) drawn first

        for ((paint, quad) in drawOrder) {
            drawFace(canvas, paint, quad)
        }

        // VISUAL-01 cardboard decoration, drawn on top of the already
        // rotated/projected quads above -- purely cosmetic, feeds back
        // into none of the geometry/face-selection/sort logic above.
        // QUBE-DAMAGE-VISUAL-01: the tape band and face swap to damaged
        // variants while dented; the dent patch itself (drawn last, same
        // as CATPUNCH-01's placement) is now the large, unmistakable
        // "ベコッ" -- see drawDent.
        drawTapeBand(canvas, upQuad, dented)
        if (dented) drawHurtEyes(canvas, frontQuad) else drawGrumpyEyes(canvas, frontQuad)
        drawShippingMark(canvas, rightQuad)

        // durability==1 is exactly the state between "just hit, not yet
        // broken" and "destroyed" (0, which is removed from `qubes`
        // before ever reaching this draw call again) -- this condition
        // is true for every single frame durability stays 1, not just
        // the instant of the hit, so the dent persists through however
        // many more topples it takes for the second punch to land.
        if (dented) {
            drawDent(canvas, frontQuad)
        }
    }

    private fun drawFace(canvas: Canvas, paint: Paint, pts: Array<FloatArray>) {
        val path = Path().apply {
            moveTo(pts[0][0], pts[0][1])
            for (i in 1 until pts.size) lineTo(pts[i][0], pts[i][1])
            close()
        }
        canvas.drawPath(path, paint)
        canvas.drawPath(path, outline)
    }

    /** Bilinear point within a face quad, corners assumed in the same
     * cyclic order [drawFace] connects them in: index0=(u=0,v=0),
     * index1=(u=1,v=0), index2=(u=1,v=1), index3=(u=0,v=1). Lets
     * decorations stay glued to a face's already-rotated/projected
     * corners without any new corner/rotation math of their own. */
    private fun quadPoint(quad: Array<FloatArray>, u: Float, v: Float): FloatArray {
        val edge0 = lerp(quad[0], quad[1], u)
        val edge1 = lerp(quad[3], quad[2], u)
        return lerp(edge0, edge1, v)
    }

    private fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)

    /** A single tape band across whichever face is currently playing the
     * "up" role -- a simple, always-present "sealed box" cue for this
     * first static visibility pass. QUBE-DAMAGE-VISUAL-01: while [dented],
     * the band's two ends are pulled to different v-heights (still the
     * same [quadPoint] glue-to-face mechanism, just asymmetric u/v
     * inputs) so it reads as knocked crooked/half-peeled instead of a
     * perfectly straight seal. */
    private fun drawTapeBand(canvas: Canvas, upQuad: Array<FloatArray>, dented: Boolean) {
        val topV = if (dented) 0.32f else 0.4f
        val bottomV = if (dented) 0.62f else 0.6f
        val a = quadPoint(upQuad, 0f, topV)
        val b = quadPoint(upQuad, 1f, if (dented) 0.46f else topV)
        val c = quadPoint(upQuad, 1f, if (dented) 0.74f else bottomV)
        val d = quadPoint(upQuad, 0f, bottomV)
        val path = Path().apply {
            moveTo(a[0], a[1]); lineTo(b[0], b[1]); lineTo(c[0], c[1]); lineTo(d[0], d[1]); close()
        }
        canvas.drawPath(path, tapePaint)
    }

    /** Two short "slightly mean" eye strokes on whichever face is
     * currently playing the "front" role -- the HP2 default. */
    private fun drawGrumpyEyes(canvas: Canvas, frontQuad: Array<FloatArray>) {
        drawEyeStroke(canvas, frontQuad, 0.26f, 0.7f, 0.42f, 0.58f)
        drawEyeStroke(canvas, frontQuad, 0.74f, 0.7f, 0.58f, 0.58f)
    }

    /** QUBE-DAMAGE-VISUAL-01: the durability==1 face -- each eye becomes a
     * small X (two crossed strokes) instead of a single angled line, the
     * simplest possible "dazed/hurt" read in this same flat-line style,
     * glued to [frontQuad] the same way every other decoration here is. */
    private fun drawHurtEyes(canvas: Canvas, frontQuad: Array<FloatArray>) {
        drawEyeX(canvas, frontQuad, 0.22f, 0.42f, 0.10f)
        drawEyeX(canvas, frontQuad, 0.78f, 0.42f, 0.10f)
    }

    private fun drawEyeX(canvas: Canvas, quad: Array<FloatArray>, cu: Float, cv: Float, r: Float) {
        val p1 = quadPoint(quad, cu - r, cv - r)
        val p2 = quadPoint(quad, cu + r, cv + r)
        val p3 = quadPoint(quad, cu - r, cv + r)
        val p4 = quadPoint(quad, cu + r, cv - r)
        canvas.drawLine(p1[0], p1[1], p2[0], p2[1], eyePaint)
        canvas.drawLine(p3[0], p3[1], p4[0], p4[1], eyePaint)
    }

    private fun drawEyeStroke(canvas: Canvas, quad: Array<FloatArray>, uOuter: Float, vTop: Float, uInner: Float, vBottom: Float) {
        val p1 = quadPoint(quad, uOuter, vTop)
        val p2 = quadPoint(quad, uInner, vBottom)
        canvas.drawLine(p1[0], p1[1], p2[0], p2[1], eyePaint)
    }

    /** A small shipping-label-style mark on the always-visible dark side
     * face. */
    private fun drawShippingMark(canvas: Canvas, rightQuad: Array<FloatArray>) {
        val a = quadPoint(rightQuad, 0.35f, 0.4f)
        val b = quadPoint(rightQuad, 0.65f, 0.4f)
        val c = quadPoint(rightQuad, 0.65f, 0.6f)
        val d = quadPoint(rightQuad, 0.35f, 0.6f)
        val path = Path().apply {
            moveTo(a[0], a[1]); lineTo(b[0], b[1]); lineTo(c[0], c[1]); lineTo(d[0], d[1]); close()
        }
        canvas.drawPath(path, markPaint)
    }

    /**
     * QUBE-DAMAGE-VISUAL-01: the durability==1 "ベコッ" -- large enough,
     * on its own, to read as damage from across the board (per the
     * explicit "must be obvious at a glance, not a small scratch"
     * requirement), covering most of the lower front face so it never
     * competes with [drawHurtEyes] above it. Three nested, progressively
     * darker quads (rim -> wall -> core, all glued to [frontQuad] via the
     * same [quadPoint] mechanism every decoration here uses) fake real
     * inward depth purely with flat 2D fills -- no actual geometry is
     * pushed inward, so the cube mesh itself (shared corners with the
     * adjacent top/side faces) never gaps or breaks. Four crease lines
     * radiate from the pit's center to the rim in a rough asterisk, the
     * same "impact crack" read the original CATPUNCH-01 version used,
     * just scaled up to match the much larger dent.
     */
    private fun drawDent(canvas: Canvas, frontQuad: Array<FloatArray>) {
        fun ring(inset: Float): Array<FloatArray> = arrayOf(
            quadPoint(frontQuad, 0.18f + inset, 0.42f + inset * 0.7f),
            quadPoint(frontQuad, 0.82f - inset, 0.42f + inset * 0.7f),
            quadPoint(frontQuad, 0.82f - inset, 0.86f - inset * 0.7f),
            quadPoint(frontQuad, 0.18f + inset, 0.86f - inset * 0.7f)
        )

        fun fillQuad(pts: Array<FloatArray>, paint: Paint) {
            val path = Path().apply {
                moveTo(pts[0][0], pts[0][1])
                for (i in 1 until pts.size) lineTo(pts[i][0], pts[i][1])
                close()
            }
            canvas.drawPath(path, paint)
        }

        fillQuad(ring(0f), dentRimPaint)
        fillQuad(ring(0.09f), dentFillPaint)
        fillQuad(ring(0.20f), dentCorePaint)

        val center = quadPoint(frontQuad, 0.5f, 0.64f)
        val crackEnds = listOf(
            0.30f to 0.46f, 0.70f to 0.46f,
            0.24f to 0.80f, 0.76f to 0.80f
        )
        for ((u, v) in crackEnds) {
            val end = quadPoint(frontQuad, u, v)
            canvas.drawLine(center[0], center[1], end[0], end[1], dentCrackPaint)
        }
    }
}
