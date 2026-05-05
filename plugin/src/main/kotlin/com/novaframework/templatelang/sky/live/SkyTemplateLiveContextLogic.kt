package com.novaframework.templatelang.sky.live

import com.novaframework.templatelang.sky.SkyTemplateRanges

/**
 * Pure helpers backing [SkyTemplateContextType]. Extracted so the
 * comment-detection logic can be unit-tested without an IDE fixture.
 *
 * The PSI-walking checks (`<script>` / `<style>` ancestor) live on the
 * context type itself — those need an [com.intellij.psi.PsiFile] and are
 * covered by [com.novaframework.templatelang.sky.live.SkyTemplateContextTypeIntegrationTest].
 */
internal object SkyTemplateLiveContextLogic {

    /**
     * `true` iff [offset] sits **strictly inside** any `{*…*}` template
     * comment range computed by [SkyTemplateRanges.computeCommentRanges]:
     *
     *   - caret at `<caret>{*…*}` (= the range's startOffset) → outside
     *     (the comment is what's *next*, not what we're in)
     *   - caret at `{<caret>*…*}` → inside (we're past the opening `{`)
     *   - caret at `{*…<caret>*}` → inside
     *   - caret at `{*…*<caret>}` → inside (right before the closing `}`)
     *   - caret at `{*…*}<caret>` (= the range's endOffset) → outside
     *
     * This `(startOffset, endOffset)` open interval matches user intent —
     * snippets should expand at boundaries where the comment is purely
     * neighbouring text, but stay suppressed once the caret has crossed into
     * the body.
     */
    fun isInsideTemplateComment(text: CharSequence, offset: Int): Boolean {
        if (text.isEmpty()) return false
        val ranges = SkyTemplateRanges.computeCommentRanges(text)
        for (r in ranges) {
            if (offset > r.startOffset && offset < r.endOffset) return true
        }
        return false
    }
}
