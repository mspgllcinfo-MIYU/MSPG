package com.mspgllc.iqpuchin.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.mspgllc.iqpuchin.board.Qube
import com.mspgllc.iqpuchin.board.QubeConfig
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
 */
class QubeRenderer {

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

    // CATPUNCH-01: a caved-in patch drawn on a punched-but-not-yet-broken
    // QUBE's front face (see drawDent). Darker than midPaint so it reads
    // as a shadowed dent, not a decal.
    private val dentFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(96, 70, 44)
        style = Paint.Style.FILL
    }
    private val dentCrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 32, 18)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
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
        val raw = arrayOf(bLL, bLR, bFL, bFR, tLL, tLR, tFL, tFR)
        val centerX = raw.sumOf { it[0].toDouble() }.toFloat() / raw.size
        val centerY = raw.sumOf { it[1].toDouble() }.toFloat() / raw.size
        val vScale = RenderConfig.QUBE_VISUAL_SCALE
        val s = Array(raw.size) { i ->
            floatArrayOf(centerX + (raw[i][0] - centerX) * vScale, centerY + (raw[i][1] - centerY) * vScale)
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
        drawTapeBand(canvas, upQuad)
        drawGrumpyEyes(canvas, frontQuad)
        drawShippingMark(canvas, rightQuad)

        // CATPUNCH-01: a first cat punch dents but doesn't destroy --
        // durability sits strictly between 0 (destroyed, removed from
        // play before it ever reaches this draw call) and its starting
        // value. Drawn last, on the front face, so it's readable from
        // roughly the same angle the player punched from.
        if (qube.durability in 1 until QubeConfig.NORMAL_QUBE_DURABILITY) {
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
     * first static visibility pass. */
    private fun drawTapeBand(canvas: Canvas, upQuad: Array<FloatArray>) {
        val a = quadPoint(upQuad, 0f, 0.4f)
        val b = quadPoint(upQuad, 1f, 0.4f)
        val c = quadPoint(upQuad, 1f, 0.6f)
        val d = quadPoint(upQuad, 0f, 0.6f)
        val path = Path().apply {
            moveTo(a[0], a[1]); lineTo(b[0], b[1]); lineTo(c[0], c[1]); lineTo(d[0], d[1]); close()
        }
        canvas.drawPath(path, tapePaint)
    }

    /** Two short "slightly mean" eye strokes on whichever face is
     * currently playing the "front" role. */
    private fun drawGrumpyEyes(canvas: Canvas, frontQuad: Array<FloatArray>) {
        drawEyeStroke(canvas, frontQuad, 0.26f, 0.7f, 0.42f, 0.58f)
        drawEyeStroke(canvas, frontQuad, 0.74f, 0.7f, 0.58f, 0.58f)
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

    /** A caved-in patch plus a couple of crack lines on the front face --
     * "ベコッ": the box survived a punch but visibly took the hit. Placed
     * off-center (not over the grumpy eyes) via the same [quadPoint]
     * glue-to-face mechanism every other decoration here uses. */
    private fun drawDent(canvas: Canvas, frontQuad: Array<FloatArray>) {
        val center = quadPoint(frontQuad, 0.5f, 0.32f)
        val a = quadPoint(frontQuad, 0.32f, 0.20f)
        val b = quadPoint(frontQuad, 0.68f, 0.20f)
        val c = quadPoint(frontQuad, 0.62f, 0.44f)
        val d = quadPoint(frontQuad, 0.38f, 0.44f)
        val path = Path().apply {
            moveTo(a[0], a[1]); lineTo(b[0], b[1]); lineTo(c[0], c[1]); lineTo(d[0], d[1]); close()
        }
        canvas.drawPath(path, dentFillPaint)

        val crack1 = quadPoint(frontQuad, 0.44f, 0.10f)
        val crack2 = quadPoint(frontQuad, 0.56f, 0.48f)
        canvas.drawLine(crack1[0], crack1[1], center[0], center[1], dentCrackPaint)
        canvas.drawLine(center[0], center[1], crack2[0], crack2[1], dentCrackPaint)
    }
}
