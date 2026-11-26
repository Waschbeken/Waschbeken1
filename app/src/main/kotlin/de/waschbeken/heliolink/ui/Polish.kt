package de.waschbeken.heliolink.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry

/**
 * Small touches that make the app feel finished: a short tick under the finger, cards that give
 * way a little when pressed, soft placeholders while data loads, sections that unfold instead of
 * jumping, and pages that slide in.
 */
class Haptics(private val view: View) {
    /** A light tick: opening a section, choosing an option. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Something was done: an entry saved, a training started. */
    fun confirm() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** A switch turned on or off. */
    fun toggle(on: Boolean) {
        val constant = when {
            Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Clickable that shrinks a little while pressed, like a real button; [shape] bounds the ripple. */
fun Modifier.pressClick(shape: Shape, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .clickable(interactionSource = source, indication = ripple(), enabled = enabled, onClick = onClick)
}

/** A grey block that gently pulses where content is still loading. */
@Composable
fun Skeleton(modifier: Modifier, shape: Shape = RoundedCornerShape(16.dp)) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha")
    Box(modifier.graphicsLayer { this.alpha = alpha }.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape))
}

/** Rows below a [CollapsibleHeader] that unfold and fold away smoothly. */
@Composable
fun ExpandableRows(open: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(tween(260, easing = FastOutSlowInEasing)) + fadeIn(tween(220, delayMillis = 40)),
        exit = shrinkVertically(tween(220, easing = FastOutSlowInEasing)) + fadeOut(tween(150)),
    ) {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/**
 * Page changes: the tabs fade into each other (a little zoom in), a page opened from one slides
 * in from the right and back out again – the way the system apps do it.
 */
object PageTransitions {
    private fun isTab(e: NavBackStackEntry) = tabs.any { it.route == e.destination.route }
    private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() = isTab(initialState) && isTab(targetState)

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (betweenTabs()) fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.97f)
        else slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(240))
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (betweenTabs()) fadeOut(tween(90))
        else slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 10 } + fadeOut(tween(200))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (betweenTabs()) fadeIn(tween(220, delayMillis = 60))
        else slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 10 } + fadeIn(tween(240))
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (betweenTabs()) fadeOut(tween(90))
        else slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(200))
    }
}
