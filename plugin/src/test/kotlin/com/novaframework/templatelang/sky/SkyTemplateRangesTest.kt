package com.novaframework.templatelang.sky

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyTemplateRangesTest {

    private fun ranges(text: String) = SkyTemplateRanges.computeTemplateRanges(text)

    @Test fun emptyText() {
        assertTrue(ranges("").isEmpty())
    }

    @Test fun plainHtml() {
        assertTrue(ranges("<p>hello</p>").isEmpty())
    }

    @Test fun simpleVariable() {
        // <p>{name}</p>
        //    ^^^^^^
        val r = ranges("<p>{name}</p>")
        assertEquals(1, r.size)
        assertEquals(TextRange(3, 9), r[0])
    }

    @Test fun multipleTagsAndComment() {
        // {if a}{name}{/} {* note *}
        val r = ranges("{if a}{name}{/} {* note *}")
        assertEquals(4, r.size)
        // {if a}, {name}, {/}, {* note *}
        assertEquals(TextRange(0, 6),  r[0])
        assertEquals(TextRange(6, 12), r[1])
        assertEquals(TextRange(12, 15), r[2])
        assertEquals(TextRange(16, 26), r[3])
    }

    @Test fun dollarBraceIgnored() {
        // ${name} is JS template literal — must not register a range.
        val r = ranges("var x = `\${name}`;")
        assertTrue(r.isEmpty())
    }

    @Test fun unterminatedTagBecomesBestEffortRange() {
        val r = ranges("hello {oops")
        assertEquals(1, r.size)
        assertEquals(6, r[0].startOffset)
        assertEquals("hello {oops".length, r[0].endOffset)
    }

    @Test fun anyOverlap_basics() {
        val rs = listOf(TextRange(10, 20), TextRange(30, 40))
        assertFalse(SkyTemplateRanges.anyOverlap(rs, 0, 5))
        assertFalse(SkyTemplateRanges.anyOverlap(rs, 5, 10))   // adjacent — no overlap
        assertTrue(SkyTemplateRanges.anyOverlap(rs, 5, 11))    // crosses into 10-20
        assertTrue(SkyTemplateRanges.anyOverlap(rs, 12, 14))   // inside 10-20
        assertFalse(SkyTemplateRanges.anyOverlap(rs, 20, 30))  // strictly between
        assertTrue(SkyTemplateRanges.anyOverlap(rs, 35, 50))   // crosses 30-40
        assertFalse(SkyTemplateRanges.anyOverlap(rs, 40, 100)) // adjacent on right
    }

    @Test fun anyOverlap_emptyAndDegenerate() {
        assertFalse(SkyTemplateRanges.anyOverlap(emptyList(), 0, 100))
        assertFalse(SkyTemplateRanges.anyOverlap(listOf(TextRange(5, 10)), 5, 5))  // empty highlight
    }

    @Test fun attributeAreaSkyTemplate_realisticInput() {
        // <input {?disabled}disabled{/}>
        val r = ranges("<input {?disabled}disabled{/}>")
        assertEquals(2, r.size)
        // {?disabled} and {/}
        assertTrue(r[0].substring("<input {?disabled}disabled{/}>") == "{?disabled}")
        assertTrue(r[1].substring("<input {?disabled}disabled{/}>") == "{/}")
    }

    @Test fun overlappingHtmlError_dropped_byAnyOverlap() {
        val text = "<input {?disabled}disabled{/}>"
        val rs = ranges(text)
        // Pretend HTML inspector reports an error over `{?disabled}`
        val errStart = text.indexOf("{?disabled}")
        val errEnd = errStart + "{?disabled}".length
        assertTrue(SkyTemplateRanges.anyOverlap(rs, errStart, errEnd))
    }

    // ── CSS / JS injection guard ──────────────────────────────────────────────

    @Test fun cssRule_singleProperty_isNotTemplate() {
        // `<style>.foo { color: red; }</style>` — the `{ color: red; }` is a CSS
        // rule, NOT a template tag. Must not be reported as a template range.
        val text = "<style>.foo { color: red; }</style>"
        val r = ranges(text)
        assertTrue("CSS rule must not register as template tag, got $r", r.isEmpty())
    }

    @Test fun cssRule_multiProperty_isNotTemplate() {
        val text = "<style>.foo { color: red; padding: 4px; }</style>"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun jsObjectLiteral_isNotTemplate() {
        val text = "<script>let x = {a: 1, b: 2};</script>"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun jsBlockStatement_isNotTemplate() {
        val text = "<script>if (x) { y(); z(); }</script>"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun jsArrowBlock_isNotTemplate() {
        val text = "<script>const f = () => { return 1; };</script>"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun templateTagInsideStyle_stillRegistered() {
        // Genuine template variable inside <style> — must still register.
        val text = "<style>.foo { color: {color}; }</style>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{color}", r[0].substring(text))
    }

    @Test fun templateDirectiveInsideScript_stillRegistered() {
        val text = "<script>let x = {?cond}1{/}{:}2{/};</script>"
        val r = ranges(text)
        // `{?cond}`, `{/}`, `{:}`, `{/}` — four template tags
        assertEquals(4, r.size)
    }

    @Test fun templatePipeInsideAnything_stillRegistered() {
        val text = "{name|upper}"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals(TextRange(0, 12), r[0])
    }

    @Test fun templateMethodCallInsideAnything_stillRegistered() {
        val text = "{user->getName()}"
        val r = ranges(text)
        assertEquals(1, r.size)
    }

    @Test fun cTagConstantStillRegistered() {
        val text = "<style>p { content: '{c.LABEL}'; }</style>"
        val r = ranges(text)
        // `{c.LABEL}` — template; outer `{ content: '...'; }` — CSS
        assertEquals(1, r.size)
        assertEquals("{c.LABEL}", r[0].substring(text))
    }

    @Test fun looksLikeTemplateBody_directPredicate() {
        fun check(s: String) = SkyTemplateRanges.looksLikeTemplateBody(s, 0, s.length)
        // Positives — `{` immediately followed by tag content.
        assertTrue(check("{name}"))
        assertTrue(check("{?cond}"))
        assertTrue(check("{=foo()}"))
        assertTrue(check("{if x}"))
        assertTrue(check("{c.NAME}"))
        assertTrue(check("{var|trim}"))
        assertTrue(check("{user->name}"))
        assertTrue(check("{Cls::method()}"))
        assertTrue(check("{num#5}"))
        assertTrue(check("{var.key.sub}"))
        // Template_ permissive — horizontal whitespace before prefix / variable.
        assertTrue(check("{ ?cond}"))
        assertTrue(check("{ =expr}"))
        assertTrue(check("{ name }"))
        assertTrue(check("{ var.key.sub }"))
        assertTrue(check("{\t?cond}"))   // tab is `\h` too
        // Case-insensitive keyword match (compiler `/i` flag + strtolower).
        assertTrue(check("{IF x}"))
        assertTrue(check("{Loop products}"))
        assertTrue(check("{FOREACH a as b}"))
        // Negatives — keyword form rejects ANY leading whitespace
        // (SkyTemplate strictness; Template_ has no keyword form).
        assertFalse(check("{ if x}"))
        assertFalse(check("{ loop products}"))
        // Negatives — newlines / vertical whitespace are NOT `\h`, so they
        // disqualify even prefix / variable forms.
        assertFalse(check("{\n?cond}"))
        assertFalse(check("{\n name }"))
        // Negatives — CSS / JS bodies.
        assertFalse(check("{ color: red; }"))
        assertFalse(check("{a: 1, b: 2}"))
        assertFalse(check("{ stmt(); }"))
        assertFalse(check("{}"))
        assertFalse(check("{ }"))
        // Negative — CSS hex color shouldn't be mistaken for zerofill `#NN`.
        assertFalse(check("{ color: #fff; }"))
        assertFalse(check("{ background: #123abc; }"))
    }

    // ── JS keyword false-positive guard (function bodies) ────────────────────

    @Test fun jsFunctionBody_startingWithIf_isNotTemplate() {
        // Outer pair body: `\n  if (cond) {\n    x = 1;\n  }\n`
        // Without the nested-brace guard, the leading `if` keyword would falsely
        // qualify the entire function body as a SkyTemplate `{if …}` tag.
        val text = "<script>function bar() {\n  if (cond) {\n    x = 1;\n  }\n}</script>"
        val r = ranges(text)
        // Only the inner `{ x = 1; }` block remains (also rejected).
        assertTrue("function body must not register as template, got $r", r.isEmpty())
    }

    @Test fun jsForLoopBody_isNotTemplate() {
        val text = "<script>for (let i = 0; i < 10; i++) { console.log(i); }</script>"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun jsWhileBody_isNotTemplate() {
        val text = "<script>while (true) { break; }</script>"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun jsElseClauseBody_isNotTemplate() {
        val text = "<script>if (a) { x; } else { y; }</script>"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun nestedJsBlocksWithKeywordsRemainNonTemplate() {
        val text = """
            <script>
            function f() {
              if (a) {
                while (b) {
                  doSomething();
                }
              } else {
                cleanup();
              }
            }
            </script>
        """.trimIndent()
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun genuineSkyTemplateIfStillRegistered_evenAlongsideJs() {
        // Real SkyTemplate `{if user}…{/}` next to JS that should NOT register.
        val text = "<p>{if user}hi{/}</p><script>function f() { if (a) { b; } }</script>"
        val r = ranges(text)
        // Just `{if user}` and `{/}` — the JS block stays clean.
        assertEquals(2, r.size)
        assertEquals("{if user}", r[0].substring(text))
        assertEquals("{/}", r[1].substring(text))
    }

    // ── strictness rules per engine ─────────────────────────────────────────

    @Test fun leadingSpaceBeforeKeyword_isNotTemplate() {
        // SkyTemplate compiler regex `{(?:if|...)(?=\W)\h*` requires the
        // keyword IMMEDIATELY after `{`. Template_ has no keyword form, so
        // this is the strict rule for both engines combined. This is the
        // signal that distinguishes JS block-scope `{ if (cond) ... }` from
        // a real template tag.
        val text = "<p>{ if x }content</p>"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun leadingSpaceBeforeVariable_isAllowed_TemplateUnderscore() {
        // Template_ allows `{ var }` (whitespace via `\s*`). We accept this.
        val text = "<p>{ name }</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{ name }", r[0].substring(text))
    }

    @Test fun leadingSpaceBeforePrefix_isAllowed_TemplateUnderscore() {
        // Template_ regex `\s*(:\?|[=#@?:\/+])?(.*)` allows whitespace before
        // the prefix. We accept `{ ?cond}` for Template_ compatibility.
        val text = "<p>{ ?cond}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{ ?cond}", r[0].substring(text))
    }

    @Test fun leadingNewlineBeforePrefix_isNotTemplate() {
        // Newlines are NOT horizontal whitespace (`\h`). A newline before the
        // first content char disqualifies — defends against multi-line JS / CSS
        // bodies that happen to start on the next line with a template-ish char.
        val text = "<p>{\n?cond}</p>"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun leadingNewlineBeforeVariable_isNotTemplate() {
        val text = "<p>{\n name\n}</p>"
        assertTrue(ranges(text).isEmpty())
    }

    // ── case-insensitive keyword match ─────────────────────────────────────

    @Test fun upperCaseKeywordIsAccepted() {
        // The compiler regex has the `/i` flag and lowercases the captured tag.
        val text = "<p>{IF user}hi{END}</p>"
        val r = ranges(text)
        assertEquals(2, r.size)
        assertEquals("{IF user}", r[0].substring(text))
        assertEquals("{END}", r[1].substring(text))
    }

    @Test fun mixedCaseKeywordIsAccepted() {
        val text = "<p>{Foreach items as i}{/}</p>"
        val r = ranges(text)
        assertEquals(2, r.size)
    }

    // ── loop-scope dot prefix + var_up `@` (0.5.21) ────────────────────────

    @Test fun loopScopeSingleDot_isTemplate() {
        // `{.name}` — single leading dot is the loop-scope marker (current
        // loop). The dotted-chain check must accept it.
        val text = "<p>{.name}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{.name}", r[0].substring(text))
    }

    @Test fun loopScopeDoubleDot_isTemplate() {
        // `{..parent}` — parent loop. Already accepted by `..`-strong
        // signal earlier; now also via dotted-chain.
        val text = "<p>{..parent}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
    }

    @Test fun loopScopeDotPropertyChain_isTemplate() {
        // `{.category.name}` — leading scope dot followed by property
        // access chain.
        val text = "<p>{.category.name}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{.category.name}", r[0].substring(text))
    }

    @Test fun varUpBareAt_isTemplate() {
        // `{name@}` — bare `@` (current-loop var_up).
        val text = "<p>{name@}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
    }

    @Test fun varUpWithDigits_isTemplate() {
        // `{.name@2}` — combined leading scope + var_up depth.
        val text = "<p>{.name@2}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{.name@2}", r[0].substring(text))
    }

    @Test fun nonKeywordIdentBoundary_treatedAsVariable() {
        // `{ifx}` is NOT a keyword tag (no word boundary after `if`); it's a
        // single-identifier variable form. Should still register as template.
        val text = "<p>{ifx}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{ifx}", r[0].substring(text))
    }

    // ── // line comments inside tags ────────────────────────────────────────

    @Test fun lineCommentAfterVariable_isAccepted() {
        // SkyTemplate `PATTERN_VAR` ends with `(?:\h*//[^}\n]*)?` — line comment OK.
        val text = "<p>{var.key // explanation}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{var.key // explanation}", r[0].substring(text))
    }

    @Test fun lineCommentAfterFunctionCall_isAccepted() {
        val text = "<p>{=foo() // returns the foo}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
    }

    @Test fun lineCommentInsideStringIsNotStripped() {
        // `'a//b'` is a string — the `//` inside is content, not a comment.
        // This still registers as template because the body has `:` from `'`?
        // Actually it has `|` for pipe? No. Just verify it doesn't crash.
        val text = "<p>{var.key|fmt='a//b'}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
    }

    /**
     * Regression: `{/// end}` is `{/}` (closer) immediately followed by
     * `// end` (line comment). The ranges scanner must recognise it as a
     * template tag — without that, the closer for an open block is missing
     * and the whole `{@x ...}` block stops folding / annotating.
     */
    @Test fun closerWithAdjacentLineComment_isTemplate() {
        val text = "{@items}\n  body\n{/// 닫기}"
        val r = ranges(text)
        assertEquals(2, r.size)
        assertEquals("{@items}", r[0].substring(text))
        assertEquals("{/// 닫기}", r[1].substring(text))
    }

    @Test fun closerWithSpacedLineComment_isTemplate() {
        val text = "{?cond}\n  body\n{/  // 닫기}"
        val r = ranges(text)
        assertEquals(2, r.size)
        assertEquals("{?cond}", r[0].substring(text))
        assertEquals("{/  // 닫기}", r[1].substring(text))
    }

    @Test fun bodyStartingWithDoubleSlash_isNotTemplate() {
        // Defends the closer-pattern fix from accepting `{// js comment ...}`
        // as a template tag — that body doesn't have the closer shape, so it
        // must still be rejected.
        assertFalse(SkyTemplateRanges.looksLikeTemplateBody("{// js comment}", 0, 15))
    }

    @Test fun looksLikeTemplateBody_lineComments() {
        fun check(s: String) = SkyTemplateRanges.looksLikeTemplateBody(s, 0, s.length)
        // `//` after a tag/variable is allowed.
        assertTrue(check("{var // comment}"))
        assertTrue(check("{=foo() // returns x}"))
        assertTrue(check("{?cond // condition}"))
        assertTrue(check("{var.key.sub // path}"))
        assertTrue(check("{if x // when x is truthy}"))
        // `//` inside a string is content, not a comment.
        assertTrue(check("{var|fmt='a//b'}"))
        // `//` BEFORE meaningful content shouldn't make a CSS body look templatey.
        // (The `effectiveBodyEnd` is at `//`, so if leading body is junk before, fail.)
        assertFalse(check("{ color: red // pretty }"))
    }

    // ── tag inside JS function body (regression for serviceLimit case) ─────

    @Test fun templateTagInsideJsFunctionBody_isDetected() {
        // The exact case the user reported.
        val text = """
            function getNoServiceAlert(){
                {=serviceLimit('A1')}
            }
        """.trimIndent()
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{=serviceLimit('A1')}", r[0].substring(text))
    }

    @Test fun multipleTemplateTagsInsideJsFunction() {
        val text = """
            function f() {
                {?cond}
                let x = {=foo()};
                {/}
            }
        """.trimIndent()
        val r = ranges(text)
        // `{?cond}`, `{=foo()}`, `{/}` — three template tags inside a JS function.
        assertEquals(3, r.size)
    }

    @Test fun templateTagWithSpacedDotConcat_rawPrefix_inJsFunction() {
        // The user-reported case: a `=`-prefixed concat tag inside a JS function
        // body must still register as a single template range so JS error
        // filtering and brace matching see the construct as opaque.
        val text = "function f() { var x = '{=foo() . bar()}'; }"
        val r = ranges(text)
        // We expect at least the template tag to register. The JS function body
        // `{ var x = '...'; }` should NOT register (it has `;` and no template
        // signals) — but we don't assert on count because the brace-pair
        // scanner emits non-template pairs that get filtered downstream.
        val tagRange = r.firstOrNull { it.substring(text).startsWith("{=") }
        assertTrue("expected a `{=…}` range; got: ${r.map { it.substring(text) }}", tagRange != null)
        assertEquals("{=foo() . bar()}", tagRange!!.substring(text))
    }

    @Test fun templateTagWithSpacedDotConcat_ifPrefix_inJsFunction() {
        val text = "function f() { var x = '{? foo() . bar()}'; }"
        val r = ranges(text)
        val tagRange = r.firstOrNull { it.substring(text).startsWith("{?") }
        assertTrue("expected a `{?…}` range; got: ${r.map { it.substring(text) }}", tagRange != null)
        assertEquals("{? foo() . bar()}", tagRange!!.substring(text))
    }

    // ── PHP `<?…?>` block exclusion (string literal mis-detection guard) ──────

    /**
     * Regression: PHP source containing a string literal like
     *   `'<?php if (%s) { ?>'`
     * had its `{ ?` mis-detected as a `{?…}` directive — `?` passes the
     * tag-prefix-char check, leading whitespace is allowed, and
     * `findBracePairs` paired the literal's `{` with the function's closing
     * `}` to invent a bogus tag span. PHP-block exclusion fixes this by
     * removing the entire `<?php … ?>` region from brace-pair candidates.
     */
    @Test fun phpStringLiteralWithBraceQuestion_isNotTemplate() {
        val text = "<?php\nreturn sprintf('<?php if (%s) { ?>', \$arg);\n"
        assertTrue("PHP string literal must not register as template, got ${ranges(text)}",
            ranges(text).isEmpty())
    }

    @Test fun phpBlockOpenBraceInHtmlHost_isNotTemplate() {
        // `.html` host file with an embedded PHP if-block. Without exclusion
        // the `{` from `<?php if (..) { ?>` paired with `}` from
        // `<?php } ?>` and the body `' ?>...<?php '` started with `?`, a
        // tag-prefix char — both braces are inside PHP regions.
        val text = "<html>\n<?php if (\$foo) { ?>\n<p>show</p>\n<?php } ?>\n</html>"
        assertTrue("HTML+PHP if-block must not register as template, got ${ranges(text)}",
            ranges(text).isEmpty())
    }

    @Test fun realTemplateOutsidePhpBlock_isStillDetected() {
        // PHP block excluded, but the `{name}` outside it must still register.
        val text = "<?php do_thing(); ?>\n<p>{name}</p>\n<?php finish(); ?>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{name}", r[0].substring(text))
    }

    @Test fun phpShortEcho_isExcluded() {
        // `<?= … ?>` short echo tag — body is PHP code, not template.
        val text = "<p><?= '{ wrong }' ?></p>"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun phpInsideSkyTemplateLoop_doesNotBreakLoopRange() {
        // Real SkyTemplate `{loop foo}…{/}` wrapping a `<?php …?>` block.
        // The PHP block sits between the open and close — it should be
        // excluded from brace-pair scanning, but the outer template tags
        // must still register.
        val text = "{loop foo}\n<?php something(); ?>\n{/}"
        val r = ranges(text)
        assertEquals(2, r.size)
        assertEquals("{loop foo}", r[0].substring(text))
        assertEquals("{/}", r[1].substring(text))
    }

    @Test fun unclosedPhpBlockExtendsToEof() {
        // A PHP-only file without a closing `?>` (the common case for class
        // files): everything from `<?php` to EOF is excluded.
        val text = "<?php\nfunction foo() {\n    return '{ wrong }';\n}\n"
        assertTrue(ranges(text).isEmpty())
    }

    @Test fun xmlDeclarationIsNotPhpBlock() {
        // `<?xml … ?>` must NOT be confused with PHP. Templates AFTER the
        // XML declaration must still register.
        val text = "<?xml version=\"1.0\"?>\n<root>{name}</root>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{name}", r[0].substring(text))
    }

    @Test fun phpCommentLikeSequenceInPhpBlock_isExcluded() {
        // `{*…*}` inside PHP code is a SkyTemplate-shaped sequence but lives
        // inside PHP — must NOT register as a comment range.
        val text = "<?php \$s = '{* not a comment *}'; ?>\n<p>{name}</p>"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("{name}", r[0].substring(text))
    }
}
