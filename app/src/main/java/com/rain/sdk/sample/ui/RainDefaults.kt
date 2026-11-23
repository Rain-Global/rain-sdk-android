package com.rain.sdk.sample.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shape
import com.rain.sdk.sample.ui.theme.RainRadius

/*
 * Shared shape and motion constants for the sample's component kit, a Compose port of the Rain
 * design system's `components.css`: white canvas, hairline neutral borders on all four sides,
 * 20dp cards, pill buttons that go pink on press, 4dp inputs with a pink focus ring, and Phosphor
 * icons in the pink container. Sentence case everywhere; no emoji.
 */

/** Press feedback is a colour settle, not a bounce. */
internal const val PRESS_MS = 200

internal val PillShape: Shape = CircleShape
internal val CardShape: Shape = RoundedCornerShape(RainRadius.Card)
internal val SmallShape: Shape = RoundedCornerShape(RainRadius.Small)
