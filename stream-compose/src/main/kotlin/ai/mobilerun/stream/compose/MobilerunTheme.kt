// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every text style used by the default UI, independent of the host's Material theme. */
@Immutable
public data class StreamTypography(
    public val body: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    public val caption: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
    public val title: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
)

/**
 * React's semantic CSS tokens, converted from OKLCH to clipped sRGB and rounded to 8-bit channels.
 * All tokens can be overridden with copy(). Radius and typography follow the React utility sizes.
 */
@Immutable
public data class StreamThemeTokens(
    public val background: Color = Color(0xFFFFFFFF),
    public val foreground: Color = Color(0xFF0A0A0A),
    public val card: Color = Color(0xFFFFFFFF),
    public val cardForeground: Color = Color(0xFF0A0A0A),
    public val muted: Color = Color(0xFFF5F5F5),
    public val mutedForeground: Color = Color(0xFF737373),
    public val border: Color = Color(0xFFE5E5E5),
    public val ring: Color = Color(0xFFA1A1A1),
    public val primary: Color = Color(0xFF171717),
    public val primaryForeground: Color = Color(0xFFFAFAFA),
    public val accent: Color = Color(0xFFF5F5F5),
    public val accentForeground: Color = Color(0xFF171717),
    public val destructive: Color = Color(0xFFE7000B),
    public val radius: Dp = 8.dp,
    public val typography: StreamTypography = StreamTypography(),
) {
    public companion object {
        /** React light defaults. */
        public val Light: StreamThemeTokens = StreamThemeTokens()
        /** React dark defaults; border preserves the 10% white alpha. */
        public val Dark: StreamThemeTokens = StreamThemeTokens(
            background = Color(0xFF0A0A0A), foreground = Color(0xFFFAFAFA),
            card = Color(0xFF171717), cardForeground = Color(0xFFFAFAFA),
            muted = Color(0xFF262626), mutedForeground = Color(0xFFA1A1A1),
            border = Color.White.copy(alpha = 0.1f), ring = Color(0xFF737373),
            primary = Color(0xFFFAFAFA), primaryForeground = Color(0xFF171717),
            accent = Color(0xFF262626), accentForeground = Color(0xFFFAFAFA),
            destructive = Color(0xFFFF6467),
        )
    }
}

private val LocalTokens = staticCompositionLocalOf<StreamThemeTokens?> { null }

/** Theme scope for the optional default UI. Omitted tokens follow system dark mode. */
@Composable
public fun MobilerunTheme(tokens: StreamThemeTokens? = null, content: @Composable () -> Unit) {
    val resolved = tokens ?: if (isSystemInDarkTheme()) StreamThemeTokens.Dark else StreamThemeTokens.Light
    CompositionLocalProvider(LocalTokens provides resolved, content = content)
}

/** Resolved tokens, also usable by replacement slots. Defaults follow system dark mode. */
public val streamThemeTokens: StreamThemeTokens
    @Composable get() = LocalTokens.current ?: if (isSystemInDarkTheme()) StreamThemeTokens.Dark else StreamThemeTokens.Light
