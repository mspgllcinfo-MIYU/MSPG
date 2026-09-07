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
    val bodyColor = Color(0xFF2B2A2E)
    val bodyShadow = Color(0xFF201F23)
    val accent = MaterialTheme.colorScheme.primary
    val eyeWhite = Color(0xFFF6F1E4)
    val pupilColor = Color(0xFF17161A)
    val mouthColor = Color(0xFF5B5760)
    val whiskerColor = Color(0xFFDCD6C8)

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

                // A big round head over a small round body with short stubby legs —
                // toy-figure proportions read as much cuter than a wide body/small head.
                val headRadius = 46.dp.toPx()
                val bodyWidth = 82.dp.toPx() * (1f - 0.04f * standAmount.value)
                val bodyHeight = 72.dp.toPx() * (1f + 0.08f * standAmount.value)
                val legHeight = 14.dp.toPx() + 8.dp.toPx() * standAmount.value
                val groundGap = 4.dp.toPx()

                val bodyBottom = size.height - groundGap - legHeight
                val bodyTop = bodyBottom - bodyHeight
                val bodyCenter = Offset(centerX, bodyTop + bodyHeight / 2f)
                val headCenter = Offset(centerX, bodyTop - headRadius * 0.22f)

                // Tail — small and curled in rather than sticking straight out, drawn
                // first so it sits behind the body while it wags around its base.
                val tailBase = Offset(bodyCenter.x + bodyWidth * 0.4f, bodyCenter.y + bodyHeight * 0.22f)
                rotate(tailAngle.value, pivot = tailBase) {
                    val tail = Path().apply {
                        moveTo(tailBase.x, tailBase.y)
                        quadraticBezierTo(
                            tailBase.x + 24.dp.toPx(), tailBase.y - 8.dp.toPx(),
                            tailBase.x + 15.dp.toPx(), tailBase.y - 30.dp.toPx(),
                        )
                    }
                    drawPath(tail, color = bodyColor, style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round))
                }

                // Legs (drawn before the body so its rounded bottom overlaps their tops)
                val legWidth = 22.dp.toPx()
                val legGap = 8.dp.toPx()
                listOf(-1f, 1f).forEach { side ->
                    drawRoundRect(
                        color = bodyShadow,
                        topLeft = Offset(centerX + side * (legGap / 2f + if (side < 0) legWidth else 0f), bodyBottom - 6.dp.toPx()),
                        size = Size(legWidth, legHeight + 6.dp.toPx()),
                        cornerRadius = CornerRadius(legWidth / 2f, legWidth / 2f),
                    )
                }

                // Body
                drawOval(
                    color = bodyColor,
                    topLeft = Offset(bodyCenter.x - bodyWidth / 2f, bodyTop),
                    size = Size(bodyWidth, bodyHeight),
                )

                rotate(headTilt.value, pivot = headCenter) {
                    // Ears: plain rounded ovals, mostly hidden behind the head circle
                    // drawn afterwards — leaves just soft round tips peeking out.
                    val earWidth = headRadius * 0.62f
                    val earHeight = headRadius * 0.95f
                    val earOffsetX = headRadius * 0.5f
                    val earCenterY = headCenter.y - headRadius * 0.8f
                    listOf(-1f, 1f).forEach { side ->
                        val earCenter = Offset(headCenter.x + side * earOffsetX, earCenterY)
                        rotate(side * 14f, pivot = earCenter) {
                            drawOval(
                                color = bodyColor,
                                topLeft = Offset(earCenter.x - earWidth / 2f, earCenter.y - earHeight / 2f),
                                size = Size(earWidth, earHeight),
                            )
                            val innerSize = Size(earWidth * 0.5f, earHeight * 0.55f)
                            drawOval(
                                color = accent.copy(alpha = 0.65f),
                                topLeft = Offset(earCenter.x - innerSize.width / 2f, earCenter.y - innerSize.height / 2f + earHeight * 0.12f),
                                size = innerSize,
                            )
                        }
                    }

                    // Head
                    drawCircle(color = bodyColor, radius = headRadius, center = headCenter)

                    // Eyes: white sclera + dark pupil (needed for contrast against a
                    // black head) with a small highlight — scaled for a blink.
                    val eyeY = headCenter.y + headRadius * 0.04f
                    val eyeDx = headRadius * 0.34f
                    val eyeRadius = 10.dp.toPx()
                    listOf(-1f, 1f).forEach { side ->
                        val eyeCenter = Offset(headCenter.x + side * eyeDx, eyeY)
                        scale(1f, eyeScaleY.value, pivot = eyeCenter) {
                            drawCircle(color = eyeWhite, radius = eyeRadius, center = eyeCenter)
                            drawCircle(color = pupilColor, radius = eyeRadius * 0.58f, center = eyeCenter)
                            drawCircle(
                                color = Color.White,
                                radius = eyeRadius * 0.2f,
                                center = eyeCenter + Offset(-eyeRadius * 0.3f, -eyeRadius * 0.3f),
                            )
                        }
                    }

                    // Nose
                    val noseY = headCenter.y + headRadius * 0.34f
                    drawCircle(color = accent, radius = 3.dp.toPx(), center = Offset(headCenter.x, noseY))

                    // Mouth: one small, gentle curve — understated rather than a wide grin.
                    val mouthWidth = 12.dp.toPx()
                    drawArc(
                        color = mouthColor,
                        startAngle = 30f,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(headCenter.x - mouthWidth / 2f, noseY - 2.dp.toPx()),
                        size = Size(mouthWidth, 8.dp.toPx()),
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
                    )

                    // Whiskers: short and subtle, light against the dark fur
                    val whiskerY = headCenter.y + headRadius * 0.22f
                    listOf(-1f, 1f).forEach { side ->
                        for (i in 0..1) {
                            val startX = headCenter.x + side * headRadius * 0.75f
                            val y = whiskerY + (i - 0.5f) * 7.dp.toPx()
                            drawLine(
                                color = whiskerColor.copy(alpha = 0.7f),
                                start = Offset(startX, y),
                                end = Offset(startX + side * 12.dp.toPx(), y - side * 1.dp.toPx()),
                                strokeWidth = 1.2.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }
        }
    }
}
