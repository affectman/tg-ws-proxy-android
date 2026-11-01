package io.github.affectman.tgwsproxy.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import io.github.affectman.tgwsproxy.core.ProxyState

/** One shape per state; the button morphs between them. */
private object PowerShapes {
    val stopped = RoundedPolygon.circle(numVertices = 12).normalized()
    val starting = RoundedPolygon.star(
        numVerticesPerRadius = 8, innerRadius = 0.78f, rounding = CornerRounding(0.22f),
    ).normalized()
    val running = RoundedPolygon.star(
        numVerticesPerRadius = 12, innerRadius = 0.88f,
        rounding = CornerRounding(0.16f), innerRounding = CornerRounding(0.16f),
    ).normalized()
    val error = RoundedPolygon(numVertices = 6, rounding = CornerRounding(0.3f)).normalized()

    fun of(state: ProxyState) = when (state) {
        ProxyState.STOPPED -> stopped
        ProxyState.STARTING -> starting
        ProxyState.RUNNING -> running
        ProxyState.ERROR -> error
    }
}

/**
 * The big on/off control. Only the short "starting" phase animates
 * continuously; the running state is still, so an open app costs no frames.
 */
@Composable
fun PowerButton(
    state: ProxyState,
    onToggle: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 196.dp,
) {
    var from by remember { mutableStateOf(state) }
    var to by remember { mutableStateOf(state) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state != to) {
            from = to
            to = state
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val morph = remember(from, to) { Morph(PowerShapes.of(from), PowerShapes.of(to)) }

    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (state) {
            ProxyState.RUNNING -> colors.primary
            ProxyState.STARTING -> colors.secondaryContainer
            ProxyState.ERROR -> colors.errorContainer
            ProxyState.STOPPED -> colors.surfaceContainerHighest
        },
        label = "container",
    )
    val content by animateColorAsState(
        when (state) {
            ProxyState.RUNNING -> colors.onPrimary
            ProxyState.STARTING -> colors.onSecondaryContainer
            ProxyState.ERROR -> colors.onErrorContainer
            ProxyState.STOPPED -> colors.onSurfaceVariant
        },
        label = "content",
    )
    val halo by animateColorAsState(
        if (state == ProxyState.RUNNING) colors.primary.copy(alpha = 0.14f) else Color.Transparent,
        label = "halo",
    )

    val rotation = if (state == ProxyState.STARTING) {
        val spin = rememberInfiniteTransition(label = "spin")
        spin.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
            label = "rotation",
        ).value
    } else 0f

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale = remember { Animatable(1f) }
    LaunchedEffect(pressed) { pressScale.animateTo(if (pressed) 0.94f else 1f, spring(stiffness = Spring.StiffnessMedium)) }

    Box(
        modifier = modifier
            .size(size)
            .scale(pressScale.value)
            .clip(CircleShape)
            .toggleable(
                value = state.isActive,
                interactionSource = interaction,
                indication = ripple(bounded = true),
                role = Role.Switch,
                onValueChange = { onToggle() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Soft halo behind the shape while running.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache { onDrawBehind { drawCircle(halo) } }
        )
        Box(
            Modifier
                .size(size * 0.84f)
                .graphicsLayer { rotationZ = rotation }
                .drawWithCache {
                    val matrix = android.graphics.Matrix().apply { setScale(this@drawWithCache.size.width, this@drawWithCache.size.height) }
                    val path = morph.toPath(progress.value).apply { transform(matrix) }.asComposePath()
                    onDrawBehind { drawPath(path, container) }
                }
        )
        Icon(
            imageVector = Icons.Rounded.PowerSettingsNew,
            contentDescription = contentDescription,
            tint = content,
            modifier = Modifier.size(size * 0.34f),
        )
    }
}
