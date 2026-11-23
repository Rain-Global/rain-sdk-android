package com.rain.sdk.sample.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rain.sdk.sample.R
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainTheme
import com.rain.sdk.sample.ui.theme.RainType

/*
 * Part of the sample's component kit, a Compose port of the Rain design system's `components.css`: white
 * canvas, hairline neutral borders on all four sides, 20dp cards, pill buttons that go pink on
 * press, 4dp inputs with a pink focus ring, and Phosphor icons in the pink container. Sentence case
 * everywhere; no emoji.
 */

/**
 * Pill button. Callers size it with [modifier] (`fillMaxWidth()` or `weight(1f)`); primaries are
 * 48dp tall, the rest 44dp. Press is a colour settle, not a ripple or a bounce. Disabled is muted
 * grey with no pink.
 */
@Suppress("LongParameterList") // Slot-style Compose API: every extra parameter is an optional knob.
@Composable
fun RainButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RainButtonStyle = RainButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    @DrawableRes icon: Int? = null,
    height: Dp = if (style == RainButtonStyle.Primary) 48.dp else 44.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && !loading

    val targets = buttonColors(style, active, pressed)
    val background by animateColorAsState(targets.background, tween(PRESS_MS), label = "buttonBg")
    val foreground by animateColorAsState(targets.foreground, tween(PRESS_MS), label = "buttonFg")
    val border by animateColorAsState(targets.border, tween(PRESS_MS), label = "buttonBorder")

    Row(
        modifier = modifier
            .height(height)
            .clip(PillShape)
            .background(background)
            .border(1.dp, border, PillShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (style == RainButtonStyle.Ghost) 14.dp else 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            RainSpinner(color = foreground)
        } else if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(text, style = RainType.Strong.copy(color = foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class ButtonColors(val background: Color, val foreground: Color, val border: Color)

/** Resting, pressed, and disabled colours for each button style. */
private fun buttonColors(style: RainButtonStyle, active: Boolean, pressed: Boolean): ButtonColors {
    if (!active) {
        return ButtonColors(
            background = if (style == RainButtonStyle.Primary) RainColors.Gray15 else Color.Transparent,
            foreground = RainColors.TextSubtle,
            border = if (style == RainButtonStyle.Secondary) RainColors.BorderSubtle else Color.Transparent,
        )
    }
    return when (style) {
        RainButtonStyle.Primary -> ButtonColors(
            background = if (pressed) RainColors.Pink else RainColors.Ink,
            foreground = RainColors.White,
            border = Color.Transparent,
        )
        RainButtonStyle.Secondary -> ButtonColors(
            background = if (pressed) RainColors.Ink else Color.Transparent,
            foreground = if (pressed) RainColors.White else RainColors.Ink,
            border = if (pressed) RainColors.Ink else RainColors.BorderStrong,
        )
        RainButtonStyle.Ghost -> ButtonColors(
            background = Color.Transparent,
            foreground = if (pressed) RainColors.Pink else RainColors.Ink,
            border = Color.Transparent,
        )
    }
}

/** Round 48dp icon-only target; the glyph turns pink while pressed. */
@Suppress("LongParameterList") // Slot-style Compose API: every extra parameter is an optional knob.
@Composable
fun RainIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint by animateColorAsState(
        when {
            !enabled -> RainColors.TextSubtle
            pressed -> RainColors.Pink
            else -> RainColors.Ink
        },
        tween(PRESS_MS),
        label = "iconTint",
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Inline text action (e.g. a card's "Refresh"): Semibold ink, pink while pressed. */
@Composable
fun RainTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color by animateColorAsState(
        when {
            !enabled -> RainColors.TextSubtle
            pressed -> RainColors.Pink
            else -> RainColors.Ink
        },
        tween(PRESS_MS),
        label = "textAction",
    )
    Row(
        modifier = modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .minimumInteractiveComponentSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = RainType.Strong.copy(color = color))
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Small ink progress ring; sized to sit inside a button. */
@Composable
fun RainSpinner(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = RainColors.Ink) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        trackColor = Color.Transparent,
        strokeWidth = 2.dp,
    )
}

// region Previews

@Preview(name = "Buttons", showBackground = true, heightDp = 760)
@Composable
private fun RainButtonsGalleryPreview() {
    RainTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RainButton(text = "Primary", onClick = {}, modifier = Modifier.fillMaxWidth())
            RainButton(
                text = "Primary with icon",
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                icon = R.drawable.ic_arrow_up_right,
            )
            RainButton(text = "Primary · disabled", onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false)
            RainButton(text = "Primary · loading", onClick = {}, modifier = Modifier.fillMaxWidth(), loading = true)

            RainButton(
                text = "Secondary",
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                style = RainButtonStyle.Secondary,
            )
            RainButton(
                text = "Secondary · disabled",
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                style = RainButtonStyle.Secondary,
                enabled = false,
            )
            RainButton(
                text = "Ghost",
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                style = RainButtonStyle.Ghost,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RainIconButton(icon = R.drawable.ic_arrow_left, contentDescription = "Back", onClick = {})
                RainIconButton(icon = R.drawable.ic_copy, contentDescription = "Copy", onClick = {})
                RainIconButton(
                    icon = R.drawable.ic_copy,
                    contentDescription = "Copy, disabled",
                    onClick = {},
                    enabled = false,
                )
                RainSpinner()
            }

            RainTextAction(text = "Text action", onClick = {})
            RainTextAction(text = "Text action with icon", onClick = {}, icon = R.drawable.ic_arrow_up_right)
            RainTextAction(text = "Text action · disabled", onClick = {}, enabled = false)
        }
    }
}

// endregion
