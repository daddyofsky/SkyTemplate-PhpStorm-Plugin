package com.novaframework.templatelang.inspection

import com.novaframework.templatelang.inspection.SkyTemplateCallArguments.ArgKind
import com.novaframework.templatelang.inspection.SkyTemplateCallArguments.ArgRange
import com.novaframework.templatelang.inspection.SkyTemplateCallArguments.CallMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit coverage for the bits of [SkyTemplateCallArguments] that have no
 * PSI dependency:
 *
 *   - call-site collection (paren / static / pipe shape, comment skip).
 *   - argument splitting (top-level comma, quote-aware, paren-aware).
 *   - per-bucket classification (positional / named / `##` placeholder,
 *     including the `==` comparison guard for pipe mode).
 *
 * Signature extraction (`signatureOf`) and rule evaluation are exercised in
 * [SkyTemplateArgumentInspectionsIntegrationTest] where a real PHP index
 * exists. Without an index, fabricating PhpParameter mocks is brittle and
 * adds little value over the integration coverage.
 */
class SkyTemplateCallArgumentsTest {

    // ── collectCalls ───────────────────────────────────────────────────────

    @Test fun collectCalls_parenFunction() {
        val calls = SkyTemplateCallArguments.collectCalls("<p>{=foo(1, 2)}</p>")
        assertEquals(1, calls.size)
        val c = calls[0]
        assertEquals(CallMode.PAREN, c.mode)
        assertEquals("foo", c.calleeName)
        assertNull(c.calleeClass)
        // argListStart points one past `(`, argListEnd points to `)`.
        assertEquals('1', "<p>{=foo(1, 2)}</p>"[c.argListStart])
        assertEquals(')', "<p>{=foo(1, 2)}</p>"[c.argListEnd])
    }

    @Test fun collectCalls_staticMethod() {
        val text = "{=Util::format(value: 1)}"
        val calls = SkyTemplateCallArguments.collectCalls(text)
        assertEquals(1, calls.size)
        val c = calls[0]
        assertEquals(CallMode.PAREN_STATIC, c.mode)
        assertEquals("Util", c.calleeClass)
        assertEquals("format", c.calleeName)
    }

    @Test fun collectCalls_pipeFilter() {
        val text = "{x|fmt=a, b}"
        val calls = SkyTemplateCallArguments.collectCalls(text)
        assertEquals(1, calls.size)
        val c = calls[0]
        assertEquals(CallMode.PIPE, c.mode)
        assertEquals("fmt", c.calleeName)
        assertEquals('a', text[c.argListStart])
        assertEquals('}', text[c.argListEnd])
    }

    @Test fun collectCalls_skipsComment() {
        // `{*…*}` ranges must not produce call sites.
        val calls = SkyTemplateCallArguments.collectCalls("{* foo(1) *}")
        assertTrue(calls.isEmpty())
    }

    /**
     * Regression (P2-2, v1.2.4): the comment guard checked `text[open + 1]`
     * directly, which only works for the plain `{*…*}` form. An HTML-wrapped
     * comment (`<!--{* … *}-->`) starts with `<!--`, so the old check missed
     * it entirely and scanned the comment body for call sites, producing
     * bogus argument warnings on example code written inside the comment.
     */
    @Test fun collectCalls_skipsWrappedComment() {
        val calls = SkyTemplateCallArguments.collectCalls("<!--{* 예: {=substr(\"x\")} *}-->")
        assertTrue(calls.isEmpty())
    }

    @Test fun collectCalls_wrappedNonCommentDirectiveStillCollected() {
        // A wrapped directive that is NOT a comment must still be scanned —
        // only the `<!--{*` shape is a comment.
        val calls = SkyTemplateCallArguments.collectCalls("<!--{=substr(\"x\")}-->")
        assertEquals(1, calls.size)
        assertEquals("substr", calls[0].calleeName)
    }

    @Test fun collectCalls_pipeStaticMethod() {
        // P3-4: `{var|Cls::m=…}` is compiler-supported (filter-name char
        // class allows `::`) and is never routed through the formatter —
        // must be collected as a static-method call, not skipped.
        val text = "{x|Cls::m=1, 2}"
        val calls = SkyTemplateCallArguments.collectCalls(text)
        assertEquals(1, calls.size)
        val c = calls[0]
        assertEquals(CallMode.PIPE, c.mode)
        assertEquals("Cls", c.calleeClass)
        assertEquals("m", c.calleeName)
        assertEquals('1', text[c.argListStart])
        assertEquals('}', text[c.argListEnd])
    }

