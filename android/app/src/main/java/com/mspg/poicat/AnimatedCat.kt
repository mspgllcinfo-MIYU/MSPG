package com.mspg.poicat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CatPose(val drawableRes: Int) {
    NORMAL(R.drawable.cat_normal),
    BLINK(R.drawable.cat_blink),
    LOOK_LEFT(R.drawable.cat_look_left),
    LOOK_RIGHT(R.drawable.cat_look_right),
    TAP(R.drawable.cat_tap),
}

/**
 * The cat character, as a fixed set of pre-rendered transparent-PNG poses
 * (drawable-nodpi/cat_*.png) rather than anything drawn/shaped in code — the
 * artwork itself is never touched. "Animation" is limited to swapping which
 * PNG is showing plus a few lightweight Modifier transforms (a small
 * vertical bob, a slight tilt, a tap scale-bounce); nothing deforms the
 * character's actual shape.
 */
@Composable
fun AnimatedCat(onTap: () -> Unit, modifier: Modifier = Modifier) {
    var pose by remember { mutableStateOf(CatPose.NORMAL) }

    // Idle behavior loop: mostly NORMAL, with an occasional brief blink or
    // glance to the side — never overlapping, and always settling back to
    // NORMAL before the next one is picked.
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2500, 5000))
            pose = CatPose.BLINK
            delay(180)
            pose = CatPose.NORMAL

            delay(Random.nextLong(2500, 5000))
            if (Random.nextInt(3) == 0) {
                pose = if (Random.nextBoolean()) CatPose.LOOK_LEFT else CatPose.LOOK_RIGHT
                delay(900)
                pose = CatPose.NORMAL
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "cat-idle")
    // A very small, slow up/down bob.
    val bob = infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    // A barely-there left/right tilt.
    val tilt = infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "tilt",
    )

    val tapScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Image(
        painter = painterResource(pose.drawableRes),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(width = 190.dp, height = 190.dp)
            .graphicsLayer {
                translationY = bob.value.dp.toPx()
                rotationZ = tilt.value
                scaleX = tapScale.value
                scaleY = tapScale.value
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                onTap()
                scope.launch {
                    val previous = pose
                    pose = CatPose.TAP
                    tapScale.snapTo(1.12f)
                    tapScale.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
                    delay(300)
                    if (pose == CatPose.TAP) pose = previous
                }
            },
    )
}
