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
public class StreamTypography(
    public val body: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    public val caption: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
    public val title: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
    ),
) {
    override fun equals(other: Any?): Boolean =
        other is StreamTypography &&
            body == other.body &&
            caption == other.caption &&
            title == other.title

    override fun hashCode(): Int {
        var result = body.hashCode()
        result = 31 * result + caption.hashCode()
        result = 31 * result + title.hashCode()
        return result
    }

    override fun toString(): String =
        "StreamTypography(body=$body, caption=$caption, title=$title)"

    /** Creates a value with selected properties replaced. */
    public fun copy(
        body: TextStyle = this.body,
        caption: TextStyle = this.caption,
        title: TextStyle = this.title,
    ): StreamTypography = StreamTypography(
        body, caption, title,
    )
}


/**
 * React's semantic CSS tokens, converted from OKLCH to clipped sRGB and rounded to 8-bit channels.
 * All tokens can be overridden with copy(). Radius and typography follow the React utility sizes.
 */
@Immutable
public class StreamThemeTokens(
    public val background: Color = Color(0xFFFFFFFF),
    public val foreground: Color = Color(0xFF0A0A0A),
    public val card: Color = Color(0xFFFFFFFF),
    public val cardForeground: Color = Color(0xFF0A0A0A),
    public val muted: Color = Color(0xFFF5F5F5),
    public val mutedForeground: Color = Color(0xFF737373),
    public val border: Color = Color(0xFFE5E5E5),
    /** Reserved for host-defined focus indicators in replacement slots. */
    public val ring: Color = Color(0xFFA1A1A1),
    public val primary: Color = Color(0xFF171717),
    public val primaryForeground: Color = Color(0xFFFAFAFA),
    /** Reserved for host-defined accents in replacement slots. */
    public val accent: Color = Color(0xFFF5F5F5),
    /** Reserved for text on host-defined accents in replacement slots. */
    public val accentForeground: Color = Color(0xFF171717),
    public val destructive: Color = Color(0xFFE7000B),
    public val radius: Dp = 8.dp,
    public val typography: StreamTypography = StreamTypography(),
) {
    override fun equals(other: Any?): Boolean =
        other is StreamThemeTokens &&
            background == other.background &&
            foreground == other.foreground &&
            card == other.card &&
            cardForeground == other.cardForeground &&
            muted == other.muted &&
            mutedForeground == other.mutedForeground &&
            border == other.border &&
            ring == other.ring &&
            primary == other.primary &&
            primaryForeground == other.primaryForeground &&
            accent == other.accent &&
            accentForeground == other.accentForeground &&
            destructive == other.destructive &&
            radius == other.radius &&
            typography == other.typography

    override fun hashCode(): Int {
        var result = background.hashCode()
        result = 31 * result + foreground.hashCode()
        result = 31 * result + card.hashCode()
        result = 31 * result + cardForeground.hashCode()
        result = 31 * result + muted.hashCode()
        result = 31 * result + mutedForeground.hashCode()
        result = 31 * result + border.hashCode()
        result = 31 * result + ring.hashCode()
        result = 31 * result + primary.hashCode()
        result = 31 * result + primaryForeground.hashCode()
        result = 31 * result + accent.hashCode()
        result = 31 * result + accentForeground.hashCode()
        result = 31 * result + destructive.hashCode()
        result = 31 * result + radius.hashCode()
        result = 31 * result + typography.hashCode()
        return result
    }

    override fun toString(): String =
        "StreamThemeTokens(background=$background, foreground=$foreground, card=$card, " +
        "cardForeground=$cardForeground, muted=$muted, mutedForeground=$mutedForeground, " +
        "border=$border, ring=$ring, primary=$primary, primaryForeground=$primaryForeground, " +
        "accent=$accent, accentForeground=$accentForeground, destructive=$destructive, " +
        "radius=$radius, typography=$typography)"

    /** Creates a value with selected properties replaced. */
    public fun copy(
        background: Color = this.background,
        foreground: Color = this.foreground,
        card: Color = this.card,
        cardForeground: Color = this.cardForeground,
        muted: Color = this.muted,
        mutedForeground: Color = this.mutedForeground,
        border: Color = this.border,
        ring: Color = this.ring,
        primary: Color = this.primary,
        primaryForeground: Color = this.primaryForeground,
        accent: Color = this.accent,
        accentForeground: Color = this.accentForeground,
        destructive: Color = this.destructive,
        radius: Dp = this.radius,
        typography: StreamTypography = this.typography,
    ): StreamThemeTokens = StreamThemeTokens(
        background,
        foreground,
        card,
        cardForeground,
        muted,
        mutedForeground,
        border,
        ring,
        primary,
        primaryForeground,
        accent,
        accentForeground,
        destructive,
        radius,
        typography,
    )

    public companion object {
        /** React light defaults. */
        public fun light(): StreamThemeTokens = StreamThemeTokens()
        /** React dark defaults; border preserves the 10% white alpha. */
        public fun dark(): StreamThemeTokens = StreamThemeTokens(
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

private val LocalTokens = staticCompositionLocalOf { StreamThemeTokens.light() }

/** Independent theme scope. Light by default; [followSystem] explicitly opts into system dark mode. */
@Composable
public fun StreamTheme(
    tokens: StreamThemeTokens? = null,
    followSystem: Boolean = false,
    content: @Composable () -> Unit,
) {
    val resolved = tokens ?: if (followSystem && isSystemInDarkTheme()) {
        StreamThemeTokens.dark()
    } else StreamThemeTokens.light()
    CompositionLocalProvider(LocalTokens provides resolved, content = content)
}

/** Theme access for components and replacement slots. */
public object StreamTheme {
    /** Resolved tokens; light when no theme scope is present. */
    public val tokens: StreamThemeTokens
        @Composable get() = LocalTokens.current
}
