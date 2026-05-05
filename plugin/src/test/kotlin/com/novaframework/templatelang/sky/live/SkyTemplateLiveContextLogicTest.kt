package com.novaframework.templatelang.sky.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [SkyTemplateLiveContextLogic.isInsideTemplateComment].
 *
 * The detector wraps [com.novaframework.templatelang.sky.SkyTemplateRanges.computeCommentRanges],
 * which already has a 11-test pure suite — so this file focuses on the
 * boundary semantics specific to live-template suppression: caret right at
 * `{|*` or `*|}` should still suppress completion.
 */
class SkyTemplateLiveContextLogicTest {

    @Test fun outsideAnyComment() {
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment("hello {name}", 5))
    }

    @Test fun emptyText() {
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment("", 0))
    }

    @Test fun textWithoutBrace() {
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment("plain text", 3))
    }

    @Test fun insideShortComment() {
        // 0 1 2 3 4 5 6 7 8 9 10
        // h e l l o   { * x  *   }
        val text = "hello {*x*}"
        assertTrue(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 8))
    }

    @Test fun atCommentOpenBoundary() {
        val text = "hello {*x*}"
        // offset 6 is AT '{' (the range's startOffset) — caret is between
        // "hello " and the comment, considered outside.
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 6))
        // offset 7 is just past '{' (`{<caret>*x*}`) — inside.
        assertTrue(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 7))
    }

    @Test fun atCommentCloseBoundary() {
        val text = "hello {*x*}"
        // offset 10 is at '}' itself — still strictly inside the range (10 < 11)
        assertTrue(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 10))
        // offset 11 is immediately past '}' — outside.
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 11))
    }

    @Test fun rightAfterCommentClose() {
        val text = "hello {*x*}body"
        // offset 12 is past the comment — first char of "body"
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 12))
    }

    @Test fun multilineCommentMidBody() {
        val text = "{*\n  long\n  comment\n*}body"
        // offset 10 falls inside the body
        assertTrue(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 10))
    }

    @Test fun multilineCommentAfterClose() {
        val text = "{*\n  long\n  comment\n*}body"
        // After the comment ends, "body" starts
        val bodyStart = text.indexOf("body")
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, bodyStart))
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, bodyStart + 1))
    }

    @Test fun outsideCommentInTagIsActive() {
        // Caret inside `{loop xs as x}` body — NOT a comment, suppression off.
        val text = "{loop xs as x}body{/}"
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 5))
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 14))
    }

    @Test fun multipleCommentsOnlyHitsCorrectOne() {
        // Two comments separated by body text.
        // 0...      .....11..........22
        //  {* a *} hello {* b *}
        val text = "{* a *} hello {* b *}"
        // Inside the first comment
        assertTrue(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 3))
        // Between the two comments — active
        assertFalse(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 10))
        // Inside the second comment
        assertTrue(SkyTemplateLiveContextLogic.isInsideTemplateComment(text, 17))
    }
}
