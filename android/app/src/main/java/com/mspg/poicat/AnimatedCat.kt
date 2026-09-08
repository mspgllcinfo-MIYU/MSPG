package com.mspg.poicat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A hand-drawn (Canvas, no image assets) matte-black "grumpy bobblehead" cat
 * modeled on a reference character sheet: huge round head over a small body,
 * short stubby arms/legs, a barely-visible tail, a chest "×" mark, and — the
 * key feature — large half-lidded eyes (a wide eye-white with a droopy lid
 * shape drawn over the top of it) for a sleepy, unimpressed expression.
 * Motion is deliberately minimal per spec: a slow blink, eyes drifting
 * left/right, a small tail twitch, a slight body sway, and a brief
 * "look at you" on tap — nothing that would break the still-pose design.
 */
@Composable
fun AnimatedCat(onTap: () -> Unit, modifier: Modifier = Modifier) {
    val bodyShadow = Color(0xFF17171A)
    val eyeWhite = Color(0xFFEDE9E2)
    val pupilColor = Color(0xFF141316)
    val markColor = Color(0xFF3C3A40)
    val whiskerColor = Color(0xFFCFC9BC)

    val infiniteTransition = rememberInfiniteTransition(label = "cat-idle")

    // Slight body sway — a gentle drift, not a walk.
    val sway = infiniteTransition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sway",
    )
    // Small tail twitch, not a wide wag.
    val tailAngle = infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tail",
    )
    // Eyes slowly drift left/right on their own.
    val pupilDrift = infiniteTransition.animateFloat(
        initialValue = -1.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pupilDrift",
    )

    // Slow, sleepy blink.
    var eyesClosed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(3000, 6000))
            eyesClosed = true
            delay(260)
            eyesClosed = false
        }
    }
    val eyeOpen = animateFloatAsState(if (eyesClosed) 0.08f else 1f, tween(220, easing = FastOutSlowInEasing), label = "eyeOpen")

    // Tap: briefly looks straight at you (pupils recenter) plus a small bounce.
    val tapPulse = remember { Animatable(0f) }
    val lookAtViewer = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Canvas(
        modifier = modifier
            .size(width = 200.dp, height = 190.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                onTap()
                scope.launch {
                    tapPulse.snapTo(1f)
                    tapPulse.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
                }
                scope.launch {
                    lookAtViewer.animateTo(1f, tween(120))
                    delay(450)
                    lookAtViewer.animateTo(0f, tween(250))
                }
            },
    ) {
        scale(1f + tapPulse.value * 0.15f, pivot = Offset(size.width / 2f, size.height * 0.72f)) {
            val centerX = size.width / 2f + sway.value.dp.toPx()

            // Bobblehead proportions: a huge head over a small body — do not
            // draw this as a normal cat body/head ratio.
            val headRadius = 54.dp.toPx()
            val bodyWidth = 50.dp.toPx()
            val bodyHeight = 44.dp.toPx()
            val armWidth = 13.dp.toPx()
            val armHeight = 30.dp.toPx()
            val legWidth = 17.dp.toPx()
            val legHeight = 16.dp.toPx()
            val groundGap = 4.dp.toPx()

            val bodyBottom = size.height - groundGap - legHeight
            val bodyTop = bodyBottom - bodyHeight
            val bodyCenter = Offset(centerX, bodyTop + bodyHeight / 2f)
            val headCenter = Offset(centerX, bodyTop - headRadius * 0.12f)

            val headBrush = Brush.radialGradient(
                colors = listOf(Color(0xFF4C4A50), Color(0xFF19181B)),
                center = Offset(headCenter.x - headRadius * 0.32f, headCenter.y - headRadius * 0.4f),
                radius = headRadius * 1.5f,
            )
            val bodyBrush = Brush.radialGradient(
                colors = listOf(Color(0xFF403E44), Color(0xFF1B1A1D)),
                center = Offset(bodyCenter.x - bodyWidth * 0.3f, bodyCenter.y - bodyHeight * 0.35f),
                radius = bodyWidth * 1.3f,
            )

            // Tail — short, curled in, mostly tucked behind the body.
            val tailBase = Offset(bodyCenter.x + bodyWidth * 0.42f, bodyCenter.y + bodyHeight * 0.28f)
            rotate(tailAngle.value, pivot = tailBase) {
                val tail = Path().apply {
                    moveTo(tailBase.x, tailBase.y)
                    quadraticBezierTo(
                        tailBase.x + 16.dp.toPx(), tailBase.y - 6.dp.toPx(),
                        tailBase.x + 10.dp.toPx(), tailBase.y - 22.dp.toPx(),
                    )
                }
                drawPath(tail, color = bodyShadow, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
            }

            // Short arms at the sides
            listOf(-1f, 1f).forEach { side ->
                val armCenter = Offset(centerX + side * (bodyWidth / 2f + armWidth * 0.35f), bodyCenter.y - bodyHeight * 0.05f)
                rotate(side * 10f, pivot = armCenter) {
                    drawRoundRect(
                        color = bodyShadow,
                        topLeft = Offset(armCenter.x - armWidth / 2f, armCenter.y - armHeight / 2f),
                        size = Size(armWidth, armHeight),
                        cornerRadius = CornerRadius(armWidth / 2f, armWidth / 2f),
                    )
                }
            }

            // Short legs (drawn before the body so its curve overlaps their tops)
            val legGap = 10.dp.toPx()
            listOf(-1f, 1f).forEach { side ->
                val legLeft = if (side < 0) centerX - legGap / 2f - legWidth else centerX + legGap / 2f
                drawRoundRect(
                    color = bodyShadow,
                    topLeft = Offset(legLeft, bodyBottom - 6.dp.toPx()),
                    size = Size(legWidth, legHeight + 6.dp.toPx()),
                    cornerRadius = CornerRadius(legWidth / 2f, legWidth / 2f),
                )
            }

            // Body — small relative to the head
            drawOval(
                brush = bodyBrush,
                topLeft = Offset(bodyCenter.x - bodyWidth / 2f, bodyTop),
                size = Size(bodyWidth, bodyHeight),
            )

            // Chest "×" mark
            val markCenter = Offset(bodyCenter.x, bodyTop + bodyHeight * 0.32f)
            val markHalf = 4.5.dp.toPx()
            drawLine(
                color = markColor.copy(alpha = 0.8f),
                start = markCenter + Offset(-markHalf, -markHalf),
                end = markCenter + Offset(markHalf, markHalf),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = markColor.copy(alpha = 0.8f),
                start = markCenter + Offset(markHalf, -markHalf),
                end = markCenter + Offset(-markHalf, markHalf),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // Ears — solid, rounded-triangle, close together, no colored inner ear.
            val earWidth = headRadius * 0.5f
            val earTipHeight = headRadius * 0.9f
            val earBaseOffset = headRadius * 0.42f
            val earBaseY = headCenter.y - headRadius * 0.7f
            listOf(-1f, 1f).forEach { side ->
                val baseCenter = Offset(headCenter.x + side * earBaseOffset, earBaseY)
                val baseLeft = Offset(baseCenter.x - earWidth / 2f, baseCenter.y)
                val baseRight = Offset(baseCenter.x + earWidth / 2f, baseCenter.y)
                val tip = Offset(baseCenter.x + side * earWidth * 0.1f, baseCenter.y - earTipHeight)
                val ear = Path().apply {
                    moveTo(baseLeft.x, baseLeft.y)
                    quadraticBezierTo(baseCenter.x - earWidth * 0.18f, baseCenter.y - earTipHeight * 0.75f, tip.x, tip.y)
                    quadraticBezierTo(baseCenter.x + earWidth * 0.18f, baseCenter.y - earTipHeight * 0.75f, baseRight.x, baseRight.y)
                    close()
                }
                drawPath(ear, brush = headBrush)
            }

            // Head
            drawCircle(brush = headBrush, radius = headRadius, center = headCenter)

            // Eyes: wide eye-white + a droopy lid drawn over its top (the lid
            // is just another head-colored oval, rotated to slant outward) —
            // this is what reads as "sleepy and annoyed" rather than a normal
            // round cat eye.
            val eyeWidth = headRadius * 0.44f
            val eyeHeight = headRadius * 0.26f
            val eyeDx = headRadius * 0.37f
            val eyeY = headCenter.y + headRadius * 0.02f
            listOf(-1f, 1f).forEach { side ->
                val eyeCenter = Offset(headCenter.x + side * eyeDx, eyeY)
                val visibleBottom = eyeCenter.y + eyeHeight * 0.32f
                scale(1f, eyeOpen.value, pivot = Offset(eyeCenter.x, visibleBottom)) {
                    drawOval(
                        color = eyeWhite,
                        topLeft = Offset(eyeCenter.x - eyeWidth / 2f, eyeCenter.y - eyeHeight / 2f),
                        size = Size(eyeWidth, eyeHeight),
                    )
                    val lookX = pupilDrift.value * (1f - lookAtViewer.value)
                    val pupilRadius = eyeHeight * 0.34f
                    val pupilCenter = Offset(
                        eyeCenter.x + side * eyeWidth * 0.14f + lookX.dp.toPx(),
                        eyeCenter.y + eyeHeight * 0.16f,
                    )
                    drawCircle(color = pupilColor, radius = pupilRadius, center = pupilCenter)
                }
                // Droopy lid: a head-colored oval overlapping the eye-white's top,
                // slanted so the outer corner droops lower than the inner one.
                val lidCenter = Offset(eyeCenter.x, eyeCenter.y - eyeHeight * 0.4f)
                rotate(side * -9f, pivot = lidCenter) {
                    drawOval(
                        brush = headBrush,
                        topLeft = Offset(lidCenter.x - eyeWidth * 0.56f, lidCenter.y - eyeHeight * 0.85f),
                        size = Size(eyeWidth * 1.12f, eyeHeight * 1.7f),
                    )
                }
            }

            // Nose — tiny, same tone as the fur, not a colored feature.
            val noseY = headCenter.y + headRadius * 0.3f
            drawCircle(color = bodyShadow, radius = 2.6.dp.toPx(), center = Offset(headCenter.x, noseY))

            // Mouth — almost not there.
            drawLine(
                color = markColor.copy(alpha = 0.4f),
                start = Offset(headCenter.x - 3.dp.toPx(), noseY + 5.dp.toPx()),
                end = Offset(headCenter.x + 3.dp.toPx(), noseY + 5.dp.toPx()),
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // Whiskers — thin and faint.
            val whiskerY = headCenter.y + headRadius * 0.32f
            listOf(-1f, 1f).forEach { side ->
                for (i in 0..1) {
                    val startX = headCenter.x + side * headRadius * 0.78f
                    val y = whiskerY + (i - 0.5f) * 6.dp.toPx()
                    drawLine(
                        color = whiskerColor.copy(alpha = 0.3f),
                        start = Offset(startX, y),
                        end = Offset(startX + side * 10.dp.toPx(), y),
                        strokeWidth = 1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
