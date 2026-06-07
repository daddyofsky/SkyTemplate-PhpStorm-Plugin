package com.novaframework.templatelang.sky

import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Test
import com.novaframework.templatelang.sky.SkyTemplateTokenTypes as T

class SkyTemplateLexerTest {

    private fun tokens(input: String): List<Pair<IElementType?, String>> {
        val lexer = SkyTemplateLexer()
        lexer.start(input, 0, input.length, 0)
        val out = mutableListOf<Pair<IElementType?, String>>()
        while (lexer.tokenType != null) {
            out += lexer.tokenType to input.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return out
    }

    @Test fun plainTextIsOuter() {
        val t = tokens("hello world")
        assertEquals(1, t.size)
        assertEquals(T.OUTER_CONTENT, t[0].first)
    }

    @Test fun simpleVariable() {
        val t = tokens("hi {name} bye")
        // OUTER, LBRACE, IDENT, RBRACE, OUTER
        assertEquals(listOf(T.OUTER_CONTENT, T.LBRACE, T.IDENTIFIER, T.RBRACE, T.OUTER_CONTENT),
            t.map { it.first })
        assertEquals("name", t[2].second)
    }

    @Test fun comment() {
        val t = tokens("a {* note *} b")
        val types = t.map { it.first }
        assertEquals(listOf(T.OUTER_CONTENT, T.COMMENT_OPEN, T.COMMENT_CONTENT, T.COMMENT_CLOSE, T.OUTER_CONTENT), types)
    }

    @Test fun nestedCommentIsOneComment() {
        // Comments nest: the outer `{*…*}` swallows the inner one whole, so
        // the inner `{*` / `*}` are COMMENT_CONTENT, not separate tokens, and
        // nothing leaks back to OUTER after the inner close.
        val t = tokens("a {* x {* y *} z *} b")
        val types = t.map { it.first }
        assertEquals(
            listOf(T.OUTER_CONTENT, T.COMMENT_OPEN, T.COMMENT_CONTENT, T.COMMENT_CLOSE, T.OUTER_CONTENT),
            types,
        )
        assertEquals(" x {* y *} z ", t[2].second)
        assertEquals("*}", t[3].second)
        assertEquals(" b", t[4].second)
    }

    @Test fun deeplyNestedComment() {
        val t = tokens("{* a {* b {* c *} d *} e *}")
        val types = t.map { it.first }
        assertEquals(listOf(T.COMMENT_OPEN, T.COMMENT_CONTENT, T.COMMENT_CLOSE), types)
        assertEquals(" a {* b {* c *} d *} e ", t[1].second)
    }

    @Test fun unterminatedNestedCommentConsumesRest() {
        // Inner pair balances but the outer never closes → the whole tail is
        // one unterminated comment (best-effort), nothing leaks to OUTER.
        val t = tokens("{* a {* b *} c")
        val types = t.map { it.first }
        assertEquals(listOf(T.COMMENT_OPEN, T.COMMENT_CONTENT), types)
        assertEquals(" a {* b *} c", t[1].second)
    }

    @Test fun nonNestedCommentsStillCloseAtFirstBalancedClose() {
        // Two independent comments separated by OUTER text — the first `*}`
        // closes the first comment (depth back to 0), it does not start
        // hunting for a second close.
        val t = tokens("{* a *} mid {* b *}")
        val types = t.map { it.first }
        assertEquals(
            listOf(
                T.COMMENT_OPEN, T.COMMENT_CONTENT, T.COMMENT_CLOSE,
                T.OUTER_CONTENT,
                T.COMMENT_OPEN, T.COMMENT_CONTENT, T.COMMENT_CLOSE,
            ),
            types,
        )
        assertEquals(" mid ", t[3].second)
    }

    @Test fun ifTagWithKeyword() {
        val t = tokens("{if user.isAdmin}x{/}")
        val types = t.map { it.first }
        // {  if  WS  user  .  isAdmin  }  x  {  /  }
        assertEquals(T.LBRACE, types[0])
        assertEquals(T.TAG_KEYWORD, types[1])
        assertEquals("if", t[1].second)
        assertEquals(T.TAG_PREFIX, types.find { it == T.TAG_PREFIX })
    }

    @Test fun upperCaseKeywordIsTagKeyword() {
        // Compiler PATTERN_TAG carries the `/i` flag, so `{LOOP …}`,
        // `{Loop …}`, and `{loop …}` are all valid keyword tags. The
        // lexer must classify them as TAG_KEYWORD so the keyword colour
        // applies regardless of casing.
        val t = tokens("{LOOP xs as x}")
        assertEquals(T.TAG_KEYWORD, t[1].first)
        assertEquals("LOOP", t[1].second)
    }

    @Test fun mixedCaseKeywordIsTagKeyword() {
        val t = tokens("{Foreach items as i}")
        assertEquals(T.TAG_KEYWORD, t[1].first)
        assertEquals("Foreach", t[1].second)
    }

    @Test fun upperCaseEndKeywordIsTagKeyword() {
        // `{END}` should pair structurally with an opener and render
        // with the keyword colour.
        val t = tokens("{END}")
        assertEquals(T.TAG_KEYWORD, t[1].first)
        assertEquals("END", t[1].second)
    }

    // ── highlight-relevant tokens (var modifiers + pipe) ───────────────────────

    @Test fun varAtParentLoopDepthEmitsAt() {
        // `{.var@2}` — leading `.` is loop-scope (SCOPE_LOOP since 0.5.20),
        // `@2` is the parent-loop depth modifier. `@` must be its own AT
        // token so the highlighter can paint it with the tag-prefix
        // accent colour.
        val t = tokens("{.var@2}")
        // { . var @ 2 }
        val types = t.map { it.first }
        assertEquals(T.LBRACE, types[0])
        assertEquals(T.SCOPE_LOOP, types[1])
        assertEquals(T.IDENTIFIER, types[2])
        assertEquals(T.AT, types[3])
        assertEquals(T.NUMBER, types[4])
        assertEquals(T.RBRACE, types[5])
    }

    @Test fun varHashZerofillEmitsHash() {
        // `{var#5}` — `#5` is zerofill width. Lexer must emit HASH so the
        // highlighter colours it consistently with `{#name}` tag-prefix
        // form.
        val t = tokens("{var#5}")
        val types = t.map { it.first }
        assertEquals(T.LBRACE, types[0])
        assertEquals(T.IDENTIFIER, types[1])
        assertEquals(T.HASH, types[2])
        assertEquals(T.NUMBER, types[3])
        assertEquals(T.RBRACE, types[4])
    }

    @Test fun pipeFunctionEmitsPipe() {
        // `{var|trim}` — `|` is the filter pipe, function name is identifier.
        val t = tokens("{var|trim}")
        val types = t.map { it.first }
        assertEquals(T.LBRACE, types[0])
        assertEquals(T.IDENTIFIER, types[1])
        assertEquals(T.PIPE, types[2])
        assertEquals(T.IDENTIFIER, types[3])
        assertEquals(T.RBRACE, types[4])
    }

    @Test fun chainedPipesEmitsMultiplePipes() {
        // `{var|date|upper}` — both `|` chars must be PIPE tokens.
        val t = tokens("{var|date|upper}")
        val pipes = t.filter { it.first == T.PIPE }
        assertEquals(2, pipes.size)
    }

    @Test fun singleDotAtTagStartIsScopeLoop() {
        // `{.name}` — leading `.` is the loop-scope marker (current loop),
        // mirroring the compiler regex `(?<scope>_|\.+|c\.)?`. Must emit
        // SCOPE_LOOP so it gets the loop-scope accent colour, not the dim
        // DOT used for property access.
        val t = tokens("{.name}")
        val types = t.map { it.first }
        // { . name }
        assertEquals(T.LBRACE, types[0])
        assertEquals(T.SCOPE_LOOP, types[1])
        assertEquals(".", t[1].second)
        assertEquals(T.IDENTIFIER, types[2])
        assertEquals("name", t[2].second)
    }

    @Test fun singleDotAfterTagPrefixIsScopeLoop() {
        // `{?.name}` — `?` is TAG_PREFIX (expression context), then the
        // single `.` is still the loop-scope marker. Walking back from
        // `.` we skip the tag-prefix char and hit `{` ⇒ at variable start.
        val t = tokens("{?.name}")
        val dotTok = t.first { it.first == T.SCOPE_LOOP || it.first == T.DOT }
        assertEquals(T.SCOPE_LOOP, dotTok.first)
        assertEquals(".", dotTok.second)
    }

    @Test fun multipleDotsAtTagStartIsScopeLoop() {
        // `{..name}` — parent loop. Already covered for `>=2` dots.
        val t = tokens("{..name}")
        assertEquals(T.SCOPE_LOOP, t[1].first)
        assertEquals("..", t[1].second)
    }

    @Test fun singleDotMidTagStaysAsDot() {
        // `{var.field}` — `.` after identifier is property access, not
        // scope marker.
        val t = tokens("{var.field}")
        val types = t.map { it.first }
        // { var . field }
        assertEquals(T.IDENTIFIER, types[1])
        assertEquals(T.DOT, types[2])
        assertEquals(T.IDENTIFIER, types[3])
    }

    // ── Multi-variable expression scope-loop dot recognition (0.5.22) ────────
    // PATTERN_VAR can match anywhere inside an expression. After an operator
    // (`!`, `||`, `&&`, …) or a separator (`(`, `[`, `,`), a leading `.` is
    // a fresh variable's loop-scope marker, not property access on the
    // previous token. Lexer must classify these as SCOPE_LOOP.

    @Test fun dotAfterBangOperatorIsScopeLoop() {
        // `{? !.foo}` — `!` is OPERATOR, then `.` opens a new variable
        // expression with current-loop scope. Walking back from `.` we hit
        // `!` (operator boundary) ⇒ SCOPE_LOOP.
        val t = tokens("{? !.foo}")
        val dotTok = t.first { it.first == T.SCOPE_LOOP || it.first == T.DOT }
        assertEquals(T.SCOPE_LOOP, dotTok.first)
        assertEquals(".", dotTok.second)
    }

    @Test fun dotAfterOrOperatorIsScopeLoop() {
        // `{? a || .b}` — `||` is OPERATOR (two-char), `.b` is a fresh
        // variable with loop scope.
        val t = tokens("{? a || .b}")
        // Find the SECOND scope-loop / dot — after `a ||`.
        val scopeOrDot = t.filter { it.first == T.SCOPE_LOOP || it.first == T.DOT }
        org.junit.Assert.assertTrue("expected at least one .", scopeOrDot.isNotEmpty())
        assertEquals(T.SCOPE_LOOP, scopeOrDot[0].first)
    }

    @Test fun multipleVariablesWithBangAndOrEmitScopeLoopForEach() {
        // The user-reported case: `{? !.category.name || !.category.code}`.
        // Both `.category` occurrences must be SCOPE_LOOP; the inner `.name`
        // and `.code` must be DOT (property access).
        val t = tokens("{? !.category.name || !.category.code}")
        val dotLikes = t.filter { it.first == T.SCOPE_LOOP || it.first == T.DOT }
        // Expected order: SCOPE_LOOP, DOT, SCOPE_LOOP, DOT
        assertEquals(4, dotLikes.size)
        assertEquals(T.SCOPE_LOOP, dotLikes[0].first)
        assertEquals(T.DOT, dotLikes[1].first)
        assertEquals(T.SCOPE_LOOP, dotLikes[2].first)
        assertEquals(T.DOT, dotLikes[3].first)
    }

    @Test fun dotAfterLParenIsScopeLoop() {
        // `{=fn(.foo)}` — `(` opens an argument list, `.foo` is a fresh
        // variable with current-loop scope.
        val t = tokens("{=fn(.foo)}")
        val dotTok = t.first { it.first == T.SCOPE_LOOP || it.first == T.DOT }
        assertEquals(T.SCOPE_LOOP, dotTok.first)
    }

    @Test fun dotAfterCommaIsScopeLoop() {
        // `{=fn(a, .foo)}` — `,` separates arguments; `.foo` is fresh var.
        val t = tokens("{=fn(a, .foo)}")
        val dotLikes = t.filter { it.first == T.SCOPE_LOOP || it.first == T.DOT }
        assertEquals(1, dotLikes.size)
        assertEquals(T.SCOPE_LOOP, dotLikes[0].first)
    }

    @Test fun dotAfterRParenIsPropertyAccess() {
        // `{=fn().foo}` — `.` after `)` continues the expression on the
        // result of the call ⇒ DOT (property access), not SCOPE_LOOP.
        val t = tokens("{=fn().foo}")
        val dotLikes = t.filter { it.first == T.SCOPE_LOOP || it.first == T.DOT }
        assertEquals(1, dotLikes.size)
        assertEquals(T.DOT, dotLikes[0].first)
    }

    // ── string-concat dot vs property-access dot (whitespace adjacency) ──────
    // SkyTemplate uses a single `.` for two distinct operators:
    //   tight  `var.key`    → property/array access
    //   spaced `var . var2` → string concatenation
    // The lexer disambiguates by whitespace adjacency. Spaced dots emit
    // OPERATOR so RefDetector treats both sides as independent variables
    // instead of chaining property access.

    @Test fun spacedDotBetweenIdentifiersIsOperator() {
        // `{=var . var2}` — both vars are independent operands of string
        // concat, not a property chain.
        val t = tokens("{=var . var2}")
        val dotLikes = t.filter { it.first == T.DOT || it.first == T.OPERATOR && it.second == "." }
        assertEquals(1, dotLikes.size)
        assertEquals(T.OPERATOR, dotLikes[0].first)
        assertEquals(".", dotLikes[0].second)
    }

    @Test fun spacedDotBetweenCallsIsOperator() {
        // `{=foo() . bar()}` — concat between two call results.
        val t = tokens("{=foo() . bar()}")
        val dotLikes = t.filter { it.first == T.DOT || it.first == T.OPERATOR && it.second == "." }
        assertEquals(1, dotLikes.size)
        assertEquals(T.OPERATOR, dotLikes[0].first)
    }

    @Test fun dotWithLeadingWhitespaceOnlyIsOperator() {
        // `{=var .field}` — whitespace only on the left. Per the rule, ANY
        // whitespace adjacency tips the dot to concat operator.
        val t = tokens("{=var .field}")
        val dotLikes = t.filter { it.first == T.DOT || (it.first == T.OPERATOR && it.second == ".") }
        assertEquals(1, dotLikes.size)
        assertEquals(T.OPERATOR, dotLikes[0].first)
    }

    @Test fun dotWithTrailingWhitespaceOnlyIsOperator() {
        // `{=var. field}` — whitespace only on the right.
        val t = tokens("{=var. field}")
        val dotLikes = t.filter { it.first == T.DOT || (it.first == T.OPERATOR && it.second == ".") }
        assertEquals(1, dotLikes.size)
        assertEquals(T.OPERATOR, dotLikes[0].first)
    }

    @Test fun tightDotBetweenIdentifiersStaysProperty() {
        // `{var.key}` — no whitespace ⇒ property access (DOT). Regression
        // guard for the whitespace-adjacency rule.
        val t = tokens("{var.key}")
        val dotLikes = t.filter { it.first == T.DOT || (it.first == T.OPERATOR && it.second == ".") }
        assertEquals(1, dotLikes.size)
        assertEquals(T.DOT, dotLikes[0].first)
    }

    @Test fun tightDotIncrementalReLexFromMidTag_staysProperty() {
        // Simulate the layered editor highlighter resuming mid-tag at the `.`
        // position. Buffer carries the full text; slice starts where the
        // tag's `.` is. Lexer state is set to IN_TAG. The whitespace-
        // adjacency check must still see the real char before via the buffer
        // (not default to whitespace and flip to OPERATOR).
        val full = "{var.key}"
        val dotPos = full.indexOf('.')
        val lexer = SkyTemplateLexer()
        lexer.start(full, dotPos, full.length, SkyTemplateLexer.STATE_IN_TAG)
        // First token at the slice start should be the `.` — must be DOT
        // (property access) because the previous char `r` is a word char.
        assertEquals(T.DOT, lexer.tokenType)
        assertEquals(".", full.substring(lexer.tokenStart, lexer.tokenEnd))
    }

    @Test fun spacedDotIncrementalReLexFromMidTag_staysOperator() {
        // Mirror of the above for the spaced case. Buffer carries `{var . key}`,
        // re-lex resumes at the `.`. The previous char (a space) lives in the
        // buffer at `dotPos - 1`; the lexer must read it and emit OPERATOR.
        val full = "{var . key}"
        val dotPos = full.indexOf('.')
        val lexer = SkyTemplateLexer()
        lexer.start(full, dotPos, full.length, SkyTemplateLexer.STATE_IN_TAG)
        assertEquals(T.OPERATOR, lexer.tokenType)
        assertEquals(".", full.substring(lexer.tokenStart, lexer.tokenEnd))
    }

    @Test fun lineCommentInsideTag() {
        // `{@products // comment}` — `//` starts a line comment that runs
        // to `}` or newline.
        val t = tokens("{@products // note}")
        val lc = t.first { it.first == T.LINE_COMMENT }
        // The comment text starts at `//` and ends just before `}`.
        org.junit.Assert.assertTrue("got: ${lc.second}", lc.second.startsWith("//"))
    }

    // ── standalone escape literals (0.5.21) ──────────────────────────────────

    @Test fun standaloneOpenEscapeLiteral() {
        // `{\` at end of input — no closing `}` on this line. Must be
        // tokenised as a single ESCAPE_LITERAL covering 2 chars; the
        // lexer must NOT enter IN_TAG state (otherwise everything that
        // follows would be lexed as tag content).
        val t = tokens("{\\")
        assertEquals(1, t.size)
        assertEquals(T.ESCAPE_LITERAL, t[0].first)
        assertEquals("{\\", t[0].second)
    }

    @Test fun standaloneCloseEscapeLiteral() {
        // `\}` at start of input — no opening `{` on this line.
        val t = tokens("\\}")
        assertEquals(1, t.size)
        assertEquals(T.ESCAPE_LITERAL, t[0].first)
        assertEquals("\\}", t[0].second)
    }

    @Test fun openEscapeStandaloneFollowedByText() {
        // `{\hello\nworld` — `{\` followed by content with no `}` on
        // this line. Lexer must emit ESCAPE_LITERAL for `{\`, then
        // resume OUTER state for `hello\nworld`.
        val t = tokens("{\\hello\nworld")
        assertEquals(T.ESCAPE_LITERAL, t[0].first)
        assertEquals("{\\", t[0].second)
        // Subsequent token is plain OUTER content (no IN_TAG drift).
        assertEquals(T.OUTER_CONTENT, t[1].first)
    }

    @Test fun closeEscapeStandaloneAfterText() {
        val t = tokens("hello \\} world")
        // OUTER `hello `, then ESCAPE_LITERAL `\}`, then OUTER ` world`.
        assertEquals(T.OUTER_CONTENT, t[0].first)
        assertEquals("hello ", t[0].second)
        assertEquals(T.ESCAPE_LITERAL, t[1].first)
        assertEquals("\\}", t[1].second)
        assertEquals(T.OUTER_CONTENT, t[2].first)
    }

    @Test fun pairedEscapeStillEntersInTag() {
        // `{\}` (paired form) keeps the existing LBRACE+TAG_PREFIX+RBRACE
        // tokenisation — the lookahead finds `}` on the same line.
        val t = tokens("{\\}")
        assertEquals(T.LBRACE, t[0].first)
        assertEquals(T.TAG_PREFIX, t[1].first)
        assertEquals("\\", t[1].second)
        assertEquals(T.RBRACE, t[2].first)
    }

    @Test fun escapeDirectiveWithBodyStillEntersInTag() {
        // `{\hello}` — regular escape directive (closing `}` on same line).
        val t = tokens("{\\hello}")
        assertEquals(T.LBRACE, t[0].first)
        assertEquals(T.TAG_PREFIX, t[1].first)
        assertEquals(T.IDENTIFIER, t[2].first)
        assertEquals("hello", t[2].second)
        assertEquals(T.RBRACE, t[3].first)
    }

    @Test fun shortPrefixIf() {
        val t = tokens("{?cond}")
        // { ? cond }
        assertEquals(listOf(T.LBRACE, T.TAG_PREFIX, T.IDENTIFIER, T.RBRACE), t.map { it.first })
        assertEquals("?", t[1].second)
    }

    @Test fun reservedScopeIndex() {
        val t = tokens("{_index}")
        assertEquals(T.SCOPE_RESERVED, t[1].first)
        assertEquals("_index", t[1].second)
    }

    @Test fun loopScopeDots() {
        val t = tokens("{..parentVar}")
        // { .. parentVar }
        assertEquals(T.LBRACE, t[0].first)
        assertEquals(T.SCOPE_LOOP, t[1].first)
        assertEquals("..", t[1].second)
        assertEquals(T.IDENTIFIER, t[2].first)
    }

    @Test fun pipeFunction() {
        val t = tokens("{name|trim}")
        assertEquals(listOf(T.LBRACE, T.IDENTIFIER, T.PIPE, T.IDENTIFIER, T.RBRACE),
            t.map { it.first })
    }

    @Test fun dollarPrefixedBraceIgnored() {
        // `${name}` is JS template literal — SkyTemplate ignores it.
        val t = tokens("hi \${name} bye")
        assertEquals(1, t.size)
        assertEquals(T.OUTER_CONTENT, t[0].first)
    }

    @Test fun stringLiteral() {
        val t = tokens("""{include "header.html"}""")
        val stringTok = t.first { it.first == T.STRING }
        assertEquals("\"header.html\"", stringTok.second)
    }

    @Test fun number() {
        val t = tokens("{n#5}")
        // { n # 5 }
        assertEquals(T.LBRACE, t[0].first)
        assertEquals(T.IDENTIFIER, t[1].first)
        assertEquals(T.HASH, t[2].first)
        assertEquals(T.NUMBER, t[3].first)
    }

    @Test fun rawTagPrefix() {
        val t = tokens("{=rawHtml}")
        assertEquals(T.TAG_PREFIX, t[1].first)
        assertEquals("=", t[1].second)
    }

    // ── SCOPE_CONST regression tests (T3 / 0.5.10) ─────────────────────────────
    // Prior to 0.5.10 the lexer's `peek(0) == '.'` check on the `c` identifier
    // was a self-comparison (peek(0) returned `c` itself), so SCOPE_CONST was
    // never emitted. Reference resolution still worked via a separate
    // IDENTIFIER+"c"+DOT branch in SkyTemplateRefDetector, but the dedicated
    // SCOPE_CONST colour configured in the Color Settings page was unused.

    @Test fun constantScopeBareName() {
        val t = tokens("{c.BASE_URL}")
        // { c . BASE_URL }
        assertEquals(
            listOf(T.LBRACE, T.SCOPE_CONST, T.DOT, T.IDENTIFIER, T.RBRACE),
            t.map { it.first },
        )
        assertEquals("c", t[1].second)
        assertEquals("BASE_URL", t[3].second)
    }

    @Test fun constantScopeNamespacedConstant() {
        // {c.\App\NAME} — absolute FQN constant
        val t = tokens("{c.\\App\\NAME}")
        // { c . \ App \ NAME }
        assertEquals(T.SCOPE_CONST, t[1].first)
        assertEquals(T.DOT, t[2].first)
        assertEquals(T.NS_SEP, t[3].first)
        assertEquals(T.IDENTIFIER, t[4].first)
        assertEquals("App", t[4].second)
        assertEquals(T.NS_SEP, t[5].first)
        assertEquals(T.IDENTIFIER, t[6].first)
        assertEquals("NAME", t[6].second)
    }

    @Test fun constantScopeClassConstant() {
        // {c.Cls::CONST}
        val t = tokens("{c.Cls::CONST}")
        // { c . Cls :: CONST }
        assertEquals(T.SCOPE_CONST, t[1].first)
        assertEquals(T.DOT, t[2].first)
        assertEquals(T.IDENTIFIER, t[3].first)
        assertEquals("Cls", t[3].second)
        assertEquals(T.DBL_COLON, t[4].first)
        assertEquals(T.IDENTIFIER, t[5].first)
        assertEquals("CONST", t[5].second)
    }

    @Test fun constantScopeNamespacedClassConstant() {
        // {c.App\Enums::TYPE_A}
        val t = tokens("{c.App\\Enums::TYPE_A}")
        assertEquals(T.SCOPE_CONST, t[1].first)
        assertEquals(T.DOT, t[2].first)
        assertEquals(T.IDENTIFIER, t[3].first)
        assertEquals("App", t[3].second)
        assertEquals(T.NS_SEP, t[4].first)
        assertEquals(T.IDENTIFIER, t[5].first)
        assertEquals("Enums", t[5].second)
        assertEquals(T.DBL_COLON, t[6].first)
    }

    @Test fun bareCAlone() {
        // `{c}` — bare `c` is NOT SCOPE_CONST (no following dot)
        val t = tokens("{c}")
        assertEquals(listOf(T.LBRACE, T.IDENTIFIER, T.RBRACE), t.map { it.first })
        assertEquals("c", t[1].second)
    }

    @Test fun bareCWithSpace() {
        // `{c .NAME}` — space between `c` and `.` breaks the SCOPE_CONST marker.
        val t = tokens("{c .NAME}")
        // The `c` should be a plain IDENTIFIER, not SCOPE_CONST.
        assertEquals(T.IDENTIFIER, t[1].first)
        assertEquals("c", t[1].second)
    }

    @Test fun cFollowedByOtherChar() {
        // `{cx}` — identifier `cx` is just an identifier
        val t = tokens("{cx}")
        assertEquals(T.IDENTIFIER, t[1].first)
        assertEquals("cx", t[1].second)
    }

    // ── _ prefix reserved-scope coverage ───────────────────────────────────────

    @Test fun reservedScopeAllSuperGlobals() {
        // Coverage: SkyTemplate global access is via `_GET`/`_POST`/etc.
        val names = listOf("_GET", "_POST", "_SERVER", "_COOKIE", "_REQUEST", "_SESSION", "_FILES", "_ENV")
        for (name in names) {
            val t = tokens("{$name.x}")
            assertEquals("Expected SCOPE_RESERVED for $name (got ${t[1].first})",
                T.SCOPE_RESERVED, t[1].first)
            assertEquals(name, t[1].second)
        }
    }

    @Test fun reservedScopeLoopMembers() {
        // SkyTemplate loop-iteration reserved members.
        val names = listOf("_index", "_number", "_key", "_value")
        for (name in names) {
            val t = tokens("{$name}")
            assertEquals("Expected SCOPE_RESERVED for $name", T.SCOPE_RESERVED, t[1].first)
        }
    }

    @Test fun reservedScopeInternal() {
        // Compiler-internal names (`_top`, `_data`, `_info`) emit-from the
        // SkyTemplate runtime are still classified as reserved by the lexer's
        // `_` prefix rule.
        val names = listOf("_top", "_data", "_info")
        for (name in names) {
            val t = tokens("{$name}")
            assertEquals(T.SCOPE_RESERVED, t[1].first)
        }
    }

    // ── loop-scope dot-prefix coverage ─────────────────────────────────────────

    @Test fun loopScopeTripleDots() {
        val t = tokens("{...grandparent}")
        assertEquals(T.SCOPE_LOOP, t[1].first)
        assertEquals("...", t[1].second)
        assertEquals(T.IDENTIFIER, t[2].first)
        assertEquals("grandparent", t[2].second)
    }

    // ── Escape `{\}` regression tests (T4 / 0.5.13) ────────────────────────────
    // SkyTemplate has two `\`-prefixed forms that share the same character
    // but mean different things:
    //
    //   - `{\}` (or `{\}…{\}` paired) — escape literal: outputs `{` / `}` /
    //     a region that is NOT subject to template substitution. The `\` is
    //     the tag-prefix alias for the `escape` directive.
    //   - `{\App\foo()}` — escape directive followed by what *looks* like a
    //     PHP FQN. The compiler treats the body as opaque — no expression
    //     context, so `\App\foo()` is NOT a reference. Use `{=\App\foo()}`
    //     to actually call the namespaced function.
    //
    // The lexer's responsibility is to keep these two intents distinguishable
    // for downstream consumers (RefDetector, annotator, folding). The
    // discriminator is `atTagStart()`: a `\` IMMEDIATELY after `{` is the
    // tag prefix (`TAG_PREFIX`); a `\` later in the body is a namespace
    // separator (`NS_SEP`).

    @Test fun escapeLiteralBare() {
        // `{\}` — the lone backslash is the escape tag prefix.
        val t = tokens("{\\}")
        assertEquals(listOf(T.LBRACE, T.TAG_PREFIX, T.RBRACE), t.map { it.first })
        assertEquals("\\", t[1].second)
    }

    @Test fun escapeLiteralPair() {
        // `{\}foo{\}` — two escape literals straddling text.
        val t = tokens("{\\}foo{\\}")
        // { \ } foo { \ }
        assertEquals(T.LBRACE, t[0].first)
        assertEquals(T.TAG_PREFIX, t[1].first)
        assertEquals("\\", t[1].second)
        assertEquals(T.RBRACE, t[2].first)
        assertEquals(T.OUTER_CONTENT, t[3].first)
        assertEquals("foo", t[3].second)
        assertEquals(T.LBRACE, t[4].first)
        assertEquals(T.TAG_PREFIX, t[5].first)
        assertEquals("\\", t[5].second)
        assertEquals(T.RBRACE, t[6].first)
    }

    @Test fun escapeDirectiveWithFqnBackslashesAreNsSep() {
        // `{\App\foo()}` — leading `\` is TAG_PREFIX (escape directive); the
        // *internal* `\` is NS_SEP (namespace separator). RefDetector must
        // see these as different so it doesn't treat the body as an
        // expression — see SkyTemplateRefDetectorTest.escapeDirectiveDoesNotEmitRef.
        val t = tokens("{\\App\\foo()}")
        // { \ App \ foo ( ) }
        assertEquals(T.LBRACE, t[0].first)
        assertEquals(T.TAG_PREFIX, t[1].first)  // leading \  (atTagStart)
        assertEquals(T.IDENTIFIER, t[2].first)
        assertEquals("App", t[2].second)
        assertEquals(T.NS_SEP, t[3].first)      // internal \ (not at tag start)
        assertEquals(T.IDENTIFIER, t[4].first)
        assertEquals("foo", t[4].second)
        assertEquals(T.LPAREN, t[5].first)
        assertEquals(T.RPAREN, t[6].first)
        assertEquals(T.RBRACE, t[7].first)
    }

    @Test fun rawDirectiveWithFqnEntersExpressionContext() {
        // `{=\App\foo()}` — `=` is the expression-context prefix. The
        // following `\` is NS_SEP because we're past the tag-prefix slot.
        val t = tokens("{=\\App\\foo()}")
        // { = \ App \ foo ( ) }
        assertEquals(T.LBRACE, t[0].first)
        assertEquals(T.TAG_PREFIX, t[1].first)
        assertEquals("=", t[1].second)
        assertEquals(T.NS_SEP, t[2].first)
        assertEquals(T.IDENTIFIER, t[3].first)
        assertEquals("App", t[3].second)
        assertEquals(T.NS_SEP, t[4].first)
        assertEquals(T.IDENTIFIER, t[5].first)
        assertEquals("foo", t[5].second)
    }

    @Test fun rawDirectiveWithStaticMethodCall() {
        // `{=\Ns\Cls::method()}`
        val t = tokens("{=\\Ns\\Cls::method()}")
        assertEquals(T.TAG_PREFIX, t[1].first)
        assertEquals("=", t[1].second)
        assertEquals(T.NS_SEP, t[2].first)
        assertEquals(T.IDENTIFIER, t[3].first)
        assertEquals("Ns", t[3].second)
        assertEquals(T.NS_SEP, t[4].first)
        assertEquals(T.IDENTIFIER, t[5].first)
        assertEquals("Cls", t[5].second)
        assertEquals(T.DBL_COLON, t[6].first)
        assertEquals(T.IDENTIFIER, t[7].first)
        assertEquals("method", t[7].second)
    }

    @Test fun escapeKeywordForm() {
        // `{escape}…{/}` — keyword spelling of the escape directive.
        val t = tokens("{escape}body{/}")
        assertEquals(T.LBRACE, t[0].first)
        assertEquals(T.TAG_KEYWORD, t[1].first)
        assertEquals("escape", t[1].second)
        assertEquals(T.RBRACE, t[2].first)
    }
}
