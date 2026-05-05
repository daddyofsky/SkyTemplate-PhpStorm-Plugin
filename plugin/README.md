# SkyTemplate — PhpStorm Plugin

PhpStorm support for the **SkyTemplate** PHP template engine (Nova Framework).
The same code path also handles **Template_** (xtac.net) — both engines share
the directive surface this plugin cares about, so existing Template_ projects
work without extra configuration.

Status: **early scaffold** — Milestones M1, M2 (HTML annotator + integration),
M4 (PHP-symbol references + completion) are largely done.

See [../PLAN.md](../PLAN.md) for the full roadmap.

## Build

```bash
./gradlew buildPlugin
# → build/distributions/template-lang-<version>.zip
```

## Run sandbox PhpStorm with the plugin

```bash
./gradlew runIde
```

## Test

```bash
./gradlew test
```

## Layout

```
src/main/kotlin/com/novaframework/templatelang/
  settings/                      # Project Settings → Tools → SkyTemplate
    TemplateLangSettings.kt        # enabled flag + namespace / useClass / templateRoot / safeMode mirrors
    TemplateLangConfigurable.kt
  sky/                           # Lexer / parser / annotator / highlighting / brace matcher
    SkyTemplateLanguage.kt
    SkyTemplateFileType.kt        # *.sky / *.skyhtml
    SkyTemplateLexer.kt
    SkyTemplateAnnotator.kt       # *.html token overlay
    SkyTemplateHtmlErrorFilter.kt # drop HTML/XML errors that overlap template tokens
    SkyTemplateAttributeDescriptorsProvider.kt   # whitelist `tpl-*`
    …
  reference/                     # PhpIndex-backed PsiReferences + completion
    SkyTemplateReferenceContributor.kt
    SkyTemplateReferenceProvider.kt
    SkyTemplatePhpReference.kt
    SkyTemplateRefDetector.kt
    SkyTemplateCompletionContributor.kt
```

## Dev environment

- macOS, JDK 21 (Temurin), Gradle 9.0+
- IntelliJ Platform Gradle Plugin 2.1.0
- PhpStorm 2024.2 baseline
