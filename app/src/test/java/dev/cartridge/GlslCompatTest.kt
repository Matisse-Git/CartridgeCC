package dev.cartridge

import dev.cartridge.shaders.GlslCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Global initialisers moved into main, as desktop GLSL runs them; every source here is made up. */
class GlslCompatTest {
    private fun lines(text: String) = text.lines().map(String::trim).filter(String::isNotEmpty)

    @Test fun aGlobalKeepsItsDeclarationAndIsSetWhenMainStarts() {
        val source = """
            #define static
            #define const
            uniform float strength;
            static const float base = 53.0 / 255.0; // a comment
            static const float scaled = base * strength;
            float helper(float x) { float local = x * 2.0; return local; }
            void main()
            {
                gl_FragColor = vec4(scaled);
            }
        """.trimIndent()
        val result = lines(GlslCompat.hoistGlobalInitializers(source)!!)
        assertTrue("float base;" in result)
        assertTrue("float scaled;" in result)
        assertTrue("uniform float strength;" in result)
        // The function and its local are untouched; the assignments come first in main, in order.
        assertTrue("float helper(float x) { float local = x * 2.0; return local; }" in result)
        val main = result.indexOf("{") .let { result.indexOf("void main()") }
        assertEquals(listOf("base = 53.0 / 255.0;", "scaled = base * strength;"), result.drop(main + 2).take(2))
    }

    @Test fun branchesGoWithTheAssignmentAndEachStageGetsItsOwn() {
        val source = """
            #ifdef HIGH
            float level = 2.0;
            #else
            float level = 1.0;
            #endif
            #if defined(VERTEX)
            float onlyVertex = level;
            void main() { gl_Position = vec4(onlyVertex); }
            #elif defined(FRAGMENT)
            void main() { gl_FragColor = vec4(level); }
            #endif
        """.trimIndent()
        val result = GlslCompat.hoistGlobalInitializers(source)!!
        val fragment = result.substringAfter("#elif defined(FRAGMENT)")
        // Each branch is reproduced with only its own assignment in it.
        assertTrue(fragment.contains("#ifdef HIGH\nlevel = 2.0;\n#endif"))
        assertTrue(fragment.contains("#ifdef HIGH\n#else\nlevel = 1.0;\n#endif"))
        assertTrue(fragment.contains("#if defined(VERTEX)\nonlyVertex = level;\n#endif"))
        assertEquals(2, Regex("""void main\(\) \{""").findAll(result).count())
    }

    @Test fun whatIsNotAGlobalInitialiserIsLeftAlone() {
        val source = """
            uniform float u = 1.0;
            struct Light { vec3 colour; float power; };
            vec3 tone(vec3 c);
            float a = 1.0, b = 2.0;
            float later() { return 1.0; }
            void main() { float x = 1.0; }
            float afterMain = 3.0;
        """.trimIndent()
        val result = GlslCompat.hoistGlobalInitializers(source)!!
        assertTrue(result.contains("uniform float u = 1.0;"))
        assertTrue(result.contains("float a = 1.0, b = 2.0;"))
        assertTrue(result.contains("struct Light { vec3 colour; float power; };"))
        // Declared after main: main is not given it.
        assertTrue(result.contains("float afterMain;"))
        assertFalse(result.substringBefore("float afterMain;").contains("afterMain = 3.0;"))
        assertTrue(result.contains("void main() { float x = 1.0; }"))
    }

    @Test fun nothingToMoveIsNoNewSource() {
        assertNull(GlslCompat.hoistGlobalInitializers("uniform float u;\nvoid main() { gl_FragColor = vec4(u); }\n"))
        assertNull(GlslCompat.hoistGlobalInitializers("float a = 1.0;\n"))
    }

    @Test fun aSelectionBetweenCallsBecomesBranches() {
        val source = """
            float SlotMask(vec2 pos, vec3 c) { if (slotmask == 0.0) return 1.0; return 0.5; }
            void main()
            {
                vec3 cmask1 = vec3(1.0);
                cmask1 *= (TATE < 0.5) ? SlotMask(gl_FragCoord.xy * 1.000001,tmp) :
                    SlotMask(gl_FragCoord.yx * 1.000001,tmp);   // a comment
                float picked = TATE > 0.5 ? f(a, g(b)) : f(c, d);
                const float kept = TATE > 0.5 ? f(a) : 2.0;
                x = y > 0.0 ? a : b;
                for (int i = 0; i < 3; i++) total += i > 1 ? f(i) : f(0);
            }
        """.trimIndent()
        val result = GlslCompat.selectedCallsAsBranches(source)
        assertTrue(result, result.contains(
            "if ((TATE < 0.5)) cmask1 *= SlotMask(gl_FragCoord.xy * 1.000001,tmp); else cmask1 *= SlotMask(gl_FragCoord.yx * 1.000001,tmp);"))
        assertTrue(result.contains("float picked; if (TATE > 0.5) picked = f(a, g(b)); else picked = f(c, d);"))
        // Not two calls, not an assignment of its own, or inside a for header: left as written.
        assertTrue(result.contains("const float kept = TATE > 0.5 ? f(a) : 2.0;"))
        assertTrue(result.contains("x = y > 0.0 ? a : b;"))
        assertTrue(result.contains("total += i > 1 ? f(i) : f(0);"))
        // The function and the line count are untouched.
        assertTrue(result.startsWith("float SlotMask(vec2 pos, vec3 c) { if (slotmask == 0.0) return 1.0; return 0.5; }"))
        assertEquals(source.count { it == '\n' }, result.count { it == '\n' })
        // Nothing to rewrite: the very same text.
        val plain = "void main() { x = y > 0.0 ? a : b; }"
        assertEquals(plain, GlslCompat.selectedCallsAsBranches(plain))
    }

    @Test fun commentsGoButLineNumbersStay() {
        val text = GlslCompat.withoutComments("a // b\n/* c\nd */ e\n")
        assertEquals(3, text.count { it == '\n' })
        assertFalse(text.contains("b") || text.contains("c") || text.contains("d"))
        assertTrue(text.contains("a") && text.contains("e"))
    }
}
