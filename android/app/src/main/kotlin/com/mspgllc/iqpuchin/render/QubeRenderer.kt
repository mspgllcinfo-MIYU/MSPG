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
 * actually pointing up (drawn bright, "top" role) and whichever
 * remaining face is pointing most toward the camera (drawn mid-tone,
 * "front" role) -- this is standard back-face culling by normal
 * direction. The +X face is unaffected by this rotation (the pivot axis
 * runs parallel to it) so it is always the visible dark side face; -X
 * and the "currently facing away" candidate are never drawn at all,
 * which is the culling. The three chosen faces are then depth-sorted
 * (farthest first) before drawing, as a defensive measure against any
 * edge-on overlap during rotation.
 */
class QubeRenderer {

    private enum class Face { TOP, BOTTOM, FRONT, BACK }

    private val brightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 195, 255) }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 130, 215) }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 80, 155) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 25, 45)
        style = Paint.Style.STROKE
        strokeWidth = 2f
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
}
