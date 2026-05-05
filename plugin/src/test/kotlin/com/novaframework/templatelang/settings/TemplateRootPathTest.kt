package com.novaframework.templatelang.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [TemplateRootPath]'s pure path-resolution logic. The
 * `TemplateRootResourceMarker` itself depends on the IDE's VFS / module
 * system, so these unit tests focus exclusively on the path math.
 */
class TemplateRootPathTest {

    @Test fun simpleRelativePath() {
        assertEquals(
            "/home/me/proj/view",
            TemplateRootPath.resolveAbsolute("/home/me/proj", "view"),
        )
    }

    @Test fun nestedRelativePath() {
        assertEquals(
            "/home/me/proj/app/Views/templates",
            TemplateRootPath.resolveAbsolute("/home/me/proj", "app/Views/templates"),
        )
    }

    @Test fun strippedLeadingDotSlash() {
        assertEquals(
            "/home/me/proj/view",
            TemplateRootPath.resolveAbsolute("/home/me/proj", "./view"),
        )
    }

    @Test fun strippedTrailingSlash() {
        assertEquals(
            "/home/me/proj/view",
            TemplateRootPath.resolveAbsolute("/home/me/proj", "view/"),
        )
    }

    @Test fun strippedMultipleTrailingSlashes() {
        assertEquals(
            "/home/me/proj/view",
            TemplateRootPath.resolveAbsolute("/home/me/proj", "view///"),
        )
    }

    @Test fun normalisesBackslashes() {
        assertEquals(
            "/home/me/proj/app/Views",
            TemplateRootPath.resolveAbsolute("/home/me/proj", "app\\Views"),
        )
    }

    @Test fun absolutePathPosixIsReturnedAsIs() {
        assertEquals(
            "/var/www/templates",
            TemplateRootPath.resolveAbsolute("/home/me/proj", "/var/www/templates"),
        )
    }

    @Test fun absolutePathWindowsIsReturnedAsIs() {
        // Windows drive letter — `C:/Templates`. The resolver normalises
        // backslashes first, so `C:\Templates` also passes.
        assertEquals(
            "C:/Templates",
            TemplateRootPath.resolveAbsolute("C:/Projects/myproj", "C:\\Templates"),
        )
    }

    @Test fun trimmedSurroundingWhitespace() {
        assertEquals(
            "/home/me/proj/view",
            TemplateRootPath.resolveAbsolute("/home/me/proj", "  view  "),
        )
    }

    @Test fun trimmedTrailingSlashOnBase() {
        assertEquals(
            "/home/me/proj/view",
            TemplateRootPath.resolveAbsolute("/home/me/proj/", "view"),
        )
    }

    @Test fun blankTemplateRootReturnsNull() {
        assertNull(TemplateRootPath.resolveAbsolute("/home/me/proj", ""))
        assertNull(TemplateRootPath.resolveAbsolute("/home/me/proj", "   "))
        assertNull(TemplateRootPath.resolveAbsolute("/home/me/proj", null))
    }

    @Test fun bareDotSlashTemplateRootReturnsNull() {
        // `./` after stripping leading `./` becomes empty, then trimEnd('/')
        // leaves it empty too — return null instead of pretending it's the
        // project base.
        assertNull(TemplateRootPath.resolveAbsolute("/home/me/proj", "./"))
    }
}
