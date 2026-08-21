# SkyTemplate — PhpStorm Plugin

PhpStorm support for the **SkyTemplate** PHP template engine (Nova Framework).
The same code path also handles **Template_** (xtac.net) — both engines share
the directive surface this plugin cares about, so existing Template_ projects
work without extra configuration.

Works in dedicated `*.sky` partials and in `*.html` host files where
SkyTemplate constructs are embedded.

## Features

**Editor**

- Syntax highlighting for tags, variables, directives, comments, and operators
  (`*.sky` files and an annotator overlay in HTML hosts)
- Brace matching, code folding for block tags, `{*…*}` block-comment toggle
- TODO / FIXME inside `{*…*}` comments keeps the colours from
  *Settings → Editor → TODO*
- Smart Enter — auto-indent and auto-`{/}` after opening block tags
- SkyTemplate-aware indentation on Enter, Tab, Paste, Reformat Code, and
  Move Statement Up/Down — including inside `<script>` / `<style>` bodies
  that carry template tags
- Live templates (`loop`, `if`, `foreach`, …) and *New → SkyTemplate File*
  file templates

**PHP integration**

- Go to Definition / Find Usages for PHP functions, static methods, classes,
  constants, and class constants referenced from `{…}` constructs
- Completion of PHP symbols inside `{=`, `{?`, `{c.`, `{var|`
- Pipe filters (`{var|func}`) resolve formatter-class methods first, matching
  the compiler's dispatch, with global-function fallback
- Named arguments — `{=foo(name: a)}` and `{var|fn=name=value}` resolve to
  the PHP parameter; *Parameter Info* popup and inlay parameter hints
- PHP symbols referenced only from templates are marked *used*, so
  PhpStorm's "unused declaration" highlight stays correct

**Inspections & noise control**

- Unclosed block, orphan `{else}` / branch, loop-scope mismatch, redundant
  `@`, duplicate `{else}`, undefined PHP symbol, argument-count / named-arg
  validation
- HTML / XML / JS / CSS parser errors overlapping SkyTemplate constructs are
  suppressed; branch-aware duplicate-id / duplicate-declaration suppression

## Requirements

- PhpStorm **2024.2** or later (no upper bound)

## Installation

### From a GitHub release

1. Download `skytemplate-phpstorm-v<version>.zip` from the
   [latest release](https://github.com/daddyofsky/SkyTemplate-PhpStorm-Plugin/releases/latest).
2. In PhpStorm: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and
   pick the downloaded zip (do not unzip it).
3. Restart the IDE.

### From source

```bash
cd plugin
./gradlew buildPlugin
# → plugin/build/distributions/skytemplate-phpstorm-v<version>.zip
```

Then install the zip as above.

## Configuration

**Settings → Tools → SkyTemplate** (per project):

- Master *Enable SkyTemplate support* toggle
- Root namespace and `useClass` aliases for PHP symbol resolution
- Formatter class for pipe-filter method dispatch
- File-extension whitelist

## Development

```bash
cd plugin
./gradlew runIde    # sandbox PhpStorm with the plugin
./gradlew test      # run the test suite
```

See [plugin/README.md](plugin/README.md) for the source layout and dev
environment, and [plugin/CHANGELOG.md](plugin/CHANGELOG.md) for per-release
notes.
