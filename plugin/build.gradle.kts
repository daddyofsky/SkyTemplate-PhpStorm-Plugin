import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.novaframework"
version = "1.1.5"

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
            untilBuild.set("261.*")
        }
        changeNotes.set(
            """
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
