package dev.cartridge.shaders

import java.io.File

/**
 * Reads a RetroArch GLSL preset (.glslp) and everything it names, following RetroArch 1.22.2
 * (gfx/video_shader_parse.c, libretro-common's config_file). Nothing outside [root] is read: a path that
 * leaves it, a wildcard path, a missing file or a pass that is not GLSL makes the preset unusable, and the
 * exception says which. Images are left as files; [ShaderLibrary] decodes them.
 */
class PresetReader(private val root: File) {
    class Unusable(message: String) : Exception(message)

    enum class Scale { NONE, SOURCE, VIEWPORT, ABSOLUTE }
    enum class Wrap { BORDER, EDGE, REPEAT, MIRRORED_REPEAT }

    data class Pass(
        val file: File,
        val source: String,
        /** -1 when the preset leaves it open, else 0 nearest or 1 linear. */
        val filter: Int,
        val wrap: Wrap,
        val mipmapInput: Boolean,
        val alias: String,
        val floatFramebuffer: Boolean,
        val srgbFramebuffer: Boolean,
        val frameCountMod: Int,
        val scaleTypeX: Scale,
        val scaleTypeY: Scale,
        val scaleX: Float,
        val scaleY: Float,
    )

    data class Texture(val name: String, val file: File, val linear: Boolean, val mipmap: Boolean, val wrap: Wrap)

    data class Preset(val passes: List<Pass>, val textures: List<Texture>, val parameters: Map<String, Float>, val feedbackPass: Int)

    /** A value as written, and the folder of the file that wrote it: paths are relative to that file. */
    private data class Entry(val value: String, val folder: File)

    private val canonicalRoot = root.canonicalFile

    fun read(preset: File): Preset {
        val entries = entries(inside(preset), 0, mutableSetOf())
        val count = entries["shaders"]?.value?.toIntOrNull()
            ?: throw Unusable("${preset.name} names no shader passes")
        if (count <= 0) throw Unusable("${preset.name} has no shader passes")
        val passes = (0 until minOf(count, MAX_PASSES)).map { pass(entries, it) }

        // #pragma parameter lines in every pass, then the preset's own values for them.
        val parameters = LinkedHashMap<String, Float>()
        for (pass in passes) for (line in pass.source.lineSequence()) parameter(line)?.let { (name, initial) -> parameters[name] = initial }
        for (name in parameters.keys.toList()) entries[name]?.value?.toFloatOrNull()?.let { parameters[name] = it }

        val textures = entries["textures"]?.value.orEmpty().split(';').map(String::trim).filter(String::isNotEmpty)
            .take(MAX_TEXTURES).map { name ->
                val entry = entries[name]?.takeIf { it.value.isNotEmpty() } ?: throw Unusable("${preset.name} names texture $name but not its file")
                Texture(name, file(entry, "texture $name"), bool(entries["${name}_linear"]) ?: true, bool(entries["${name}_mipmap"]) ?: false,
                    wrap(entries["${name}_wrap_mode"]))
            }
        val feedback = entries["feedback_pass"]?.value?.toIntOrNull() ?: -1
        return Preset(passes, textures, parameters, feedback)
    }

    private fun pass(entries: Map<String, Entry>, index: Int): Pass {
        val path = entries["shader$index"]?.takeIf { it.value.isNotEmpty() } ?: throw Unusable("Pass $index names no shader")
        val file = file(path, "pass $index")
        if (!file.name.endsWith(".glsl", ignoreCase = true)) throw Unusable("Pass $index is ${file.name}, not a GLSL shader")
        if (file.length() > MAX_SOURCE_BYTES) throw Unusable("Pass $index (${file.name}) is too large")
        val source = file.readText()

        // video_shader_parse_pass: no scale type at all leaves the pass to the defaults; any one makes both axes
        // source × 1 unless said otherwise.
        val both = entries["scale_type$index"]?.value.orEmpty()
        var typeX = if (both.isNotEmpty()) both else entries["scale_type_x$index"]?.value.orEmpty()
        var typeY = if (both.isNotEmpty()) both else entries["scale_type_y$index"]?.value.orEmpty()
        val scaled = typeX.isNotEmpty() || typeY.isNotEmpty()
        if (scaled) {
            if (typeX.isEmpty()) typeX = "source"
            if (typeY.isEmpty()) typeY = "source"
        }
        val scaleX = if (scaled) scaleType(typeX, index) else Scale.NONE
        val scaleY = if (scaled) scaleType(typeY, index) else Scale.NONE
        fun factor(axis: String): Float {
            val value = entries["scale$index"] ?: entries["scale_$axis$index"]
            return value?.value?.toFloatOrNull() ?: 1f
        }
        return Pass(
            file = file,
            source = source,
            filter = when (bool(entries["filter_linear$index"])) { true -> 1; false -> 0; null -> -1 },
            wrap = wrap(entries["wrap_mode$index"]),
            mipmapInput = bool(entries["mipmap_input$index"]) ?: false,
            alias = entries["alias$index"]?.value.orEmpty(),
            floatFramebuffer = bool(entries["float_framebuffer$index"]) ?: false,
            srgbFramebuffer = bool(entries["srgb_framebuffer$index"]) ?: false,
            frameCountMod = entries["frame_count_mod$index"]?.value?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            scaleTypeX = scaleX,
            scaleTypeY = scaleY,
            // An absolute size is read as a whole number of pixels, as RetroArch reads it.
            scaleX = if (scaleX == Scale.ABSOLUTE) factor("x").toInt().toFloat() else factor("x"),
            scaleY = if (scaleY == Scale.ABSOLUTE) factor("y").toInt().toFloat() else factor("y"),
        )
    }

