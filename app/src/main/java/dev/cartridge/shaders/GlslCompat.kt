package dev.cartridge.shaders

/**
 * A shader source as GLSL ES accepts it where desktop GLSL was more lenient (ADR 0027). Desktop GLSL lets a
 * global be initialised from anything and runs those initialisers when `main` starts; GLSL ES allows only
 * constant expressions there. crt-royale's port, for one, empties `const` with a macro, so every one of its
 * hundreds of settings is such a global. [hoistGlobalInitializers] does what desktop GL does: each global
 * keeps its declaration, and its initialisation moves to the start of every `main`, inside the same
 * preprocessor branches it was written in. The host compiles this form only when the source as written does
 * not compile.
 */
object GlslCompat {
    private val QUALIFIERS_KEPT_OUT = setOf("uniform", "attribute", "varying", "in", "out", "inout", "layout", "buffer", "shared", "precision", "struct")
    private val DROPPED = setOf("const", "static")
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val MAIN = Regex("""^\s*void\s+main\s*\(\s*(void)?\s*\)\s*$""")

    private class Hoisted(val at: Int, val conditions: List<List<String>>, val assignment: String)

    private val COUNTERS = Regex("""\buniform\s+(?:[A-Za-z_][A-Za-z0-9_]*\s+)?int\s+(FrameCount|FrameDirection)\s*;""")

    /**
     * The source as GLSL ES accepts it, or null when nothing in it needed to change: global initialisers moved
     * into main, and RetroArch's frame counters declared as highp floats. Desktop GL turns an int counter into
     * a float wherever a shader adds it to one (crt-royale does, for interlacing); GLSL ES converts nothing.
     * The host sets a counter by the type the compiled program reports.
     */
    fun forGlslEs(source: String): String? {
        val hoisted = hoistGlobalInitializers(source)
        val text = hoisted ?: source
        val counters = COUNTERS.replace(text) { "uniform highp float ${it.groupValues[1]};" }
        return if (hoisted == null && counters == text) null else counters
    }

    private val SIMPLE_TARGET = Regex("""^[A-Za-z_][A-Za-z0-9_.]*(\[[^\]]*\])*$""")
    private val CALL_START = Regex("""^[A-Za-z_][A-Za-z0-9_]*\s*\(""")

