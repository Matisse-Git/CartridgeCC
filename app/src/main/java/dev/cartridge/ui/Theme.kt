package dev.cartridge.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Warm charcoal surfaces, one text family, one accent. Every screen draws from these and nothing else. */
object Ink {
    /** Matches the renderer's clear colour, so pages and the 3D stage read as one material. */
    val Stage = Color(0xFF161514)
    val Surface = Color(0xFF1F1E1C)
    val Raised = Color(0xFF2B2926)
    val Line = Color(0x1AFFFFFF)
    val Text = Color(0xFFF2EEE6)
    val Soft = Color(0xFFB5AFA4)
    val Faint = Color(0xFF7F7A71)
    val Warning = Color(0xFFE6B27A)
    val Danger = Color(0xFFE79482)
    val Scrim = Color(0xB3100F0E)
}

enum class Accent(val title: String, val color: Color) {
    Sand("Sand", Color(0xFFD9C8A9)), Sage("Sage", Color(0xFFA8C3A3)), Sky("Sky", Color(0xFF9EBBD6)),
    Rose("Rose", Color(0xFFDDA9A6)), Paper("Paper", Color(0xFFEDEAE3));
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Sand }
}

/** Five sizes. If a sixth seems necessary, the layout is wrong. */
object Type {
    val Display = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp)
    val Title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp)
    val Heading = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val Label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val Caption = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp)
}

/** A 4dp grid. Screen edges use [Edge]; related things sit [S] apart, unrelated things [L] or more. */
object Space {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 20.dp
    val XL = 32.dp
    val Edge = 28.dp
    val Row = 44.dp
}

object Radius {
    val Row = 10.dp
    val Sheet = 18.dp
    val Chip = 6.dp
}

/** One motion vocabulary. Reduced motion replaces every spec with a cut; ordering never depends on it. */
class Motion(val reduced: Boolean) {
    /** Focus moving between rows: fast, no overshoot. */
    fun <T> focus(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 1400f)
    /** Sheets and pages arriving. */
    fun <T> enter(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(220, easing = Standard)
    /** Leaving is quicker than arriving. */
    fun <T> exit(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(140, easing = Standard)
    /** Chrome that fades with a mode change: hints, titles, status. */
    fun <T> fade(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(180, easing = Standard)
    companion object { val Standard = CubicBezierEasing(.2f, 0f, 0f, 1f) }
}

val LocalAccent = staticCompositionLocalOf { Accent.Sand.color }
val LocalMotion = staticCompositionLocalOf { Motion(false) }

@Composable fun CartridgeTheme(accent: Accent = Accent.Sand, reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccent provides accent.color, LocalMotion provides Motion(reducedMotion)) {
        MaterialTheme(colorScheme = darkColorScheme(primary = accent.color, onPrimary = Ink.Stage, background = Ink.Stage,
            onBackground = Ink.Text, surface = Ink.Surface, onSurface = Ink.Text, onSurfaceVariant = Ink.Soft, outline = Ink.Faint,
            error = Ink.Danger), typography = Typography(bodyLarge = Type.Body, bodyMedium = Type.Body, titleLarge = Type.Title,
            titleMedium = Type.Heading, labelMedium = Type.Label, labelSmall = Type.Caption), content = content)
    }
}
