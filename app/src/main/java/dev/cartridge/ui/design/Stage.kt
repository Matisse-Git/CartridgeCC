package dev.cartridge.ui.design

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cartridge.shell.Tab
import dev.cartridge.ui.design.Typo.tabular
import kotlinx.coroutines.launch
import kotlin.math.sign

/**
 * The screens of the console share one stage: one top bar and one hint bar that stay put while what is under them
 * changes. A screen hands its hints to the stage with [ScreenHints] instead of drawing its own bar.
 */
class HintHost {
    private class Entry(val hints: List<Hint>, val context: String?)
    private val entries = mutableStateMapOf<Any, Entry>()
    private val order = mutableStateListOf<Any>()
    fun put(owner: Any, hints: List<Hint>, context: String?) {
        entries[owner] = Entry(hints, context)
        if (owner !in order) order.add(owner)
    }
    fun remove(owner: Any) { entries.remove(owner); order.remove(owner) }
    /** The hints of the screen that took the stage last, or nothing when no screen is active. */
    val hints: List<Hint>? get() = order.lastOrNull()?.let { entries[it]?.hints }
    val context: String? get() = order.lastOrNull()?.let { entries[it]?.context }
}

val LocalHintHost = staticCompositionLocalOf<HintHost?> { null }
/** True inside the stage, where a screen's own top bar is left to the stage's. */
val LocalSharedTopBar = staticCompositionLocalOf { false }

/** A screen's hint bar: handed to the stage while [active], or drawn in place where there is no stage. */
@Composable fun ScreenHints(hints: List<Hint>, active: Boolean, modifier: Modifier = Modifier, context: String? = null) {
    val host = LocalHintHost.current
    if (host == null) { if (active) HintBar(hints, modifier, context); return }
    val owner = remember { Any() }
    SideEffect { if (active) host.put(owner, hints, context) else host.remove(owner) }
    DisposableEffect(owner) { onDispose { host.remove(owner) } }
}

/**
 * The stage's hint bar. A hint both screens share stays where it is; one that is new fades in beside it, and the row
 * makes room smoothly. With no active screen the bar fades out and keeps its last hints while it does.
 */
@Composable fun StageHints(host: HintHost, visible: Boolean, modifier: Modifier = Modifier) {
    val motion = LocalDesignMotion.current
    val current = host.hints
    var kept by remember { mutableStateOf<List<Hint>>(emptyList()) }
    var keptContext by remember { mutableStateOf<String?>(null) }
    if (current != null) SideEffect { kept = current; keptContext = host.context }
    val shown = visible && current != null
    val alpha by animateFloatAsState(if (shown) 1f else 0f, motion.fade(), label = "stage-hints")
    if (alpha <= 0f) return
    val hints = current ?: kept
    Row(modifier.fillMaxWidth().height(Layout.HintBar).padding(horizontal = Layout.Margin).alpha(alpha).testTag("stage-hints"),
        verticalAlignment = Alignment.CenterVertically) {
        Text((if (current != null) host.context else keptContext).orEmpty(), Typo.Caption.tabular(), Modifier.weight(1f).testTag("hint-context"), Palette.Tertiary, lines = 1)
        Row(Modifier.testTag("hints").then(if (motion.reduced) Modifier else Modifier.animateContentSize(androidx.compose.animation.core.tween(DesignMotion.FADE_MS, easing = DesignMotion.Ease))),
            horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
            hints.forEach { hint ->
                key(hint.pad, hint.label) {
                    val appear = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { appear.animateTo(1f, motion.fade()) }
                    Row((if (hint.action != null) Modifier.tap(hint.tag ?: "hint-${hint.label.lowercase()}", action = hint.action) else Modifier).alpha(appear.value),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(hint.pad, hint.primary)
                        Text(hint.label, Typo.Callout.copy(fontWeight = if (hint.primary) FontWeight.SemiBold else FontWeight.Medium), lines = 1)
                    }
                }
            }
        }
    }
}

/**
 * The tabs sit left to right, so moving between them is moving sideways: the screen you leave slides a little toward
 * the side it lies on and fades, the one you reach slides in from its side. [amount] is how present a tab is (0..1).
 */
