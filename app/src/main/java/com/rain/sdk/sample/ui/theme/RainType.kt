package com.rain.sdk.sample.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Rain type scale for the sample: two sizes (16 body, 32 heading) plus the 12px badge, which the
 * design system files as product chrome rather than content type. Hierarchy comes from weight
 * (Light vs Semibold) and colour (ink vs muted), never from extra sizes or capitalisation.
 *
 * Rain's brand face is Antique Legacy (Light 300 / Semibold 600). It is a licensed font and is not
 * checked into this public repo, so the sample renders on the platform sans (Roboto) at the same
 * two weights. To use the real face, drop `antique_legacy_light.otf` and
 * `antique_legacy_semibold.otf` into `app/src/main/res/font/` and point [fontFamily] at them:
 *
 * ```
 * FontFamily(
 *     Font(R.font.antique_legacy_light, FontWeight.Light),
 *     Font(R.font.antique_legacy_semibold, FontWeight.SemiBold),
 * )
 * ```
 */
object RainType {
    val fontFamily: FontFamily = FontFamily.Default

    private val platform = PlatformTextStyle(includeFontPadding = false)
    private val lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

    /** Headings: Semibold, set solid (line-height 100%), tight tracking. */
    val Title = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.03).em,
        color = RainColors.Ink,
        platformStyle = platform,
        lineHeightStyle = lineHeightStyle,
    )

    /** Body: Light, 150% line-height, the same value everywhere. */
    val Body = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.01).em,
        color = RainColors.Ink,
        platformStyle = platform,
        lineHeightStyle = lineHeightStyle,
    )

    /** Body stepped down in colour: descriptors, helper copy, secondary values. */
    val BodyMuted = Body.copy(color = RainColors.TextMuted)

    /** Body in Semibold: card titles, row titles, button text, emphasised values. */
    val Strong = Body.copy(fontWeight = FontWeight.SemiBold)

    /** Field labels and eyebrows: Semibold, sentence case, muted. */
    val Label = Strong.copy(color = RainColors.TextMuted)

    /** Badge chrome. */
    val Badge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.01).em,
        platformStyle = platform,
        lineHeightStyle = lineHeightStyle,
    )
}
