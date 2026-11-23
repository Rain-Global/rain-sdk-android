package com.rain.sdk.sample.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
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

/** Full-height white canvas: 8dp top, 16dp sides, 32dp bottom, 24dp between sections. */
@Composable
fun RainScreen(
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RainColors.Canvas)
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        content = content,
    )
}

/** Wordmark on the left, environment badge on the right. */
@Composable
fun RainHomeHeader(environmentLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.rain_wordmark),
            contentDescription = "Rain",
            modifier = Modifier.width(66.dp).height(20.dp),
        )
        RainBadge(environmentLabel)
    }
}

/** Back affordance for feature screens: a round 48dp arrow target and the destination's name. */
@Composable
fun RainBackHeader(onBack: () -> Unit, destination: String = "Home") {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).offset(x = (-12).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RainIconButton(icon = R.drawable.ic_arrow_left, contentDescription = "Back", onClick = onBack)
        Text(destination, style = RainType.Label)
    }
}

/** Screen title (32 Semibold) with an optional descriptor in muted body. */
@Composable
fun RainTitleBlock(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = RainType.Title)
        if (subtitle != null) Text(subtitle, style = RainType.BodyMuted)
    }
}

// region Previews

@Preview(name = "Scaffold · home header", showBackground = true)
@Composable
private fun RainScaffoldHomePreview() {
    RainTheme {
        RainScreen(PaddingValues()) {
            RainHomeHeader(environmentLabel = "Sandbox")
            RainTitleBlock(title = "SDK sample", subtitle = "Connect a wallet provider to start.")
        }
    }
}

@Preview(name = "Scaffold · feature header", showBackground = true)
@Composable
private fun RainScaffoldFeaturePreview() {
    RainTheme {
        RainScreen(PaddingValues()) {
            RainBackHeader(onBack = {})
            RainTitleBlock(title = "Withdraw collateral", subtitle = "EVM · Base Sepolia")
            RainTitleBlock(title = "Title with no subtitle")
        }
    }
}

// endregion
