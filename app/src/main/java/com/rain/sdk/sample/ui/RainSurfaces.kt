package com.rain.sdk.sample.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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

/** Horizontal start and end of the icon tile's 160-degree gradient, as fractions of its width. */
private const val TILE_GRADIENT_START_X = 0.33f
private const val TILE_GRADIENT_END_X = 0.67f

/** White card: hairline subtle border on all sides, 20dp radius, 24dp padding, 16dp row gap. */
@Composable
fun RainCard(
    modifier: Modifier = Modifier,
    gap: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(RainColors.Surface, CardShape)
            .border(1.dp, RainColors.BorderSubtle, CardShape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Quiet canvas-tinted panel (no border) for status lines, notes, and empty states. */
@Composable
fun RainPanel(
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(RainColors.SurfaceSubtle, CardShape)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Title plus explanatory copy in a quiet panel. */
@Composable
fun RainNote(title: String, body: String, modifier: Modifier = Modifier) {
    RainPanel(modifier) {
        RainStrong(title)
        RainMuted(body)
    }
}

/** Failure copy: a danger badge and the message in ink. Colour stays on the badge, not the panel. */
@Composable
fun RainErrorPanel(message: String, modifier: Modifier = Modifier) {
    RainPanel(modifier, gap = 8.dp) {
        RainBadge("Error", tone = RainBadgeTone.Danger)
        Text(message, style = RainType.Body)
    }
}

/** Hairline divider inside a card. */
@Composable
fun RainDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(RainColors.BorderSubtle))
}

/** Space-between row: title on the left, trailing content on the right. */
@Composable
fun RainRow(
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

enum class RainBadgeTone { Neutral, Success, Danger, Outline, Pink }

/** 12sp product-chrome badge: 4dp radius, 4x8 padding. */
@Composable
fun RainBadge(text: String, modifier: Modifier = Modifier, tone: RainBadgeTone = RainBadgeTone.Neutral) {
    val (background, foreground) = when (tone) {
        RainBadgeTone.Neutral -> RainColors.Gray15 to RainColors.Ink
        RainBadgeTone.Success -> RainColors.SuccessTint to RainColors.Success
        RainBadgeTone.Danger -> RainColors.DangerTint to RainColors.Danger
        RainBadgeTone.Outline -> Color.Transparent to RainColors.TextMuted
        RainBadgeTone.Pink -> RainColors.Pink to RainColors.White
    }
    val border = if (tone == RainBadgeTone.Outline) {
        Modifier.border(1.dp, RainColors.BorderStrong, SmallShape)
    } else {
        Modifier
    }
    Box(
        modifier
            .background(background, SmallShape)
            .then(border)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, style = RainType.Badge.copy(color = foreground), maxLines = 1)
    }
}

/** 8dp status dot in a sanctioned state colour. */
@Composable
fun RainStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}

/** The Rain icon container: 48dp rounded square, soft pink gradient, white line icon. */
@Composable
fun RainIconTile(@DrawableRes icon: Int, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { 48.dp.toPx() }
    // CSS `160deg`: pale at the upper-left flowing to blush at the lower-right.
    val gradient = remember(sizePx) {
        Brush.linearGradient(
            colors = listOf(RainColors.PinkPale, RainColors.PinkBlush),
            start = Offset(sizePx * TILE_GRADIENT_START_X, 0f),
            end = Offset(sizePx * TILE_GRADIENT_END_X, sizePx),
        )
    }
    Box(
        modifier = modifier.size(48.dp).background(gradient, SmallShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = RainColors.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

// region Previews

@Preview(name = "Surfaces", showBackground = true, heightDp = 900)
@Composable
private fun RainSurfacesGalleryPreview() {
    RainTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RainCard {
                RainStrong("RainCard")
                RainMuted("20dp radius, hairline border, 24dp padding.")
                RainRow {
                    RainStrong("A row", Modifier.weight(1f))
                    RainBadge("Trailing")
                }
                RainDivider()
                RainRow {
                    RainStatusDot(RainColors.Success)
                    RainMuted("RainStatusDot beside RainRow content")
                }
            }

            RainPanel { RainMuted("RainPanel · the subtle status strip") }

            RainNote(
                title = "RainNote",
                body = "An explanatory note, used where a screen needs a sentence of guidance.",
            )

            RainErrorPanel("RainErrorPanel · the message shown when a call fails")

            RainRow {
                RainBadge("Neutral", tone = RainBadgeTone.Neutral)
                RainBadge("Success", tone = RainBadgeTone.Success)
                RainBadge("Danger", tone = RainBadgeTone.Danger)
            }
            RainRow {
                RainBadge("Outline", tone = RainBadgeTone.Outline)
                RainBadge("Pink", tone = RainBadgeTone.Pink)
            }

            RainRow {
                RainIconTile(icon = R.drawable.ic_tile_wallet)
                RainIconTile(icon = R.drawable.ic_tile_coin)
                RainIconTile(icon = R.drawable.ic_tile_secure)
            }
        }
    }
}

// endregion
