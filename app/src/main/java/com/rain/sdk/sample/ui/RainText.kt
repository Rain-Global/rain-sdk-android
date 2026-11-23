package com.rain.sdk.sample.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rain.sdk.sample.ui.theme.RainColors
import com.rain.sdk.sample.ui.theme.RainTheme
import com.rain.sdk.sample.ui.theme.RainType

/*
 * Part of the sample's component kit, a Compose port of the Rain design system's `components.css`: white
 * canvas, hairline neutral borders on all four sides, 20dp cards, pill buttons that go pink on
 * press, 4dp inputs with a pink focus ring, and Phosphor icons in the pink container. Sentence case
 * everywhere; no emoji.
 */

@Composable
fun RainLabel(text: String, modifier: Modifier = Modifier) = Text(text, style = RainType.Label, modifier = modifier)

@Composable
fun RainStrong(text: String, modifier: Modifier = Modifier) = Text(text, style = RainType.Strong, modifier = modifier)

@Composable
fun RainMuted(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) =
    Text(text, style = RainType.BodyMuted, modifier = modifier, textAlign = textAlign)

/** A field label over its value, e.g. "USDC contract" over an address. */
@Composable
fun RainKeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        RainLabel(label)
        Text(value, style = RainType.Body)
    }
}

/** A headline figure with its unit: `1,250.00 USDC`. */
@Composable
fun RainAmount(value: String, unit: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(value, style = RainType.Title, modifier = Modifier.alignByBaseline())
        Text(unit, style = RainType.Label, modifier = Modifier.alignByBaseline())
    }
}

/** Underlined ink text that turns pink while pressed; for transaction hashes and addresses. */
@Composable
fun RainLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color by animateColorAsState(
        if (pressed) RainColors.Pink else RainColors.Ink,
        tween(PRESS_MS),
        label = "link",
    )
    Text(
        text = text,
        style = RainType.Body.copy(color = color, textDecoration = TextDecoration.Underline),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick),
    )
}

// region Previews

@Preview(name = "Text styles", showBackground = true)
@Composable
private fun RainTextGalleryPreview() {
    RainTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RainLabel("Label · section heading over a field")
            RainStrong("Strong · a card title")
            RainMuted("Muted · the descriptor under a title")
            RainMuted("Muted, centred", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            RainKeyValue(label = "USDC contract", value = "0x036CbD53842c5426634e7929541eC2318f3dCF7e")
            RainAmount(value = "1,250.00", unit = "USDC")
            RainLink(text = "0x9fd7a2c4…b2c73e15", onClick = {})
            RainLink(
                text = "A link long enough to need the single-line ellipsis that RainLink applies",
                onClick = {},
                maxLines = 1,
            )
        }
    }
}

// endregion
