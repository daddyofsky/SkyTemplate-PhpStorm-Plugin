import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.novaframework"
version = "1.2.2"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // PhpStorm + PHP plugin. Uses 2024.2 baseline.
        phpstorm("2024.2")
        bundledPlugins(
            "com.jetbrains.php",
            "JavaScript",
            "com.intellij.css",
        )
        instrumentationTools()
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    // IntelliJ test framework loads classes through UrlClassLoader and expects
    // opentest4j on the test classpath even when we use JUnit 4.
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("242")
            // No upper bound — a null provider makes patchPluginXml omit the
            // `until-build` attribute, so the plugin stays installable on
            // future IDE branches (2026.2 / 262.* and later).
            untilBuild.set(provider { null })
        }
        changeNotes.set(
            """
            <h3>1.2.2</h3>
            <ul>
              <li><b>Fixed</b> &mdash; The Enter handler and the closing-tag aligner now honor the plugin&rsquo;s master switch and file-extension whitelist &mdash; disabling the plugin really disables auto-<code>{/}</code> and indent rewriting.</li>
              <li><b>Fixed</b> &mdash; Consistent block classification across Enter, Reformat, folding, and inspections: <code>{?:expr}</code> (elvis) opens a block everywhere, <code>{/foo}</code> (trailing text after <code>/</code>) is no longer treated as a closer by the indent paths, and Reformat&rsquo;s line walker unwinds an unbalanced <code>{/}</code> by indent the same way Enter and the aligner do &mdash; so &ldquo;forgot the inner close&rdquo; files settle to the same depth on every path.</li>
              <li><b>Fixed</b> &mdash; Inside SkyTemplate-bearing <code>&lt;script&gt;</code> / <code>&lt;style&gt;</code>, a line starting with <code>}</code> no longer over-indents by one level on Enter / smart-indent.</li>
              <li><b>Fixed</b> &mdash; Enter on <code>{loop x}&lt;caret&gt;text</code> moves <code>text</code> into the block body instead of stranding it after the auto-inserted <code>{/}</code>.</li>
              <li><b>Fixed</b> &mdash; Join Lines keeps a single space between words across the joined line break (tag-adjacent joins stay tight); indent-width comparisons now weigh a tab as one indent step; a rare out-of-range result after Reformat of a shrunken <code>&lt;script&gt;</code> body; a possible exception in <i>Move Statement Down</i> on the file&rsquo;s last line.</li>
              <li><b>Performance</b> &mdash; Template / comment / indent ranges and block-pairing analysis are now cached per document snapshot &mdash; typing, Enter, and Tab no longer rescan the whole file several times per keystroke.</li>
            </ul>
            <h3>1.2.1</h3>
            <ul>
              <li><b>Compatibility</b> &mdash; Removed the IDE upper-bound so the plugin stays installable on 2026.2 (build 262) and later branches.</li>
              <li><b>Improved</b> &mdash; Indentation (Enter, Paste, Reformat, and smart-indent / Tab) is now computed <b>relative to the nearest enclosing HTML or template opener&rsquo;s actual indent</b>, instead of a depth re-derived from the whole file &mdash; so a block under an unindented ancestor chain no longer over-indents, and each level adds exactly one step. A new line-indent provider extends the same rule to the platform&rsquo;s smart-indent paths.</li>
              <li><b>Fixed</b> &mdash; False JS warnings on SkyTemplate tags inside <code>&lt;script&gt;</code>: <code>Unnecessary semicolon</code> and <code>Expression statement is not assignment or call</code>, raised by the embedded-JS parser on tokens next to a tag (e.g. the <code>;</code> after <code>{=expr};</code> or the bare branches in <code>{?var}true{:}false{/}</code>), are suppressed when a SkyTemplate tag shares the line.</li>
              <li><b>Fixed</b> &mdash; Reformat Code no longer mangles SkyTemplate-bearing <code>&lt;script&gt;</code> / <code>&lt;style&gt;</code>: the embedded JS / CSS formatter used to split <code>{=json_encode(data)}</code> across lines, push the <code>;</code> of <code>const a = {=foo};</code> onto its own line, break an inline <code>{?var}true{:}false{/}</code> over several lines, insert blank lines around block tags, and mis-indent. The whole body of any <code>&lt;script&gt;</code> / <code>&lt;style&gt;</code> that contains a SkyTemplate tag is now snapshotted before formatting and restored verbatim after, so it survives Reformat exactly as written. A script / style with no Sky tag (a genuine JS object literal, plain CSS) still formats normally.</li>
              <li><b>Fixed</b> &mdash; Enter indentation inside SkyTemplate-bearing <code>&lt;script&gt;</code> / <code>&lt;style&gt;</code>: the embedded JS / CSS Enter couldn&rsquo;t see <code>{?&hellip;}</code> / <code>{/}</code> block structure, so the new line missed the brace level after a JS <code>{</code>, stayed too deep after <code>{/}</code>, or lost the block indent on a blank line. The Enter handler now owns a plain Enter inside such a body and computes the combined HTML + SkyTemplate-block + host-brace indent itself. Scoped to script / style bodies that carry a SkyTemplate tag; everything else keeps the host Enter.</li>
              <li><b>Added</b> &mdash; Branch-aware duplicate suppression: <code>Duplicate id reference</code> (HTML) and <code>Duplicate declaration</code> (JS) are no longer reported when the element sits inside a loop body or a branched <code>{?&hellip;}{:}{/}</code> / <code>{if}&hellip;{else}&hellip;{/}</code>, where the parser flattens mutually-exclusive branches into one scope. A branch-less <code>{?cond}&hellip;{/}</code> is not covered, so genuine collisions against outside content still surface.</li>
              <li><b>Added</b> &mdash; SkyTemplate-aware re-indent on <i>Paste</i> and <i>Move Statement Up/Down</i>: block bodies inside <code>{loop}&hellip;{/}</code> / <code>{?&hellip;}</code> are re-indented to their proper depth, matching what Reformat Code settles on (previously only Reformat Code did this).</li>
              <li><b>Added</b> &mdash; Template-tag-aware indent inside <code>{*&hellip;*}</code> comments: block tags (<code>{loop}&hellip;{/}</code>, <code>{if}</code>, <code>{:}</code>, &hellip;) drive body indentation on par with HTML, on Enter and on Reformat Code. Comment-scoped &mdash; an unbalanced opener inside a comment never shifts code that follows the comment.</li>
              <li><b>Added</b> &mdash; Nested <code>{*&hellip;*}</code> comments: the outer comment swallows every inner <code>{*&hellip;*}</code> whole, so the entire nested block is one comment and all inner content is neutralised.</li>
              <li><b>Fixed</b> &mdash; Rainbow Brackets (and other description-less low-severity highlights) no longer colour HTML tag <code>&lt; &gt;</code> inside <code>{*&hellip;*}</code> comments in <code>*.html</code> hosts. The highlight-info filter drops low-severity highlights that are a proper subset of a comment range, preserving the plugin&rsquo;s own full-range comment overlay.</li>
              <li><b>Removed</b> &mdash; Stale <code>.skyhtml</code> references (never a registered file type); supported surfaces remain <code>*.sky</code> and <code>*.html</code> / <code>*.htm</code> / <code>*.xml</code> hosts.</li>
            </ul>
            <h3>1.1.0</h3>
            <ul>
              <li><b>Added</b> &mdash; Named arguments support: <code>{=foo(name: a)}</code> in paren calls and <code>{var|fn=name: a, ##}</code> in pipe filters resolve to the PHP <code>Parameter</code> PSI (Find Usages, Go to Definition).</li>
              <li><b>Added</b> &mdash; <i>Parameter Info</i> popup (<code>Cmd+P</code> / <code>Ctrl+P</code>) inside SkyTemplate paren calls and pipe filters &mdash; reuses PhpStorm&rsquo;s native presentation.</li>
              <li><b>Added</b> &mdash; Inlay parameter hints for SkyTemplate calls (paren, static-method, pipe filter), respecting explicit/auto-prepend <code>##</code> placement and skipping already-named slots.</li>
              <li><b>Added</b> &mdash; Argument-validation inspections: <i>Argument count mismatch</i> (missing required / too many) and <i>Named argument issue</i> (unknown name / duplicate / positional after named). Poly-variant tolerant; types not checked.</li>
              <li><b>Changed</b> &mdash; SkyTemplate compiler <code>parseExpressionCallback</code> guards named-arg syntax against expression rewriting; <code>parseFunction</code> uses a <code>(?!=)</code> lookahead so <code>name=value</code> is recognised as named-arg only outside comparisons.</li>
              <li><b>Fixed</b> &mdash; PHP code regions (<code>&lt;?php &hellip; ?&gt;</code>, <code>&lt;?= &hellip; ?&gt;</code>, <code>&lt;? &hellip; ?&gt;</code>) inside HTML hosts no longer trigger SkyTemplate brace / comment misdetection (e.g. <code>&lsquo;&lt;?php if (%s) { ?&gt;&rsquo;</code>).</li>
              <li><b>Fixed</b> &mdash; Inlay parameter hints now decorate static-method calls (<code>{=Cls::method(a, b)}</code>) correctly &mdash; previous off-by-one trimmed the last character of the class identifier.</li>
              <li><b>Refactored</b> &mdash; Call-site collection / argument splitting / named-arg classification consolidated into <code>SkyTemplateCallArguments</code>; the inlay provider and the new inspections share one source of truth.</li>
            </ul>
            <h3>1.0.0</h3>
            <ul>
              <li>First stable release.</li>
              <li>Editor: syntax highlighting for tags, variables, directives, comments, and operators in <code>*.sky</code> files; annotator overlay paints SkyTemplate constructs in <code>*.html</code> host files.</li>
              <li>Editing: brace matching, code folding for block tags, <code>{*&hellip;*}</code> block-comment toggle, smart Enter (auto-indent and auto-<code>{/}</code>), <code>tpl-*</code> attribute whitelist.</li>
              <li>Code generation: live templates (<code>loop</code>, <code>if</code>, <code>foreach</code>, &hellip;) and <i>New &rarr; SkyTemplate File</i> templates (Empty <code>*.sky</code> partial, <code>*.html</code> page).</li>
              <li>Navigation: Find Usages and Go to Definition for PHP functions, static methods, classes, global constants, and class constants referenced from any SkyTemplate construct &mdash; in <code>*.sky</code> partials and in <code>*.html</code> hosts, including HTML attribute values like <code>&lt;a href="{=urlFor()}"&gt;</code>.</li>
              <li>Resolution: <code>useClass</code> alias expansion, configured-namespace prefix, global-namespace fallback, simple-name fallback across all namespaces. Configurable in <i>Settings &rarr; Tools &rarr; SkyTemplate</i>.</li>
              <li>Completion: PHP symbols inside <code>{=</code>, <code>{?</code>, <code>{c.</code>, <code>{var|</code> &mdash; functions auto-insert <code>()</code>, class-member chains list static methods and class constants.</li>
              <li>Inspections: <i>Unclosed block</i>, <i>Orphan {else} / branch</i>, <i>Loop-scope depth mismatch</i>, <i>Redundant `@` modifier</i>, <i>Duplicate {else} branch</i>, <i>Undefined PHP symbol</i>. Honours the master <i>Enable SkyTemplate support</i> toggle and PhpStorm's inspection profile.</li>
              <li>"Unused declaration" daemon awareness: PHP functions / methods / classes / constants referenced only from a template are no longer greyed out as unused. Use-scope is extended to <code>*.sky</code> / <code>*.html</code> for refactoring traversal.</li>
              <li>Noise control: HTML / XML / JS / CSS parser errors whose range overlaps a SkyTemplate construct are suppressed. JavaScript and CSS brace pairing in HTML hosts ignores SkyTemplate <code>{&hellip;}</code> tags.</li>
            </ul>
            """.trimIndent()
        )
    }
    publishing {
        // token via env: ORG_GRADLE_PROJECT_intellijPublishToken
    }
}

