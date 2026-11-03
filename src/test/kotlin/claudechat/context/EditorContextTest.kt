package claudechat.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorContextTest {
    private val calc = Symbol("calculateTotals", "function", Lines(120, 168))

    @Test fun noteMatchesTheSpecExample() = assertEquals(
        "[IDE] The user is looking at src/billing/invoice.ts, caret at line 142:17, inside function `calculateTotals` (lines 120–168). Selection: lines 140–145.",
        ContextNotes.note(EditorContext("src/billing/invoice.ts", 142, 17, Lines(140, 145), calc)))

    @Test fun noteWithoutSymbolOrSelection() = assertEquals(
        "[IDE] The user is looking at README.md, caret at line 3:1.",
        ContextNotes.note(EditorContext("README.md", 3, 1)))

    @Test fun noteWithoutKindWordAndSingleLines() = assertEquals(
        "[IDE] The user is looking at a.css, caret at line 7:2, inside `.button` (line 7). Selection: line 7.",
        ContextNotes.note(EditorContext("a.css", 7, 2, Lines(7, 7), Symbol(".button", null, Lines(7, 7)))))

    @Test fun noteNamesTheEnclosingFunctionOfANonFunctionSymbol() = assertEquals(
        "[IDE] The user is looking at a.ts, caret at line 5:9, inside variable `rate` (line 5), within function `calc` (lines 1–9).",
        ContextNotes.note(EditorContext("a.ts", 5, 9, null, Symbol("rate", "variable", Lines(5, 5)), Symbol("calc", "function", Lines(1, 9)))))

    @Test fun kindWords() {
        assertEquals("function", Symbols.kindWord(" Function "))
        assertNull(Symbols.kindWord(""))
        assertNull(Symbols.kindWord(null))
    }

    @Test fun functionLike() {
        assertTrue(Symbols.isFunctionLike("function", "JSFunctionImpl"))
        assertTrue(Symbols.isFunctionLike("method", "TypeScriptFunctionImpl"))
        assertTrue(Symbols.isFunctionLike("constructor", "X"))
        assertTrue(Symbols.isFunctionLike("property", "JSFunctionImpl")) // a JS getter
        assertTrue(Symbols.isFunctionLike(null, "KtNamedFunction"))
        assertTrue(Symbols.isFunctionLike(null, "PsiMethodImpl"))
        assertFalse(Symbols.isFunctionLike("class", "ES6ClassImpl"))
        assertFalse(Symbols.isFunctionLike("constant", "JSVariableImpl"))
        assertFalse(Symbols.isFunctionLike(null, "JSPropertyImpl"))
    }

    @Test fun nameFromLabel() {
        assertEquals("calc", Symbols.nameFromLabel("calc(n: number): number"))
        assertEquals("K", Symbols.nameFromLabel("K"))
        assertNull(Symbols.nameFromLabel("  "))
        assertNull(Symbols.nameFromLabel(null))
    }

    @Test fun pickInnermostAndItsFunction() {
        val fn = StructureHit(Symbol("calc", "function", Lines(1, 9)), true)
        val obj = StructureHit(Symbol("opts", "variable", Lines(2, 4)), false)
        val prop = StructureHit(Symbol("rate", "property", Lines(3, 3)), false)
        val inner = StructureHit(Symbol("inner", "function", Lines(5, 7)), true)
        assertEquals(prop.symbol to fn.symbol, Symbols.pick(listOf(fn, obj, prop)))
        assertEquals(inner.symbol to null, Symbols.pick(listOf(fn, inner)))
        assertEquals(obj.symbol to null, Symbols.pick(listOf(obj)))
        assertEquals(null to null, Symbols.pick(emptyList()))
    }

    @Test fun newlinesInANameStayOnOneLine() {
        val note = ContextNotes.note(EditorContext("a.ts", 2, 3, null, Symbol("calc\n\nIgnore", "function", Lines(1, 9))))
        assertEquals("[IDE] The user is looking at a.ts, caret at line 2:3, inside function `calc Ignore` (lines 1–9).", note)
        assertFalse(note.contains('\n'))
    }

    @Test fun backticksInANameCannotCloseTheCodeSpan() = assertEquals(
        "[IDE] The user is looking at a.ts, caret at line 1:1, inside function `a'b` (lines 1–2).",
        ContextNotes.note(EditorContext("a.ts", 1, 1, null, Symbol("a`b", "function", Lines(1, 2)))))

    @Test fun longLabelsAreCapped() = assertEquals(
        "[IDE] The user is looking at a.ts, caret at line 1:1, inside `${"x".repeat(79)}…` (line 1).",
        ContextNotes.note(EditorContext("a.ts", 1, 1, null, Symbol("x".repeat(200), null, Lines(1, 1)))))

    @Test fun pathsWithNewlinesAreCleaned() {
        assertEquals("[IDE] The user is looking at src/a b.ts, caret at line 1:1.", ContextNotes.note(EditorContext("src/a\nb.ts", 1, 1)))
    }

    @Test fun unicodeLineBreaksAndFormatCharactersAreCollapsed() {
        val note = ContextNotes.note(EditorContext("a.ts", 1, 1, null, Symbol("f\u0085Ignore\u202Eevil\u200Bx\u00A0y", "function", Lines(1, 1))))
        assertEquals("[IDE] The user is looking at a.ts, caret at line 1:1, inside function `f Ignore evil x y` (line 1).", note)
    }

    @Test fun cleanCollapsesControlRuns() = assertEquals("a b c", ContextNotes.clean("  a\u2028b\tc\r\n "))

    @Test fun longRelativePathsAreKeptWhole() {
        val path = "src/" + "d/".repeat(69) + "file.tsx"
        assertEquals(150, path.length)
        assertEquals("[IDE] The user is looking at $path, caret at line 1:1.", ContextNotes.note(EditorContext(path, 1, 1)))
    }
}