@Stable class TabStage(initial: Tab) {
    internal val amounts = Tab.entries.associateWith { Animatable(if (it == initial) 1f else 0f) }
    internal var from by mutableStateOf(initial)
    internal var to by mutableStateOf(initial)
    fun amount(tab: Tab) = amounts.getValue(tab).value
    fun shown(tab: Tab) = amount(tab) > .001f || tab == to
    /** Sideways offset in design pixels for [tab] at its present amount. */
    fun offset(tab: Tab, travel: Float): Float {
        val side = if (tab == to) sign((tab.ordinal - from.ordinal).toFloat()) else sign((tab.ordinal - to.ordinal).toFloat())
        return (1f - amount(tab)) * travel * side
    }
}

@Composable fun rememberTabStage(current: Tab): TabStage {
    val motion = LocalDesignMotion.current
    val stage = remember { TabStage(current) }
    LaunchedEffect(current) {
        if (current == stage.to) return@LaunchedEffect
        stage.from = stage.to; stage.to = current
        Tab.entries.forEach { tab -> launch { stage.amounts.getValue(tab).animateTo(if (tab == current) 1f else 0f, motion.progress(DesignMotion.TAB_MS)) } }
    }
    return stage
}

/** A tab's layer of the stage: present by its amount, slid toward its side. */
@Composable fun TabLayer(stage: TabStage, tab: Tab, content: @Composable () -> Unit) {
    val travel = LocalDesignMotion.current.travel(DesignMotion.TAB_TRAVEL)
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = stage.amount(tab); translationX = stage.offset(tab, travel).dp.toPx() }) { content() }
}

/**
 * Where a box stands on screen, in design pixels: its top left, its size, how far it is turned and how near it comes.
 * One box travels between screens by moving from one screen's pose to the next.
 */
@Immutable data class BoxPose(val x: Float, val y: Float, val width: Float, val height: Float, val angle: Float, val near: Float = 0f) {
    fun toward(other: BoxPose, t: Float): BoxPose {
        fun mix(a: Float, b: Float) = a + (b - a) * t
        return BoxPose(mix(x, other.x), mix(y, other.y), mix(width, other.width), mix(height, other.height), mix(angle, other.angle), mix(near, other.near))
    }
}

/** A box drawn at [pose]. */
@Composable fun PosedBox(look: BoxLook, pose: BoxPose, modifier: Modifier = Modifier, focused: Boolean = true, alpha: Float = 1f, dim: Float = 0f) {
    GameBox(look, pose.width.dp, pose.height.dp, pose.angle, modifier.absoluteOffset(pose.x.dp, pose.y.dp), translateZ = pose.near.dp,
        focused = focused, alpha = alpha, dim = dim)
}

/** How far through a staggered entrance item [index] of [count] is when the whole is [t] through (0..1). */
fun stagger(t: Float, index: Int, step: Float = .08f): Float = ((t - index * step) / (1f - index * step).coerceAtLeast(.2f)).coerceIn(0f, 1f)


/**
 * Where the focused Systems card's spines stand, in design pixels, so "Browse games" can fly them into the shelf: each
 * spine turns from spine-on to its place on the shelf.
 */
class SpineRects {
    class Spot(val x: Float, val y: Float, val width: Float, val height: Float, val opacity: Float)
    private val spots = LinkedHashMap<String, Spot>()
    fun clear() = spots.clear()
    fun put(id: String, bounds: androidx.compose.ui.geometry.Rect, density: Float, opacity: Float) {
        spots[id] = Spot(bounds.left / density, bounds.top / density, bounds.width / density, bounds.height / density, opacity)
    }
    fun snapshot(): Map<String, Spot> = LinkedHashMap(spots)
}

val LocalSpineRects = staticCompositionLocalOf<SpineRects?> { null }

/** A box standing spine-on where a spine is drawn: turned a quarter so only its spine faces the viewer. */
fun spinePose(spot: SpineRects.Spot, media: dev.cartridge.domain.Media): BoxPose {
    val shape = media.shape
    val h = spot.height
    val w = h * shape.aspect
    return BoxPose(spot.x + spot.width / 2f - w / 2f, spot.y, w, h, 90f)
}