    private fun scaleType(value: String, index: Int) = when (value) {
        "source" -> Scale.SOURCE
        "viewport" -> Scale.VIEWPORT
        "absolute" -> Scale.ABSOLUTE
        else -> throw Unusable("Pass $index has an unknown scale type \"$value\"")
    }

    /**
     * The preset's keys with every #reference resolved: the first reference is the preset this one builds on,
     * later ones and the file's own keys override it, as video_shader_load_preset_into_shader applies them.
     */
    private fun entries(file: File, depth: Int, seen: MutableSet<File>): Map<String, Entry> {
        if (depth > MAX_REFERENCE_DEPTH) throw Unusable("${file.name} has references nested too deeply")
        if (!seen.add(file)) throw Unusable("${file.name} refers to itself")
        if (!file.isFile) throw Unusable("${file.relativeTo(canonicalRoot).path} is missing")
        if (file.length() > MAX_PRESET_BYTES) throw Unusable("${file.name} is too large")
        val parsed = ConfigFile.parse(file.readText())
        val result = LinkedHashMap<String, Entry>()
        for (reference in parsed.references) {
            if ('$' in reference) throw Unusable("${file.name} uses a wildcard path, which only RetroArch can fill in")
            result.putAll(entries(inside(File(file.parentFile, reference)), depth + 1, seen))
        }
        for ((key, value) in parsed.values) result[key] = Entry(value, file.parentFile!!)
        seen.remove(file)
        return result
    }

    private fun file(entry: Entry, what: String): File {
        if ('$' in entry.value) throw Unusable("The $what uses a wildcard path, which only RetroArch can fill in")
        val file = inside(File(entry.folder, entry.value))
        if (!file.isFile) throw Unusable("The $what, ${file.relativeTo(canonicalRoot).path}, is missing")
        return file
    }

    private fun inside(file: File): File {
        val canonical = file.canonicalFile
        if (canonical != canonicalRoot && !canonical.path.startsWith(canonicalRoot.path + File.separator))
            throw Unusable("${file.name} is outside the shader library")
        return canonical
    }

    private fun bool(entry: Entry?): Boolean? = when (entry?.value?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    private fun wrap(entry: Entry?): Wrap = when (entry?.value) {
        "clamp_to_edge" -> Wrap.EDGE
        "repeat" -> Wrap.REPEAT
        "mirrored_repeat" -> Wrap.MIRRORED_REPEAT
        else -> Wrap.BORDER
    }

    companion object {
        const val MAX_PASSES = 64
        const val MAX_TEXTURES = 64
        const val MAX_REFERENCE_DEPTH = 16
        const val MAX_PRESET_BYTES = 256L * 1024
        const val MAX_SOURCE_BYTES = 2L * 1024 * 1024

        private val PARAMETER = Regex("""^#pragma parameter\s+(\S{1,63})\s+"[^"]{0,63}"\s+(\S+)\s+(\S+)\s+(\S+)""")

        /** One `#pragma parameter name "description" initial minimum maximum [step]` line, as RetroArch's sscanf takes it. */
        fun parameter(line: String): Pair<String, Float>? {
            if (!line.startsWith("#pragma parameter")) return null
            val match = PARAMETER.find(line) ?: return null
            val initial = match.groupValues[2].toFloatOrNull() ?: return null
            if (match.groupValues[3].toFloatOrNull() == null || match.groupValues[4].toFloatOrNull() == null) return null
            return match.groupValues[1] to initial
        }
    }
}

/** libretro-common's config_file as presets use it: `key = value` lines, quoted or not, `#` comments, `#reference`. */
object ConfigFile {
    data class Parsed(val values: Map<String, String>, val references: List<String>)

    fun parse(text: String): Parsed {
        val values = LinkedHashMap<String, String>()
        val references = mutableListOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith("#reference")) value(line.removePrefix("#reference"))?.takeIf { it.isNotEmpty() }?.let(references::add)
                continue
            }
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val key = line.substring(0, equals).trim()
            if (key.isEmpty() || key.any(Char::isWhitespace)) continue
            values[key] = value(line.substring(equals + 1)) ?: continue
        }
        return Parsed(values, references)
    }

    /** A quoted value runs to its closing quote; an unquoted one to the first space or comment. */
    private fun value(rest: String): String? {
        val text = rest.trim()
        if (text.startsWith("\"")) {
            val end = text.indexOf('"', 1)
            return if (end < 0) null else text.substring(1, end)
        }
        return text.substringBefore('#').trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    }
}
