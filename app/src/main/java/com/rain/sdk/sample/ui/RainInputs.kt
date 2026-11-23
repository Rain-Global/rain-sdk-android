package com.rain.sdk.sample.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rain.sdk.sample.R
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainRadius
import com.rain.sdk.sample.ui.theme.RainTheme
import com.rain.sdk.sample.ui.theme.RainType

/*
 * Part of the sample's component kit, a Compose port of the Rain design system's `components.css`: white
 * canvas, hairline neutral borders on all four sides, 20dp cards, pill buttons that go pink on
 * press, 4dp inputs with a pink focus ring, and Phosphor icons in the pink container. Sentence case
 * everywhere; no emoji.
 */

/**
 * Single-line text input: 4dp radius, hairline border, 11x14 padding, pink border plus soft halo
 * on focus. Errors are reported next to the field in helper copy, never as a coloured border.
 */
@Suppress("LongParameterList") // Slot-style Compose API: every extra parameter is an optional knob.
@Composable
fun RainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        when {
            !enabled -> RainColors.BorderSubtle
            focused -> RainColors.Focus
            else -> RainColors.Border
        },
        tween(PRESS_MS),
        label = "fieldBorder",
    )
    val haloPx = with(LocalDensity.current) { 3.dp.toPx() }
    val haloRadiusPx = with(LocalDensity.current) { (RainRadius.Small + 3.dp).toPx() }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        textStyle = RainType.Body.copy(color = if (enabled) RainColors.Ink else RainColors.TextMuted),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        interactionSource = interaction,
        cursorBrush = SolidColor(RainColors.Pink),
        decorationBox = { innerField ->
            Box(
                modifier = Modifier
                    .drawBehind {
                        if (focused && enabled) {
                            drawRoundRect(
                                color = RainColors.FocusHalo,
                                topLeft = Offset(-haloPx, -haloPx),
                                size = Size(size.width + haloPx * 2, size.height + haloPx * 2),
                                cornerRadius = CornerRadius(haloRadiusPx),
                            )
                        }
                    }
                    .background(if (enabled) RainColors.Surface else RainColors.SurfaceDisabled, SmallShape)
                    .border(1.dp, borderColor, SmallShape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = RainType.Body.copy(color = RainColors.TextSubtle), maxLines = 1)
                }
                innerField()
            }
        },
    )
}

/** Label, input, and optional helper copy (muted, or danger for a validation problem). */
@Suppress("LongParameterList") // Slot-style Compose API: every extra parameter is an optional knob.
@Composable
fun RainField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    helper: String? = null,
    helperIsError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RainLabel(label)
        RainTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            keyboardType = keyboardType,
        )
        if (helper != null) {
            Text(
                helper,
                style = if (helperIsError) RainType.Body.copy(color = RainColors.Danger) else RainType.BodyMuted,
            )
        }
    }
}

