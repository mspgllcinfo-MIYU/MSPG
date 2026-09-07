package com.mspg.poicat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A small hand-drawn (Canvas, no image assets) cat that idles on the Home
 * screen: it sways, blinks, wags its tail, tilts its head, occasionally
 * hops, and settles between a sitting and standing pose — all on cheap
 * float animations, no bitmaps or continuous recomposition, so it's fine to
 * leave running while the tab is open. Tapping it bounces once and invokes
 * [onTap] (Home wires this to switching to the 猫AI tab).
 */
@Composable
fun AnimatedCat(onTap: () -> Unit, modifier: Modifier = Modifier) {
    val bodyColor = Color(0xFFF6DAB6)
    val bodyShadow = Color(0xFFE9C08C)
    val accent = MaterialTheme.colorScheme.primary
    val eyeColor = Color(0xFF4A3B32)

    val infiniteTransition = rememberInfiniteTransition(label = "cat-idle")
    val sway = infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sway",
    )
    val tailAngle = infiniteTransition.animateFloat(
        initialValue = -22f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(tween(950, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tail",
    )
    val headTilt = infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "headTilt",
    )

    var blinking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2500, 5500))
            blinking = true
            delay(140)
            blinking = false
        }
    }
    val eyeScaleY = animateFloatAsState(if (blinking) 0.12f else 1f, tween(90), label = "blink")

    // Occasional sit/stand shift so the cat isn't locked into one silhouette.
    var standingTall by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(4000, 8000))
            standingTall = !standingTall
        }
    }
    val standAmount = animateFloatAsState(if (standingTall) 1f else 0f, tween(500, easing = FastOutSlowInEasing), label = "stand")

    // A little random hop now and then, on top of the continuous idle motions,
    // so the loop doesn't read as perfectly repetitive.
    val hop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(6000, 12000))
            hop.animateTo(10f, tween(160, easing = FastOutSlowInEasing))
            hop.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }
    }

    val tapPulse = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Canvas(
        modifier = modifier
            .size(width = 180.dp, height = 170.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                onTap()
                scope.launch {
                    tapPulse.snapTo(1f)
                    tapPulse.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
                }
            },
    ) {
        val hopPx = hop.value.dp.toPx()
        translate(top = -hopPx) {
            scale(1f + tapPulse.value * 0.18f, pivot = Offset(size.width / 2f, size.height * 0.72f)) {
                val centerX = size.width / 2f + sway.value.dp.toPx()

                val bodyWidth = 108.dp.toPx() * (1f - 0.05f * standAmount.value)
                val bodyHeight = 70.dp.toPx() * (1f + 0.12f * standAmount.value)
                val bodyTop = size.height - bodyHeight - 30.dp.toPx()
                val bodyCenter = Offset(centerX, bodyTop + bodyHeight / 2f)

                // Tail — drawn first so it sits behind the body, wagging around its base.
                val tailBase = Offset(bodyCenter.x + bodyWidth * 0.36f, bodyCenter.y + bodyHeight * 0.05f)
                rotate(tailAngle.value, pivot = tailBase) {
                    val tail = Path().apply {
                        moveTo(tailBase.x, tailBase.y)
                        quadraticBezierTo(
                            tailBase.x + 46.dp.toPx(), tailBase.y - 10.dp.toPx(),
                            tailBase.x + 34.dp.toPx(), tailBase.y - 52.dp.toPx(),
                        )
                    }
                    drawPath(tail, color = bodyColor, style = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round))
                }

                // Paws
                drawOval(
                    color = bodyShadow,
                    topLeft = Offset(bodyCenter.x - bodyWidth * 0.32f, bodyTop + bodyHeight - 10.dp.toPx()),
                    size = Size(22.dp.toPx(), 16.dp.toPx()),
                )
                drawOval(
                    color = bodyShadow,
                    topLeft = Offset(bodyCenter.x + bodyWidth * 0.10f, bodyTop + bodyHeight - 10.dp.toPx()),
                    size = Size(22.dp.toPx(), 16.dp.toPx()),
                )

                // Body
                drawRoundRect(
                    color = bodyColor,
                    topLeft = Offset(bodyCenter.x - bodyWidth / 2f, bodyTop),
                    size = Size(bodyWidth, bodyHeight),
                    cornerRadius = CornerRadius(bodyHeight / 2f, bodyHeight / 2f),
                )

                val headRadius = 34.dp.toPx()
                val headCenter = Offset(centerX, bodyTop - headRadius * 0.5f)

                rotate(headTilt.value, pivot = headCenter) {
                    // Ears
                    val earSize = 20.dp.toPx()
                    listOf(-1f, 1f).forEach { side ->
                        val ear = Path().apply {
                            moveTo(headCenter.x + side * headRadius * 0.55f, headCenter.y - headRadius * 0.75f)
                            lineTo(headCenter.x + side * (headRadius * 0.55f + earSize * 0.6f), headCenter.y - headRadius * 0.75f - earSize)
                            lineTo(headCenter.x + side * (headRadius * 0.55f + earSize * 1.15f), headCenter.y - headRadius * 0.55f)
                            close()
                        }
                        drawPath(ear, color = bodyColor)
                        val innerEar = Path().apply {
                            moveTo(headCenter.x + side * headRadius * 0.62f, headCenter.y - headRadius * 0.78f)
                            lineTo(headCenter.x + side * (headRadius * 0.62f + earSize * 0.32f), headCenter.y - headRadius * 0.78f - earSize * 0.55f)
                            lineTo(headCenter.x + side * (headRadius * 0.62f + earSize * 0.68f), headCenter.y - headRadius * 0.62f)
                            close()
                        }
                        drawPath(innerEar, color = accent.copy(alpha = 0.55f))
                    }

                    // Head
                    drawOval(color = bodyColor, topLeft = Offset(headCenter.x - headRadius, headCenter.y - headRadius), size = Size(headRadius * 2, headRadius * 2))

                    // Eyes (scaled for a blink)
                    val eyeY = headCenter.y - headRadius * 0.05f
                    val eyeDx = headRadius * 0.42f
                    val eyeSize = Size(9.dp.toPx(), 12.dp.toPx())
                    listOf(-1f, 1f).forEach { side ->
                        val eyeCenter = Offset(headCenter.x + side * eyeDx, eyeY)
                        scale(1f, eyeScaleY.value, pivot = eyeCenter) {
                            drawOval(
                                color = eyeColor,
                                topLeft = Offset(eyeCenter.x - eyeSize.width / 2f, eyeCenter.y - eyeSize.height / 2f),
                                size = eyeSize,
                            )
                        }
                    }

                    // Nose
                    val noseY = headCenter.y + headRadius * 0.28f
                    val nose = Path().apply {
                        moveTo(headCenter.x - 4.dp.toPx(), noseY)
                        lineTo(headCenter.x + 4.dp.toPx(), noseY)
                        lineTo(headCenter.x, noseY + 5.dp.toPx())
                        close()
                    }
                    drawPath(nose, color = accent)

                    // Mouth
                    val mouthWidth = 10.dp.toPx()
                    val mouthTop = noseY + 5.dp.toPx()
                    drawArc(
                        color = eyeColor,
                        startAngle = 20f,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(headCenter.x - mouthWidth, mouthTop - 4.dp.toPx()),
                        size = Size(mouthWidth, 10.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = eyeColor,
                        startAngle = 40f,
                        sweepAngle = -120f,
                        useCenter = false,
                        topLeft = Offset(headCenter.x, mouthTop - 4.dp.toPx()),
                        size = Size(mouthWidth, 10.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )

                    // Whiskers
                    val whiskerY = headCenter.y + headRadius * 0.15f
                    listOf(-1f, 1f).forEach { side ->
                        for (i in 0..2) {
                            val startX = headCenter.x + side * headRadius * 0.7f
                            val y = whiskerY + (i - 1) * 6.dp.toPx()
                            drawLine(
                                color = eyeColor.copy(alpha = 0.5f),
                                start = Offset(startX, y),
                                end = Offset(startX + side * 18.dp.toPx(), y - side * 2.dp.toPx() * (i - 1)),
                                strokeWidth = 1.4.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }
        }
    }
}
