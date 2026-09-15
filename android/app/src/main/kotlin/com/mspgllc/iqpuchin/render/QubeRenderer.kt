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
 * the same [IsoProjection] used for the floor -- not by rotating a
 * single flat icon. That distinction matters: rotating a flat 2D drawing
 * around an axis in its own plane visually collapses it to a line at 90
 * degrees, which would make the QUBE appear to vanish exactly at the
 * moment it's supposed to land. Rotating real 3D points has no such
 * problem, and it's also what makes the pivot corner stay pinned in
 * place (the edge it's actually toppling over) instead of the whole
 * shape sliding.
 *
 * The rotation pivot is always exactly at the true boundary between the
 * two cells (half a grid unit from each cell's center) -- that's what
 * guarantees every topple advances the QUBE by exactly one grid unit
 * with a perfectly flush landing and no visual jump into the next
 * topple. [RenderConfig.QUBE_VISUAL_SCALE] never touches that; it only
 * shrinks the already-correct projected corners toward their own center
 * afterward, purely so a small gap can show against the cell edges.
 */
class QubeRenderer {

    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(130, 180, 255) }
    private val rightFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 115, 205) }
    private val frontFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 80, 155) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 25, 45)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /** Cosine-based ease-in-out: slow start, fast middle, slow finish --
     * a constant rotation speed would read as mechanical rather than a
     * natural topple. */
    private fun easeInOut(linearT: Float): Float =
        (1f - cos(linearT * Math.PI.toFloat())) / 2f

    fun draw(canvas: Canvas, qube: Qube, motion: QubeMotion, projection: IsoProjection) {
        val easedT = easeInOut(motion.rotationProgress())
        val thetaBase = easedT * (Math.PI.toFloat() / 2f) // 0..90 degrees, in radians

        // Rotation always pivots as if the QUBE exactly filled one cell
        // (half-extent 0.5), regardless of the cosmetic visual scale --
        // see the class doc for why.
        val half = 0.5f

        fun corner(localX: Float, localY: Float, localZ: Float): FloatArray {
            val fromX = qube.previousCoord.x.toFloat()
            val fromZ = qube.previousCoord.z.toFloat()
            val dz = qube.direction.dz.toFloat()

            val pivotZ = half * dz
            val relY = localY
            val relZ = localZ - pivotZ
            val theta = thetaBase * dz
            val cosT = cos(theta)
            val sinT = sin(theta)

            val worldY = cosT * relY - sinT * relZ
            val relZ2 = sinT * relY + cosT * relZ
            val worldZ = fromZ + pivotZ + relZ2
            val worldX = fromX + localX

            return projection.toScreen(worldX, worldZ, worldY)
        }

        val bLL = corner(-half, 0f, -half)
        val bLR = corner(half, 0f, -half)
        val bFL = corner(-half, 0f, half)
        val bFR = corner(half, 0f, half)
        val tLL = corner(-half, 2f * half, -half)
        val tLR = corner(half, 2f * half, -half)
        val tFL = corner(-half, 2f * half, half)
        val tFR = corner(half, 2f * half, half)

        // Cosmetic-only: shrink all 8 projected points toward their
        // shared center, after the physically-correct rotation above.
        val raw = arrayOf(bLL, bLR, bFL, bFR, tLL, tLR, tFL, tFR)
        val centerX = raw.sumOf { it[0].toDouble() }.toFloat() / raw.size
        val centerY = raw.sumOf { it[1].toDouble() }.toFloat() / raw.size
        val scale = RenderConfig.QUBE_VISUAL_SCALE
        val shrunk = Array(raw.size) { i ->
            floatArrayOf(centerX + (raw[i][0] - centerX) * scale, centerY + (raw[i][1] - centerY) * scale)
        }
        // Index order matches `raw` above: 0=bLL 1=bLR 2=bFL 3=bFR 4=tLL 5=tLR 6=tFL 7=tFR
        drawFace(canvas, topPaint, shrunk[4], shrunk[5], shrunk[7], shrunk[6])
        drawFace(canvas, rightFacePaint, shrunk[1], shrunk[5], shrunk[7], shrunk[3])
        drawFace(canvas, frontFacePaint, shrunk[2], shrunk[3], shrunk[7], shrunk[6])
    }

    private fun drawFace(canvas: Canvas, paint: Paint, a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) {
        val path = Path().apply {
            moveTo(a[0], a[1])
            lineTo(b[0], b[1])
            lineTo(c[0], c[1])
            lineTo(d[0], d[1])
            close()
        }
        canvas.drawPath(path, paint)
        canvas.drawPath(path, outline)
    }
}
