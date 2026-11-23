package com.rain.sdk.sample.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Rain brand palette, mirrored from the design system's `colors_and_type.css` tokens.
 *
 * The system is monochromatic neutrals plus one pink: pink is reserved for identity moments (the
 * wordmark, the icon tiles) and interaction states (press, focus). It is never a text colour, a
 * border, or a large fill. The neutral ramp carries a faint lilac cast in the mids.
 */
@Suppress("MagicNumber") // A palette is a list of literal values by definition.
object RainColors {
    // Pink system — tints and shades of the brand colour.
    val Pink = Color(0xFFFF2FB6)
    val PinkBlush = Color(0xFFFFAEFD)
    val PinkPale = Color(0xFFFFD7FE)

    // Neutral ramp.
    val White = Color(0xFFFFFFFF)
    val Gray05 = Color(0xFFFAFAFA) // canvas
    val Gray10 = Color(0xFFF2F2F2)
    val Gray15 = Color(0xFFE9E8EF) // cloud
    val Gray20 = Color(0xFFDBD5E4) // mist
    val Gray30 = Color(0xFFCABDDD) // lilac
    val Gray40 = Color(0xFFABA6B8)
    val Gray50 = Color(0xFF787185) // slate lilac
    val OffBlack = Color(0xFF121212) // ink

    // Functional state colours: small status elements (badges, dots) only, never fills.
    val Success = Color(0xFF00B87B)
    val Warn = Color(0xFFFF8A3D)
    val Danger = Color(0xFFFF262A)

    // Semantic roles.
    val Ink = OffBlack
    val TextMuted = Gray50
    val TextSubtle = Gray40
    val Canvas = White
    val Surface = White
    val SurfaceSubtle = Gray05
    val SurfaceDisabled = Gray10
    val Border = Gray20
    val BorderSubtle = Gray15
    val BorderStrong = Gray30
    val Track = Gray15
    val Focus = Pink
    val FocusHalo = Color(0x1FFF2FB6) // rgba(255,47,182,0.12)
    val SuccessTint = Color(0x1F00B87B) // rgba(0,184,123,0.12)
    val DangerTint = Color(0x1FFF262A) // rgba(255,38,42,0.12)
}
