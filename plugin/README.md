# SkyTemplate — PhpStorm Plugin (development)

Build, test, and source-layout notes for plugin developers.
For features, installation, and configuration see the
[repository README](../README.md); per-release notes are in
[CHANGELOG.md](CHANGELOG.md).

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
  common/       # Shared helpers
  settings/     # Project Settings → Tools → SkyTemplate
                #   enabled flag, namespace / useClass / formatter class,
                #   file-extension whitelist
  sky/          # Language core & editor behaviour — lexer, parser,
                #   highlighting, annotator overlay for *.html hosts,
                #   brace matching, folding, commenter, Enter / Tab / Paste /
                #   Reformat indentation, pre/post-format processors,
                #   live & file templates
  reference/    # PhpIndex-backed PsiReferences, completion, Parameter Info,
                #   inlay parameter hints, Find Usages / Go to Definition
  inspection/   # Structural & argument-validation inspections
                #   (unclosed block, orphan branch, loop scope, redundant @,
                #   duplicate {else}, undefined symbol, argument count,
                #   named args)
```

## Dev environment

- macOS, JDK 21 (Temurin), Gradle 9.0+
- IntelliJ Platform Gradle Plugin 2.1.0
- PhpStorm 2024.2 baseline