tasks {
    runIde {
        // Allocate a comfortable heap for sandbox runs.
        jvmArgs("-Xmx2g")
    }
    // Distribution artifact name: `skytemplate-phpstorm-v<version>.zip`.
    // Overrides the default `<rootProject.name>-<version>.zip`
    // (`template-lang-1.1.0.zip`) so the published zip carries the public
    // plugin name and a `v`-prefixed version, matching the release-asset
    // convention. The bundled JAR (`lib/<base>-<version>.jar`) gets the
    // same base name so the inner artifact lines up with the outer ZIP.
    buildPlugin {
        archiveBaseName.set("skytemplate-phpstorm")
        archiveVersion.set("v${project.version}")
    }
    jar {
        archiveBaseName.set("skytemplate-phpstorm")
    }
    // The IntelliJ Platform plugin's `instrumentedJar` (custom subtype) and
    // `composedJar` each produce their own archive — the bundled
    // `lib/<jar>.jar` comes from `composedJar`, so its base name has to be
    // set explicitly. Use the `AbstractArchiveTask` supertype since
    // `instrumentedJar` is not a `Jar`.
    withType(org.gradle.api.tasks.bundling.AbstractArchiveTask::class.java)
        .matching { it.name in setOf("instrumentedJar", "composedJar", "jarSearchableOptions") }
        .configureEach { archiveBaseName.set("skytemplate-phpstorm") }
}
