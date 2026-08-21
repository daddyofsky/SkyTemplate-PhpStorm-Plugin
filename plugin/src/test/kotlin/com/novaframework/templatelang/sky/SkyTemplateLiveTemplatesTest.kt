package com.novaframework.templatelang.sky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the shipped live-template snippets
 * (`resources/liveTemplates/SkyTemplate.xml`). Pure unit test — no IDE
 * fixture needed, reads the resource via the same classloader the platform
 * uses to load `defaultLiveTemplates` at runtime.
 */
class SkyTemplateLiveTemplatesTest {

    private fun liveTemplatesXml(): String {
        val cl = SkyTemplateFileTemplates::class.java.classLoader
        val text = cl.getResourceAsStream("liveTemplates/SkyTemplate.xml")
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("liveTemplates/SkyTemplate.xml must ship in JAR", text)
        return text!!
    }

    @Test fun elifSnippetUsesCompilerSupportedColonBranchForm() {
        // The compiler's PATTERN_TAG keyword alternation lists `else` but NOT
        // `elseif` — `{elseif $COND$}` falls through to the "invalid tag"
        // branch and is never compiled as a conditional branch at all. The
        // supported spelling for "else with a condition" is the colon-prefix
        // form `{:cond}` (tagAlias `':' => 'else'`; `tagElse` emits `elseif`
        // PHP when its `$arg` is non-empty).
        val xml = liveTemplatesXml()
        val match = Regex("""<template name="elif"[\s\S]*?value="([^"]*)"""").find(xml)
        assertNotNull("elif template must exist in liveTemplates/SkyTemplate.xml", match)
        val value = match!!.groupValues[1]

        assertFalse(
            "elif snippet must not insert the compiler-unsupported `{elseif …}` keyword form: $value",
            value.contains("elseif"),
        )
        assertTrue(
            "elif snippet must insert the compiler-supported `{:\$COND\$}` colon-branch form: $value",
            value.startsWith("{:\$COND\$}"),
        )
    }
}