    /**
     * Every `x op= cond ? f(a) : g(b);` inside a function written as `if (cond) x op= f(a); else x op= g(b);`.
     * The two mean the same, but the Adreno 650's compiler evaluates the first form to 0 when the calls return
     * early (crt-guest-dr-venom's slot mask: ADR 0027), and gets the second right. A declaration of that form
     * becomes a declaration and the same branches. Line breaks are kept, so compiler messages keep their lines.
     */
    fun selectedCallsAsBranches(source: String): String {
        val text = withoutComments(source)
        val edits = mutableListOf<Triple<Int, Int, String>>()
        var depth = 0
        var parentheses = 0
        var start = 0
        var crossesDirective = false
        var lineStart = true
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (lineStart) {
                var first = index
                while (first < text.length && (text[first] == ' ' || text[first] == '\t')) first++
                if (first < text.length && text[first] == '#') {
                    if (text.substring(start, first).isNotBlank()) crossesDirective = true
                    index = text.indexOf('\n', first).let { if (it < 0) text.length else it }
                    lineStart = false
                    continue
                }
            }
            lineStart = char == '\n'
            when (char) {
                '(' -> parentheses++
                ')' -> parentheses = maxOf(0, parentheses - 1)
                '{', '}' -> if (parentheses == 0) {
                    depth = if (char == '{') depth + 1 else maxOf(0, depth - 1)
                    start = index + 1
                    crossesDirective = false
                }
                ';' -> if (parentheses == 0) {
                    if (depth > 0 && !crossesDirective) branches(text.substring(start, index))?.let { edits.add(Triple(start, index + 1, it)) }
                    start = index + 1
                    crossesDirective = false
                }
            }
            index++
        }
        if (edits.isEmpty()) return source
        val result = StringBuilder(source)
        for ((from, to, replacement) in edits.sortedByDescending { it.first }) result.replace(from, to, replacement)
        return result.toString()
    }

    private fun branches(statement: String): String? {
        if ('?' !in statement) return null
        var parentheses = 0
        var assign = -1
        for ((i, char) in statement.withIndex()) {
            when (char) {
                '(', '[' -> parentheses++
                ')', ']' -> parentheses--
                '=' -> if (parentheses == 0) {
                    val before = statement.getOrNull(i - 1)
                    if (statement.getOrNull(i + 1) != '=' && before !in setOf('=', '!', '<', '>')) { assign = i; break }
                }
            }
        }
        if (assign <= 0) return null
        val compound = statement[assign - 1] in setOf('*', '+', '-', '/')
        val operator = if (compound) statement.substring(assign - 1, assign + 1) else "="
        val target = statement.substring(0, if (compound) assign - 1 else assign).trim()
        val value = statement.substring(assign + 1).trim()
        var question = -1
        var colon = -1
        parentheses = 0
        for ((i, char) in value.withIndex()) {
            when (char) {
                '(', '[' -> parentheses++
                ')', ']' -> parentheses--
                '?' -> if (parentheses == 0) { if (question >= 0) return null; question = i }
                ':' -> if (parentheses == 0 && question >= 0 && colon < 0) colon = i
            }
        }
        if (question <= 0 || colon <= question) return null
        val condition = value.substring(0, question).trim()
        val first = value.substring(question + 1, colon).trim()
        val second = value.substring(colon + 1).trim()
        if (!isCall(first) || !isCall(second) || condition.isEmpty()) return null
        val breaks = "\n".repeat(statement.count { it == '\n' })
        if (SIMPLE_TARGET.matches(target)) return "if ($condition) $target $operator $first; else $target $operator $second;$breaks"
        // A declaration: its type, then the branches; a const one cannot wait for its value.
        val tokens = target.split(Regex("\\s+")).filter { it.isNotEmpty() && it != "const" }
        val name = tokens.lastOrNull() ?: return null
        if (compound || tokens.size < 2 || !Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name) || '(' in target) return null
        return "${tokens.joinToString(" ")}; if ($condition) $name = $first; else $name = $second;$breaks"
    }

    /** One function call and nothing else: a name, then parentheses that close at the end. */
    private fun isCall(text: String): Boolean {
        val open = CALL_START.find(text)?.range?.last ?: return false
        var parentheses = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> parentheses++
                ')' -> { parentheses--; if (parentheses == 0) return i == text.length - 1 }
            }
        }
        return false
    }

    /** The source with its global initialisers moved into `main`, or null when it has none to move. */
    fun hoistGlobalInitializers(source: String): String? {
        val text = withoutComments(source)
        val replacements = mutableListOf<Triple<Int, Int, String>>()
        val mains = mutableListOf<Int>()
        val hoisted = mutableListOf<Hoisted>()
        val conditions = mutableListOf<MutableList<String>>()

        var depth = 0
        var parentheses = 0
        var statementStart = -1
        var crossesDirective = false
        var index = 0
        var lineStart = true
        while (index < text.length) {
            val char = text[index]
            if (lineStart) {
                // A preprocessor line is passed over whole; it only changes which branch later text is in.
                var first = index
                while (first < text.length && (text[first] == ' ' || text[first] == '\t')) first++
                if (first < text.length && text[first] == '#') {
                    var end = text.indexOf('\n', first).let { if (it < 0) text.length else it }
                    // A directive continued with backslashes is one line.
                    while (end < text.length && end > 0 && text[end - 1] == '\\') end = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
                    val directive = text.substring(first, end).trim()
                    val word = directive.removePrefix("#").trim().substringBefore(' ').substringBefore('(').substringBefore('\t')
                    when (word) {
                        "if", "ifdef", "ifndef" -> conditions.add(mutableListOf(directive))
                        "elif", "else" -> conditions.lastOrNull()?.add(directive)
                        "endif" -> if (conditions.isNotEmpty()) conditions.removeAt(conditions.size - 1)
                    }
                    if (statementStart >= 0 && text.substring(statementStart, first).isNotBlank()) crossesDirective = true
                    index = end
                    lineStart = false
                    continue
                }
            }
            lineStart = char == '\n'
            if (depth == 0 && parentheses == 0 && statementStart < 0 && !char.isWhitespace()) {
                statementStart = index
                crossesDirective = false
            }
            when (char) {
                '(' -> parentheses++
                ')' -> parentheses = maxOf(0, parentheses - 1)
                '{' -> {
                    if (depth == 0 && statementStart >= 0 && MAIN.matches(text.substring(statementStart, index))) mains.add(index + 1)
                    depth++
                }
                '}' -> {
                    depth = maxOf(0, depth - 1)
                    // A function ends its statement here; a struct goes on to its semicolon.
                    if (depth == 0 && statementStart >= 0 && !text.substring(statementStart, index).trimStart().startsWith("struct")) statementStart = -1
                }
                ';' -> if (depth == 0 && parentheses == 0 && statementStart >= 0) {
                    if (!crossesDirective) declaration(text.substring(statementStart, index))?.let { (declaration, assignment) ->
                        replacements.add(Triple(statementStart, index + 1, declaration))
                        hoisted.add(Hoisted(statementStart, conditions.map { it.toList() }, assignment))
                    }
                    statementStart = -1
                }
            }
            index++
        }
        if (hoisted.isEmpty() || mains.isEmpty()) return null

        // A main is given the globals declared above it, in the order they were written.
        fun block(main: Int) = buildString {
            append('\n')
            for (item in hoisted) {
                if (item.at > main) continue
                for (level in item.conditions) for (line in level) append(line).append('\n')
                append(item.assignment).append('\n')
                repeat(item.conditions.size) { append("#endif\n") }
            }
        }
        val edits = replacements + mains.map { Triple(it, it, block(it)) }.filter { it.third.isNotBlank() }
        val result = StringBuilder(text)
        for ((start, end, replacement) in edits.sortedByDescending { it.first }) result.replace(start, end, replacement)
        return result.toString()
    }

    /** `[qualifiers] type name = initialiser` as a declaration without its initialiser, and the assignment. */
    private fun declaration(statement: String): Pair<String, String>? {
        val equals = topLevelEquals(statement) ?: return null
        val left = statement.substring(0, equals).trim()
        val right = statement.substring(equals + 1).trim()
        if (right.isEmpty() || '(' in left || hasTopLevelComma(right)) return null
        val tokens = IDENTIFIER.findAll(left).map { it.value }.toList()
        if (tokens.any { it in QUALIFIERS_KEPT_OUT }) return null
        val name = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*(\[[^\]]*\])?\s*$""").find(left)?.groupValues?.get(1) ?: return null
        val kept = tokens.filter { it !in DROPPED }
        if (kept.size < 2 || kept.last() != name) return null
        val declaration = left.split(Regex("\\s+")).filter { it !in DROPPED }.joinToString(" ")
        return "$declaration;" to "$name = $right;"
    }

    private fun topLevelEquals(statement: String): Int? {
        var parentheses = 0
        for ((i, char) in statement.withIndex()) {
            when (char) {
                '(', '[' -> parentheses++
                ')', ']' -> parentheses--
                '=' -> if (parentheses == 0) {
                    val before = statement.getOrNull(i - 1)
                    val after = statement.getOrNull(i + 1)
                    if (after != '=' && before !in setOf('=', '!', '<', '>', '+', '-', '*', '/', '%', '&', '|', '^')) return i
                }
            }
        }
        return null
    }

    private fun hasTopLevelComma(text: String): Boolean {
        var parentheses = 0
        for (char in text) when (char) {
            '(', '[' -> parentheses++
            ')', ']' -> parentheses--
            ',' -> if (parentheses == 0) return true
        }
        return false
    }

    /** Comments replaced by spaces, their line breaks kept, so line numbers in a compiler's log stay true. */
    internal fun withoutComments(source: String): String {
        val result = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            if (source.startsWith("//", i)) {
                while (i < source.length && source[i] != '\n') { result.append(' '); i++ }
            } else if (source.startsWith("/*", i)) {
                val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                for (j in i until end) result.append(if (source[j] == '\n') '\n' else ' ')
                i = end
            } else {
                result.append(source[i]); i++
            }
        }
        return result.toString()
    }
}