    @Test fun collectCalls_pipeStaticMethod_namespaced() {
        val text = "{x|Ns\\Cls::m=1}"
        val calls = SkyTemplateCallArguments.collectCalls(text)
        assertEquals(1, calls.size)
        assertEquals("Ns\\Cls", calls[0].calleeClass)
        assertEquals("m", calls[0].calleeName)
    }

    @Test fun collectCalls_pipeAfterIdentifier() {
        // L-002 regression: identifier preceding `|` must not eat past the pipe.
        val text = "{name|fmt=1}"
        val calls = SkyTemplateCallArguments.collectCalls(text)
        assertEquals(1, calls.size)
        assertEquals(CallMode.PIPE, calls[0].mode)
        assertEquals("fmt", calls[0].calleeName)
    }

    @Test fun collectCalls_nestedCall() {
        // `{=foo(bar(1))}` — P3-3: the scanner now recurses into argument
        // ranges, so the inner `bar(1)` call is also collected (and
        // therefore validated by the argument-count / named-arg rules).
        val calls = SkyTemplateCallArguments.collectCalls("{=foo(bar(1))}")
        assertEquals(2, calls.size)
        assertEquals(setOf("foo", "bar"), calls.map { it.calleeName }.toSet())
    }

    @Test fun collectCalls_nestedCall_innerArgRangeIsCorrect() {
        val text = "{=trim(substr(\"x\", 1))}"
        val calls = SkyTemplateCallArguments.collectCalls(text)
        val inner = calls.first { it.calleeName == "substr" }
        assertEquals(CallMode.PAREN, inner.mode)
        assertEquals('"', text[inner.argListStart])
        assertEquals(')', text[inner.argListEnd])
    }

    @Test fun collectCalls_nestedCall_doesNotMisreadParenInsideStringLiteral() {
        // `foo("bar(", 1)` — the `(` inside the quoted string must not be
        // mistaken for a nested call opener by the recursive scan.
        val calls = SkyTemplateCallArguments.collectCalls("{=foo(\"bar(\", 1)}")
        assertEquals(1, calls.size)
        assertEquals("foo", calls[0].calleeName)
    }

    @Test fun collectCalls_doublyNestedCall_allThreeCollected() {
        val calls = SkyTemplateCallArguments.collectCalls("{=a(b(c(1)))}")
        assertEquals(setOf("a", "b", "c"), calls.map { it.calleeName }.toSet())
    }

    // ── splitArguments ─────────────────────────────────────────────────────

    @Test fun splitArguments_simple() {
        val text = "a, b, c"
        val parts = SkyTemplateCallArguments.splitArguments(text, 0, text.length)
        assertEquals(3, parts.size)
        assertEquals("a", text.substring(parts[0].startInclusive, parts[0].endExclusive).trim())
        assertEquals("b", text.substring(parts[1].startInclusive, parts[1].endExclusive).trim())
        assertEquals("c", text.substring(parts[2].startInclusive, parts[2].endExclusive).trim())
    }

    @Test fun splitArguments_quotedComma() {
        val text = "'a, b', c"
        val parts = SkyTemplateCallArguments.splitArguments(text, 0, text.length)
        assertEquals(2, parts.size)
        assertEquals("'a, b'", text.substring(parts[0].startInclusive, parts[0].endExclusive).trim())
    }

    @Test fun splitArguments_parenInArg() {
        val text = "f(a, b), c"
        val parts = SkyTemplateCallArguments.splitArguments(text, 0, text.length)
        assertEquals(2, parts.size)
        assertEquals("f(a, b)", text.substring(parts[0].startInclusive, parts[0].endExclusive).trim())
    }

    @Test fun splitArguments_bracketInArg() {
        val text = "[1, 2], 3"
        val parts = SkyTemplateCallArguments.splitArguments(text, 0, text.length)
        assertEquals(2, parts.size)
    }

    @Test fun splitArguments_emptyAndTrailingComma() {
        // Default (paren mode): trailing commas/blanks are dropped.
        val text = "a, , b,"
        val parts = SkyTemplateCallArguments.splitArguments(text, 0, text.length)
        assertEquals(2, parts.size)
    }

    // ── P3-5: pipe-mode keepBlanks ───────────────────────────────────────────

