package dev.cartridge.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.cartridge.R

/**
 * The redesign's tokens (cartridge-design/DESIGN_SPEC.md §2). True black canvas, grayscale UI; colour comes only
 * from game media. Every new screen draws from these and nothing else.
 */
object Palette {
    val Canvas = Color(0xFF000000)
    val Surface1 = Color(0xFF141416)
    val Surface2 = Color(0xFF1C1C1E)
    val Surface3 = Color(0xFF2C2C2E)
    val Separator = Color(0x14FFFFFF)
    /** Glass panels: surface-2 at 74 %. The blur comes from blurring the layer beneath, which this app owns. */
    val Glass = Color(0xBD1C1C1E)
    val GlassBorder = Color(0x14FFFFFF)
    val Label = Color(0xFFF5F5F7)
    val Secondary = Color(0xFFA1A1A6)
    val Tertiary = Color(0xFF8E8E93)
    /** The light gray the mockups use for quiet text over media. */
    val Quiet = Color(0xFFC7C7CC)
    val Soft = Color(0xFFD1D1D6)
    /** Secondary text on a white (focused) fill. */
    val OnLabelSecondary = Color(0xFF3A3A3C)
    val Accent = Color(0xFF0A84FF)
    val AccentText = Color(0xFF409CFF)
    val Success = Color(0xFF30D158)
    val Destructive = Color(0xFFFF453A)
    val DestructiveText = Color(0xFFFF6961)
    val Favorite = Color(0xFFFFD60A)
    /** Fills over media: chips, pills, quiet buttons. */
    val Fill = Color(0x24FFFFFF)
    val FillQuiet = Color(0x14FFFFFF)
    val ChipRest = Color(0x8C28282A)
    val ChipSelected = Color(0x33FFFFFF)
    val GlyphLine = Color(0x8CF5F5F7)
    val ShoulderLine = Color(0x59F5F5F7)
}

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(R.font.inter, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

/** Type — Inter, tabular numerals wherever times or counts are shown ([tabular]). */
object Typo {
    private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    private fun style(size: Float, weight: Int, tracking: Float = 0f, line: Float = 1.25f) = TextStyle(fontFamily = Inter, fontSize = size.sp,
        fontWeight = FontWeight(weight), letterSpacing = tracking.em, lineHeight = (size * line).sp, lineHeightStyle = trim, color = Palette.Label)
    val Display = style(58f, 800, -0.035f, 1.04f)
    val Title = style(28f, 700, -0.02f, 1.15f)
    val TitleLarge = style(30f, 700, -0.02f, 1.15f)
    val Headline = style(17f, 700, -0.01f)
    val Body = style(15f, 500)
    val Callout = style(14f, 500)
    val Caption = style(13f, 500)
    val Small = style(12f, 500)
    /** Section labels: callers upper-case the text. */
    val Section = style(12f, 600, 0.04f)
    fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")
}

object Radii {
    val R8 = 8.dp
    val R12 = 12.dp
    val R16 = 16.dp
    val R22 = 22.dp
    val R28 = 28.dp
    val Pill = 999.dp
}

object Layout {
    val Margin = 40.dp
    val TopBar = 76.dp
    val HintBar = 64.dp
    val Target = 44.dp
    const val WIDTH = 1280f
    const val HEIGHT = 720f
}

/** Motion (§2). Reduced motion cuts every spec. */
class DesignMotion(val reduced: Boolean) {
    fun <T> shelf(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(450, easing = Ease)
    fun <T> lift(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(250, easing = Ease)
    fun <T> pause(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(220, easing = Ease)
    fun <T> fade(ms: Int = 300): FiniteAnimationSpec<T> = if (reduced) snap() else tween(ms, easing = Ease)
    companion object {
        val Ease = CubicBezierEasing(.2f, .8f, .2f, 1f)
        const val PRESSED = .97f
        const val PREVIEW_DWELL_MS = 1500L
    }
}

val LocalDesignMotion = staticCompositionLocalOf { DesignMotion(false) }

/**
 * The 1280×720 reference canvas. Inside it one dp is one pixel of the mockups, whatever the screen: the density is
 * chosen so the canvas fills the shorter fit of the display, as the spec's "scale with density" asks.
 */
@Composable fun DesignCanvas(reducedMotion: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val outer = LocalDensity.current
        val widthPx = with(outer) { maxWidth.toPx() }
        val heightPx = with(outer) { maxHeight.toPx() }
        val scale = minOf(widthPx / Layout.WIDTH, heightPx / Layout.HEIGHT)
        CompositionLocalProvider(LocalDensity provides Density(scale, 1f), LocalDesignMotion provides DesignMotion(reducedMotion)) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}
