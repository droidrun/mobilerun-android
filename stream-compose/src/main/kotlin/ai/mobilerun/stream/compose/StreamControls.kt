// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import ai.mobilerun.stream.android.SystemKey
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun Spinner() {
    val angle = rememberInfiniteTransition().animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
    )
    val color = streamThemeTokens.mutedForeground
    Canvas(Modifier.size(14.dp).rotate(angle.value)) {
        drawArc(color, 0f, 270f, false, style = Stroke(2.dp.toPx()))
    }
}

/** Spinner and label in a standalone pill; the host chooses its corner placement. */
@Composable
public fun StreamStatusPill(label: String, modifier: Modifier = Modifier) {
    val t = streamThemeTokens
    Row(
        modifier.clip(RoundedCornerShape(50)).background(t.card.copy(alpha = .8f))
            .border(1.dp, t.border, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spinner()
        BasicText(label, style = t.typography.caption.copy(color = t.mutedForeground))
    }
}

@Composable
internal fun StreamButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    pending: Boolean = false,
) {
    val t = streamThemeTokens
    val shape = RoundedCornerShape(t.radius)
    Row(
        modifier.defaultMinSize(minHeight = 48.dp).clip(shape)
            .background(if (primary) t.primary else t.background)
            .border(1.dp, t.border, shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pending) Spinner()
        BasicText(label, style = t.typography.body.copy(
            color = (if (primary) t.primaryForeground else t.foreground).copy(alpha = if (enabled) 1f else .5f),
        ))
    }
}

/** Back/Home/Recent icon actions (Android codes 4/3/187). The caller owns sending and control access. */
@Composable
public fun NavigationBar(
    onAction: (SystemKey) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labels: StreamLabels = StreamLabels(),
) {
    val t = streamThemeTokens
    Column(modifier.background(t.background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.border))
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            listOf(SystemKey.BACK to labels.back, SystemKey.HOME to labels.home, SystemKey.RECENT to labels.recent).forEach { (key, label) ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = label }
                        .clickable(enabled = enabled, role = Role.Button) { onAction(key) },
                    contentAlignment = Alignment.Center,
                ) { NavigationIcon(key, t.foreground.copy(alpha = if (enabled) 1f else .5f)) }
            }
        }
    }
}

@Composable
private fun NavigationIcon(key: SystemKey, color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = Stroke(1.6.dp.toPx())
        when (key) {
            SystemKey.BACK -> drawPath(Path().apply {
                moveTo(size.width * .65f, size.height * .1f)
                lineTo(size.width * .25f, size.height * .5f)
                lineTo(size.width * .65f, size.height * .9f)
            }, color, style = stroke)
            SystemKey.HOME -> drawPath(Path().apply {
                moveTo(size.width * .1f, size.height * .45f)
                lineTo(size.width * .5f, size.height * .1f)
                lineTo(size.width * .9f, size.height * .45f)
                lineTo(size.width * .9f, size.height * .9f)
                lineTo(size.width * .1f, size.height * .9f)
                close()
            }, color, style = stroke)
            SystemKey.RECENT -> for (x in listOf(.1f, .6f)) for (y in listOf(.1f, .6f)) {
                drawRect(color, Offset(size.width * x, size.height * y),
                    androidx.compose.ui.geometry.Size(size.width * .3f, size.height * .3f), style = stroke)
            }
        }
    }
}