    @Test fun splitArguments_keepBlanks_middleBlankIsPreserved() {
        // The compiler's `str_getcsv` keeps a blank CSV field as a
        // positional `''` argument — `{v|fn=a,,b}` compiles to 4 PHP args
        // (##, a, '', b), not 2. Blank buckets must survive when keepBlanks.
        val text = "a,,b"
        val parts = SkyTemplateCallArguments.splitArguments(text, 0, text.length, keepBlanks = true)
        assertEquals(3, parts.size)
        assertEquals("a", text.substring(parts[0].startInclusive, parts[0].endExclusive).trim())
        assertTrue(text.substring(parts[1].startInclusive, parts[1].endExclusive).isBlank())
        assertEquals("b", text.substring(parts[2].startInclusive, parts[2].endExclusive).trim())
    }

    @Test fun splitArguments_keepBlanks_whollyBlankNoCommaCollapsesToZero() {
        // `{v|fn=}` (no comma at all) is the compiler's separate `$args === ''`
        // bypass (direct `fn($code)`, no tokenising) — must still collapse to
        // zero buckets even with keepBlanks.
        val text = "   "
        val parts = SkyTemplateCallArguments.splitArguments(text, 0, text.length, keepBlanks = true)
        assertTrue(parts.isEmpty())
    }

    @Test fun splitArguments_keepBlanks_defaultStillDropsBlanks() {
        // keepBlanks defaults to false — existing paren-mode callers unaffected.
        val text = "a,,b"
        val parts = SkyTemplateCallArguments.splitArguments(text, 0, text.length)
        assertEquals(2, parts.size)
    }

    @Test fun splitArguments_empty() {
        val parts = SkyTemplateCallArguments.splitArguments("", 0, 0)
        assertTrue(parts.isEmpty())
    }

    // ── classify ───────────────────────────────────────────────────────────

    private fun cls(text: String, pipe: Boolean) =
        SkyTemplateCallArguments.classify(text, ArgRange(0, text.length), pipeMode = pipe)

    @Test fun classify_paren_named() {
        val a = cls("name: 1", pipe = false)
        assertEquals(ArgKind.NAMED, a.kind)
        assertEquals("name", a.name)
        assertEquals(0, a.nameRange!!.startOffset)
        assertEquals(4, a.nameRange!!.endOffset)
    }

    @Test fun classify_paren_doubleColonIsPositional() {
        // `Cls::CONST` — `Cls` followed by `::` is not a named arg.
        val a = cls("Cls::CONST", pipe = false)
        assertEquals(ArgKind.POSITIONAL, a.kind)
    }

    @Test fun classify_paren_positionalNumber() {
        val a = cls("42", pipe = false)
        assertEquals(ArgKind.POSITIONAL, a.kind)
    }

    @Test fun classify_pipe_named() {
        val a = cls("mode=daily", pipe = true)
        assertEquals(ArgKind.NAMED, a.kind)
        assertEquals("mode", a.name)
    }

    @Test fun classify_pipe_comparisonIsPositional() {
        // L-003 guard: `count==2` is comparison, not a named arg.
        val a = cls("count==2", pipe = true)
        assertEquals(ArgKind.POSITIONAL, a.kind)
    }

    @Test fun classify_pipe_hashPlaceholder() {
        val a = cls("##", pipe = true)
        assertEquals(ArgKind.HASH_PLACEHOLDER, a.kind)
    }

    @Test fun classify_pipe_hashWithLeadingSpace() {
        val a = cls("  ##  ", pipe = true)
        assertEquals(ArgKind.HASH_PLACEHOLDER, a.kind)
    }

    @Test fun classify_pipe_singleHashIsNotPlaceholder() {
        // A bare `#` is not the placeholder — only `##` is.
        val a = cls("#x", pipe = true)
        assertEquals(ArgKind.POSITIONAL, a.kind)
    }

    @Test fun classify_paren_namedWithSpacesAroundColon() {
        val a = cls("name : 1", pipe = false)
        assertEquals(ArgKind.NAMED, a.kind)
        assertEquals("name", a.name)
    }

    @Test fun classify_pipe_parenModeRejectsNameEqual() {
        // `name=value` in paren mode is NOT a named arg (paren uses `:`).
        // Documents that classify is mode-aware, preventing accidental
        // cross-mode shape acceptance.
        val a = cls("name=1", pipe = false)
        assertFalse(a.kind == ArgKind.NAMED)
    }
}