/** Input-shaped trigger showing the current choice and a caret; pairs with a dropdown menu. */
@Composable
fun RainDropdownField(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val borderColor by animateColorAsState(
        when {
            !enabled -> RainColors.BorderSubtle
            pressed -> RainColors.Focus
            else -> RainColors.Border
        },
        tween(PRESS_MS),
        label = "dropdownBorder",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RainColors.Surface, SmallShape)
            .border(1.dp, borderColor, SmallShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.DropdownList,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = RainType.Body.copy(color = if (enabled) RainColors.Ink else RainColors.TextMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_caret_down),
            contentDescription = null,
            tint = if (enabled) RainColors.Ink else RainColors.TextSubtle,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Pill segmented control on a cloud track: the selected segment is an ink pill with white text. */
@Composable
fun RainSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RainColors.Track, PillShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val background by animateColorAsState(
                if (selected) RainColors.Ink else Color.Transparent,
                tween(PRESS_MS),
                label = "segment",
            )
            val foreground = when {
                selected -> RainColors.White
                !enabled -> RainColors.TextSubtle
                else -> RainColors.TextMuted
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(PillShape)
                    .background(background)
                    .selectable(
                        selected = selected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelected(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = RainType.Strong.copy(color = foreground),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 44x24 toggle (48dp touch target): ink track when on, mist when off, white knob. */
@Composable
fun RainToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val track by animateColorAsState(
        when {
            !enabled -> RainColors.Gray15
            checked -> RainColors.Ink
            else -> RainColors.Gray20
        },
        tween(PRESS_MS),
        label = "toggleTrack",
    )
    val knobOffset by animateDpAsState(if (checked) 22.dp else 2.dp, tween(PRESS_MS), label = "toggleKnob")
    Box(
        modifier = modifier
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .minimumInteractiveComponentSize()
            .size(width = 44.dp, height = 24.dp)
            .clip(PillShape)
            .background(track),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(knobOffset.roundToPx(), 0) }
                .size(20.dp)
                .background(RainColors.White, CircleShape),
        )
    }
}

/**
 * Selectable row in a list of choices (e.g. which token to withdraw): a 20dp bordered row that
 * fills canvas-grey and shows a check when selected.
 */
@Suppress("LongParameterList") // Slot-style Compose API: every extra parameter is an optional knob.
@Composable
fun RainOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (selected || pressed) RainColors.SurfaceSubtle else RainColors.Surface,
        tween(PRESS_MS),
        label = "optionBg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, CardShape)
            .border(1.dp, RainColors.BorderSubtle, CardShape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RainStrong(title)
            if (subtitle != null) RainMuted(subtitle)
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = RainColors.Ink,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Home-grid tile: icon container over a Semibold label; the border firms up while pressed. */
@Composable
fun RainFeatureTile(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val border by animateColorAsState(
        if (pressed) RainColors.BorderStrong else RainColors.BorderSubtle,
        tween(PRESS_MS),
        label = "tileBorder",
    )
    Column(
        modifier = modifier
            .background(RainColors.Surface, CardShape)
            .border(1.dp, border, CardShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RainIconTile(icon)
        RainStrong(label)
    }
}

// region Previews

@Preview(name = "Inputs", showBackground = true, heightDp = 1250)
@Composable
@Suppress("LongMethod") // a gallery: one call per input variant, by design
private fun RainInputsGalleryPreview() {
    RainTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RainTextField(value = "", onValueChange = {}, placeholder = "RainTextField · placeholder")
            RainTextField(value = "A value the user typed", onValueChange = {})
            RainTextField(value = "Disabled", onValueChange = {}, enabled = false)

            RainField(label = "RainField", value = "", onValueChange = {}, placeholder = "you@example.com")
            RainField(
                label = "With helper",
                value = "+15551234567",
                onValueChange = {},
                helper = "With the country code, for example +15551234567",
            )
            RainField(
                label = "With an error",
                value = "not-an-email",
                onValueChange = {},
                helper = "Enter a valid email address",
                helperIsError = true,
            )
            RainField(label = "Disabled", value = "Locked once a code is out", onValueChange = {}, enabled = false)

            RainDropdownField(text = "EVM · Base Sepolia", onClick = {})
            RainDropdownField(text = "Disabled", onClick = {}, enabled = false)

            RainSegmentedControl(
                options = listOf("Portal MPC", "Rain Wallet", "Turnkey", "Privy"),
                selectedIndex = 1,
                onSelected = {},
            )
            RainSegmentedControl(
                options = listOf("Email", "Phone"),
                selectedIndex = 0,
                onSelected = {},
                enabled = false,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RainToggle(checked = true, onCheckedChange = {})
                RainToggle(checked = false, onCheckedChange = {})
                RainToggle(checked = true, onCheckedChange = {}, enabled = false)
            }

            RainOptionRow(title = "Selected option", subtitle = "With a subtitle", selected = true, onClick = {})
            RainOptionRow(title = "Unselected option", subtitle = null, selected = false, onClick = {})

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RainFeatureTile(
                    icon = R.drawable.ic_tile_wallet,
                    label = "Wallet & QR",
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                RainFeatureTile(
                    icon = R.drawable.ic_tile_coin,
                    label = "Balances",
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// endregion
