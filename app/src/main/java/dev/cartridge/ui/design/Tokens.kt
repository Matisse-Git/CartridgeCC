package dev.cartridge.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
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

/**
 * Motion (§2), one set of durations for the whole app, all on the spec's ease. Reduced motion moves nothing: whatever
 * would travel arrives at once, and whatever would fade crossfades in [REDUCED_MS], so screens still connect.
 */
class DesignMotion(val reduced: Boolean) {
    fun <T> shelf(): FiniteAnimationSpec<T> = move(SHELF_MS)
    fun <T> lift(): FiniteAnimationSpec<T> = move(LIFT_MS)
    fun <T> pause(): FiniteAnimationSpec<T> = fade(PAUSE_MS)
    fun <T> fade(ms: Int = FADE_MS): FiniteAnimationSpec<T> = tween(if (reduced) REDUCED_MS else ms, easing = Ease)
    /** Something that travels: a tab's slide, a row's swap, a box's flight. Under reduced motion it is already there. */
    fun <T> move(ms: Int): FiniteAnimationSpec<T> = if (reduced) snap() else tween(ms, easing = Ease)
    /** A progress from one screen to the next (0..1): under reduced motion a crossfade's length, so what fades still fades. */
    fun <T> progress(ms: Int): FiniteAnimationSpec<T> = tween(if (reduced) REDUCED_MS else ms, easing = if (reduced) LinearEasing else Ease)
    /** How far things travel; nothing travels under reduced motion. */
    fun travel(distance: Float) = if (reduced) 0f else distance
    companion object {
        val Ease = CubicBezierEasing(.2f, .8f, .2f, 1f)
        const val PRESSED = .97f
        const val PREVIEW_DWELL_MS = 1500L
        const val FADE_MS = 180
        const val LIFT_MS = 250
        const val PAUSE_MS = 220
        const val SHEET_MS = 300
        const val TAB_MS = 360
        const val RISE_MS = 380
        const val ROW_MS = 420
        const val SHELF_MS = 450
        const val FLIGHT_MS = 520
        const val START_MS = 600
        const val REDUCED_MS = 120
        /** How far a tab's screen slides as it comes and goes. */
        const val TAB_TRAVEL = 80f
        /** How far an opened page slides in from the right. */
        const val PAGE_TRAVEL = 40f
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
