package dev.cartridge.domain

/**
 * The buttons of a pad as Android names them, and as LibretroDroid hands them to a core: the pad button of the same
 * letter. They are both the physical buttons a player presses and the keys a core reads. Names are stored ids:
 * append, never rename or reorder.
 */
enum class PadKey(val title: String) {
    A("A"), B("B"), X("X"), Y("Y"), L1("L1"), R1("R1"), L2("L2"), R2("R2"), L3("L3"), R3("R3"), Start("Start"), Select("Select");
}

/** One button of a console's own pad: its name on that console, and the key its core reads it as (docs/CONSOLES). */
class PadButton(val name: String, val key: PadKey)

/**
 * A console's buttons: which physical button presses each of its own. Keyed by the button's [PadButton.key]; every
 * button has exactly one physical button and no two share one. A physical button in no entry sends nothing.
 */
object ButtonMap {
    /** What the console's games have always been sent: each key from the physical button of the same name. */
    fun default(system: GameSystem): Map<PadKey, PadKey> = system.pad.associate { it.key to it.key }

    /** [button] is pressed with [physical]; the button that had it trades places and takes [button]'s old one. */
    fun assign(map: Map<PadKey, PadKey>, button: PadKey, physical: PadKey): Map<PadKey, PadKey> {
        val previous = map[button] ?: return map
        val holder = map.entries.firstOrNull { it.value == physical && it.key != button }?.key
        return map + (button to physical) + listOfNotNull(holder?.let { it to previous })
    }

    /** The next physical button for [button], left or right through every one, trading places as [assign] does. */
    fun step(map: Map<PadKey, PadKey>, button: PadKey, delta: Int): Map<PadKey, PadKey> {
        val current = map[button] ?: return map
        val keys = PadKey.entries
        return assign(map, button, keys[Math.floorMod(keys.indexOf(current) + delta, keys.size)])
    }

    fun encode(map: Map<PadKey, PadKey>) = map.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.name}=${it.value.name}" }

    /**
     * A stored map for [system], or null when there is none or it no longer fits the console: an unknown button, a
     * physical button used twice, or a button missing. Null means the console's default.
     */
    fun decode(system: GameSystem, value: String?): Map<PadKey, PadKey>? {
        if (value.isNullOrBlank()) return null
        val pairs = value.split(',').map { entry ->
            val (button, physical) = entry.split('=').takeIf { it.size == 2 } ?: return null
            (PadKey.entries.firstOrNull { it.name == button } ?: return null) to (PadKey.entries.firstOrNull { it.name == physical } ?: return null)
        }
        val map = pairs.toMap()
        val buttons = system.pad.map { it.key }.toSet()
        return map.takeIf { pairs.size == map.size && map.keys == buttons && map.values.toSet().size == map.size }
    }
}

/** Each console's standard pad, read from its pinned core (docs/CONSOLES). Buttons only an arcade stick has are left out. */
internal object Pads {
    private fun pad(vararg buttons: Pair<String, PadKey>) = buttons.map { (name, key) -> PadButton(name, key) }
    val GAME_BOY = pad("A" to PadKey.A, "B" to PadKey.B, "Start" to PadKey.Start, "Select" to PadKey.Select)
    val GAME_BOY_ADVANCE = pad("A" to PadKey.A, "B" to PadKey.B, "L" to PadKey.L1, "R" to PadKey.R1, "Start" to PadKey.Start, "Select" to PadKey.Select)
    val PLAYSTATION = pad("Cross" to PadKey.B, "Circle" to PadKey.A, "Square" to PadKey.Y, "Triangle" to PadKey.X,
        "L1" to PadKey.L1, "R1" to PadKey.R1, "L2" to PadKey.L2, "R2" to PadKey.R2, "Start" to PadKey.Start, "Select" to PadKey.Select)
    val SUPER_NINTENDO = pad("A" to PadKey.A, "B" to PadKey.B, "X" to PadKey.X, "Y" to PadKey.Y, "L" to PadKey.L1, "R" to PadKey.R1,
        "Start" to PadKey.Start, "Select" to PadKey.Select)
    // FCEUmm's turbo buttons are off unless a core option it is not given turns them on; A+B is always there.
    val NES = pad("A" to PadKey.A, "B" to PadKey.B, "A+B" to PadKey.L3, "Start" to PadKey.Start, "Select" to PadKey.Select)
    val GENESIS = pad("A" to PadKey.Y, "B" to PadKey.B, "C" to PadKey.A, "X" to PadKey.L1, "Y" to PadKey.X, "Z" to PadKey.R1,
        "Start" to PadKey.Start, "Mode" to PadKey.Select)
    val MASTER_SYSTEM = pad("1" to PadKey.B, "2" to PadKey.A, "Pause" to PadKey.Start)
    val GAME_GEAR = pad("1" to PadKey.B, "2" to PadKey.A, "Start" to PadKey.Start)
    val SATURN = pad("A" to PadKey.B, "B" to PadKey.A, "C" to PadKey.R1, "X" to PadKey.Y, "Y" to PadKey.X, "Z" to PadKey.L1,
        "L" to PadKey.L2, "R" to PadKey.R2, "Start" to PadKey.Start)
    val DREAMCAST = pad("A" to PadKey.B, "B" to PadKey.A, "X" to PadKey.Y, "Y" to PadKey.X, "L trigger" to PadKey.L2, "R trigger" to PadKey.R2,
        "Start" to PadKey.Start)
    val PLAYSTATION_2 = pad("Cross" to PadKey.B, "Circle" to PadKey.A, "Square" to PadKey.Y, "Triangle" to PadKey.X,
        "L1" to PadKey.L1, "R1" to PadKey.R1, "L2" to PadKey.L2, "R2" to PadKey.R2, "L3" to PadKey.L3, "R3" to PadKey.R3,
        "Start" to PadKey.Start, "Select" to PadKey.Select)
    val GAMECUBE = pad("A" to PadKey.A, "B" to PadKey.B, "X" to PadKey.X, "Y" to PadKey.Y, "Z" to PadKey.R1, "L" to PadKey.L2, "R" to PadKey.R2,
        "Start" to PadKey.Start)
}
