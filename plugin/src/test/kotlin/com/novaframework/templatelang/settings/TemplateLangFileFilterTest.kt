package com.novaframework.templatelang.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies the file-extension whitelist gate. Mirrors every behaviour
 * documented on [TemplateLangFileFilter]:
 *
 *   - master switch off → reject
 *   - SkyTemplate own file type → always accept
 *   - extension match (case-insensitive, dot-less) → accept
 *   - no extension / empty name → reject
 *   - whitelist mutations (add / remove / clear) reflected immediately
 */
class TemplateLangFileFilterTest : BasePlatformTestCase() {

    private fun settings() = TemplateLangSettings.getInstance(project)

    override fun setUp() {
        super.setUp()
        // Defensive: tests pollute settings via the `state` reference, so
        // restore the documented defaults at every entry.
        settings().loadState(TemplateLangSettings.State())
    }

    fun testNullFileRejected() {
        assertFalse(TemplateLangFileFilter.shouldProcess(null))
    }

    fun testHtmlAcceptedByDefault() {
        val file = myFixture.configureByText("page.html", "<p>{=foo()}</p>")
        assertTrue(TemplateLangFileFilter.shouldProcess(file))
    }

    fun testHtmlUppercaseAccepted() {
        // Case-insensitive: `.HTML` matches whitelist `html`.
        val file = myFixture.configureByText("page.HTML", "<p>{=foo()}</p>")
        assertTrue(TemplateLangFileFilter.shouldProcess(file))
    }

    fun testMdRejectedByDefault() {
        // .md is the canonical false-positive case Phase 5 is built to fix.
        val file = myFixture.configureByText("notes.md", "{?cond}txt{/}")
        assertFalse(TemplateLangFileFilter.shouldProcess(file))
    }

    fun testPyRejectedByDefault() {
        val file = myFixture.configureByText("script.py", "x = '{=foo()}'")
        assertFalse(TemplateLangFileFilter.shouldProcess(file))
    }

    fun testTxtRejectedByDefault() {
        val file = myFixture.configureByText("readme.txt", "{?cond}{/}")
        assertFalse(TemplateLangFileFilter.shouldProcess(file))
    }

    fun testNoExtensionRejected() {
        // Dotless name (e.g. `Makefile`, `README`) cannot match any
        // extension whitelist entry.
        val file = myFixture.configureByText("README", "{?cond}{/}")
        assertFalse(TemplateLangFileFilter.shouldProcess(file))
    }

    fun testSkyAlwaysAcceptedEvenWhenWhitelistEmpty() {
        // Own file type guarantee — the whitelist is irrelevant for `.sky`.
        settings().state.fileExtensions = mutableListOf()
        val file = myFixture.configureByText("page.sky", "{?cond}txt{/}")
        assertTrue(TemplateLangFileFilter.shouldProcess(file))
    }

    fun testHtmlRejectedWhenRemovedFromWhitelist() {
        settings().state.fileExtensions = mutableListOf("sky")
        val file = myFixture.configureByText("page.html", "<p>{=foo()}</p>")
        assertFalse(TemplateLangFileFilter.shouldProcess(file))
    }

    fun testXmlAcceptedWhenAddedToWhitelist() {
        // Default whitelist excludes `.xml` — explicit opt-in path.
        settings().state.fileExtensions = mutableListOf("html", "sky", "xml")
        val file = myFixture.configureByText("data.xml", "<x>{=foo()}</x>")
        assertTrue(TemplateLangFileFilter.shouldProcess(file))
    }

    fun testRejectedWhenMasterSwitchOff() {
        settings().state.enabled = false
        try {
            val file = myFixture.configureByText("page.html", "<p>{=foo()}</p>")
            assertFalse(TemplateLangFileFilter.shouldProcess(file))
        } finally {
            settings().state.enabled = true
        }
    }

    fun testSkyRejectedWhenMasterSwitchOff() {
        // Master switch trumps the own-file-type fast-path — disabling
        // the plugin must silence even `.sky` file processing.
        settings().state.enabled = false
        try {
            val file = myFixture.configureByText("page.sky", "{?cond}{/}")
            assertFalse(TemplateLangFileFilter.shouldProcess(file))
        } finally {
            settings().state.enabled = true
        }
    }

    fun testEmptyWhitelistAcceptsOnlySkyFile() {
        settings().state.fileExtensions = mutableListOf()
        val sky = myFixture.configureByText("a.sky", "{?cond}{/}")
        assertTrue(TemplateLangFileFilter.shouldProcess(sky))

        val html = myFixture.configureByText("b.html", "<p>{=foo()}</p>")
        assertFalse(TemplateLangFileFilter.shouldProcess(html))
    }

    fun testDotlessAndDottedInputBothMatch() {
        // Settings can store either form; the accessor normalises to dotless
        // lower-case, so ".HTM" and "HTM" are equivalent post-normalisation.
        settings().state.fileExtensions = mutableListOf(".HTM")
        val file = myFixture.configureByText("legacy.htm", "{?cond}{/}")
        assertTrue(TemplateLangFileFilter.shouldProcess(file))
    }

    fun testShouldProcessVirtualFile_HtmlDefault() {
        val file = myFixture.configureByText("page.html", "<p>{=foo()}</p>")
        assertTrue(TemplateLangFileFilter.shouldProcessVirtualFile(project, file.virtualFile))
    }

    fun testShouldProcessVirtualFile_MdRejected() {
        val file = myFixture.configureByText("notes.md", "{?cond}{/}")
        assertFalse(TemplateLangFileFilter.shouldProcessVirtualFile(project, file.virtualFile))
    }

    fun testShouldProcessVirtualFile_NullRejected() {
        assertFalse(TemplateLangFileFilter.shouldProcessVirtualFile(project, null))
    }

    fun testShouldProcessVirtualFile_DisabledRejected() {
        val file = myFixture.configureByText("page.html", "<p>{=foo()}</p>")
        settings().state.enabled = false
        try {
            assertFalse(TemplateLangFileFilter.shouldProcessVirtualFile(project, file.virtualFile))
        } finally {
            settings().state.enabled = true
        }
    }

    fun testShouldProcessVirtualFile_AcceptsSkyByExtension() {
        // VirtualFile-based gate cannot inspect PSI for SkyTemplateFile, but
        // `sky` sits in the default whitelist so the result still matches
        // the PsiFile gate's outcome under default settings.
        val file = myFixture.configureByText("a.sky", "{?cond}{/}")
        assertTrue(TemplateLangFileFilter.shouldProcessVirtualFile(project, file.virtualFile))
    }
}
