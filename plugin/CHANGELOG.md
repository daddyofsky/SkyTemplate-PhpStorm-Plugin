# Changelog

All notable changes to the **SkyTemplate** PhpStorm plugin are recorded in
this file. The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to semantic versioning.

## [1.2.0] — 2026-06-07

Rolls up the work on top of 1.1.0: block-tag indent / Reformat groundwork,
embedded `<script>` / `<style>` Reformat and Enter handling, branch-aware
duplicate suppression, and the comment-handling and fixes below.

### Fixed

- **False JS warnings on SkyTemplate tags inside `<script>`** — the embedded
  JavaScript parser raises `Unnecessary semicolon` and `Expression statement
  is not assignment or call` on tokens adjacent to a Sky tag (the `;` after
  `{=expr};`, or the bare `true` / `false` branches in `{?var}true{:}false{/}`),
  because it reads the `{…}` as JS. These are now suppressed when a SkyTemplate
  tag shares the same line as the highlight. Scoped to that pair of diagnostics
  and gated on line overlap, so genuine JS warnings elsewhere still surface.
- **Reformat Code mangling SkyTemplate-bearing `<script>` / `<style>`** —
  `<script>` is lexer-embedded JavaScript (and `<style>` is embedded CSS), so
  the JS / CSS formatter owns those regions and reads `{…}` as code. On
  Reformat it mangled the *whole* region: it split a tag like
  `{=json_encode(data)}` across lines, pushed the `;` of
  `const a = {=foo};` onto its own line, broke an inline
  `{?var}true{:}false{/}` over several lines, inserted blank lines around the
  block tags, and re-indented the body wrongly. A pre-format pass now
  snapshots the **entire body** of any `<script>` / `<style>` that contains a
  SkyTemplate tag (as a `RangeMarker` + its original text); the post-format
  pass restores it verbatim before the existing block-body re-indent runs, so
  SkyTemplate-structured embedded code survives Reformat exactly as written. A
  `<script>` / `<style>` with no SkyTemplate tag (a genuine JS object literal
  `{a: 1, b: 2}`, plain CSS) is left untouched and still formats normally; a
  stray HTML-context tag keeps its prior per-tag restore. A
  `MultiHostInjector`-based "placeholder injection" was evaluated first and
  proven unworkable (the `<script>` body is `JSEmbeddedContentImpl`, not a
  `PsiLanguageInjectionHost`); see
  `docs/design/spike-verdict-script-injection.md`.
- **Enter indentation inside SkyTemplate-bearing `<script>` / `<style>`** —
  the embedded JS / CSS Enter handler can't see `{?…}` / `{:}` / `{/}` block
  structure, so a new line landed at the host's Sky-blind depth: after a JS
  `{` inside a `{?var}` block it missed the brace level, after `{/}` it stayed
  one level too deep, and on a blank line it lost the block indent entirely
  (and for JS the host re-indented even after our post-Enter correction). The
  Enter handler now OWNS a plain Enter inside such a body — it inserts the
  newline itself and computes the combined indent (HTML + SkyTemplate block
  depth via the shared `computeIndentForLine`, plus the host language's own
  `{` nesting that the SkyTemplate walk can't see), so the caret lands at the
  right column. Scoped to `<script>` / `<style>` bodies that actually carry a
  SkyTemplate tag; everything else keeps the host's native Enter.

### Added

- **Branch-aware duplicate suppression** — `Duplicate id reference` (HTML)
  and `Duplicate declaration` (JS function / variable redeclaration) are no
  longer reported when the offending element sits inside a **loop body** or a
  **branched** `{?…}{:}{/}` / `{if}…{else}…{/}`. The HTML / JS parser flattens
  every branch and loop iteration into a single scope, so identical ids /
  declarations across mutually-exclusive branches (or inside a repeating loop)
  look like duplicates when they never coexist at runtime. A branch-less
  `{?cond}…{/}` is intentionally **not** covered, so a real collision between
  the conditional body and identical content outside the `if` still surfaces.
- **SkyTemplate-aware re-indent on Paste and Move Statement Up/Down** — body
  lines inside `{loop …}…{/}` / `{?…}` block tags are now re-indented to their
  proper combined HTML+Sky depth after a paste (`copyPastePostProcessor`) or a
  *Move Statement Up/Down* (`statementUpDownMover`), matching the result
  Reformat Code settles on. Previously only the explicit Reformat Code path
  ran the block-body re-indent, so pasted / moved Sky blocks stayed at the
  host indent level.
- **Template-tag-aware indent inside `{*…*}` comments** — block tags written
  inside a comment (`{loop}…{/}`, `{if}…{/}`, `{:}`, …) now drive body
  indentation on par with HTML tags, both on Enter and on Reformat Code. The
  indent is **comment-scoped**: an unbalanced opener inside a comment (e.g. a
  `{loop}` with no `{/}`) never shifts the indentation of real code that
  follows the comment.
- **Nested `{*…*}` comments** — comments now nest. The outer `{*…*}` swallows
  every inner `{*…*}` whole, so the entire nested block is one comment and all
  inner content is neutralised by the outer pair (no leak back to live content
  after an inner `*}`).

### Fixed

- **Rainbow Brackets (and other description-less low-severity highlights) no
  longer colour HTML tag `< >` inside `{*…*}` comments** in `*.html` host
  files. HTML stays the primary PSI for `*.html`, so the platform still builds
  tag PSI inside a comment; the highlight-info filter now drops low-severity,
  description-less highlights that sit as a proper subset of a comment range,
  while preserving the plugin's own full-range comment overlay.

### Removed

- **`.skyhtml` extension references** — `.skyhtml` was never a registered file
  type (only `*.sky` is). Stale mentions in docs/comments were removed to avoid
  implying support; `*.sky` (SkyTemplate file type) and `*.html` / `*.htm` /
  `*.xml` (HTML host) remain the supported surfaces.

## [1.1.0] — 2026-05-05

### Added

- **Named arguments** — `{=foo(name: a)}` in paren calls and
  `{var|fn=name: a, ##}` in pipe filters resolve to the PHP `Parameter` PSI.
  Find Usages and Go to Definition both treat the named-arg identifier as a
  reference to the corresponding parameter; the SkyTemplate compiler emits
  `name: a` syntax that PHP 8 accepts natively.
- **Parameter Info popup** — `Cmd+P` / `Ctrl+P` inside SkyTemplate paren
  calls (`{=foo(|)}`) and pipe filters (`{var|fn=|}`) shows the callee's
  signature using PhpStorm's native presentation, with the active argument
  highlighted as you type.
- **Inlay parameter hints** — positional arguments in SkyTemplate calls now
  display a `name:` chip pointing at the corresponding PHP parameter.
  Already-named slots are skipped; pipe filters honour both auto-prepend and
  explicit `##` placement so chip indices match the compiler's slot map
  (e.g. `{x|sprintf=%05d, ##}` correctly chips `%05d` as the format string).
- **Argument-validation inspections** — two new entries under
  *Settings → Editor → Inspections → SkyTemplate*:
  - *Argument count mismatch* — flags missing required arguments and too
    many arguments (variadic-aware, default-aware).
  - *Named argument issue* — flags unknown parameter names, duplicate named
    arguments, and positional arguments after named ones.
  Both are poly-variant tolerant (the most permissive resolution wins) and
  intentionally skip type checks. Object-method calls (`{=user.method(...)}`)
  remain out of scope.

### Changed

- SkyTemplate compiler `parseExpressionCallback` guards `name: value` from
  being mis-rewritten as a ternary or label, so paren named-arg syntax is
  forwarded verbatim to the underlying PHP call.
- `parseFunction` uses a `(?!=)` lookahead when matching pipe-filter
  `name=value` named-arg shape, so `count==2` (comparison) is left as a
  positional expression and only single-`=` patterns are treated as named.

### Fixed

- PHP code regions inside HTML hosts (`<?php … ?>`, `<?= … ?>`, `<? … ?>`)
  are now excluded from SkyTemplate brace and comment detection. Templates
  embedded in PHP — for example `'<?php if (%s) { ?>'` near the top of a
  file — no longer mis-trigger `{?…}` parsing.
- *Inlay parameter hints* now correctly decorate static-method calls
  (`{=Cls::method(a, b)}`). The 1.0.0 hint provider trimmed one byte too
  many when extracting the class identifier from the `Cls::` prefix, which
  caused the lookup to miss and emit no chips at all for static-method
  calls.

### Refactored

- Call-site collection, argument splitting, and named-arg classification
  consolidated into a single object — `SkyTemplateCallArguments`. The
  inlay parameter-hints provider and the new argument-validation
  inspections both delegate to it, eliminating two parallel implementations
  of the same lexing logic and the latent off-by-one fixed above.

## [1.0.0] — 2026-04-29

Initial stable release. See `plugin/build.gradle.kts` `changeNotes` /
`plugin/README.md` for the full feature inventory: lexer & highlighter for
`*.sky` files, HTML host annotator overlay, brace matching, code folding,
block-comment toggle, smart Enter, `tpl-*` attribute whitelist, live
templates, *New → SkyTemplate File* templates, PhpIndex-backed references
(Find Usages / Go to Definition), completion in `{=`/`{?`/`{c.`/`{var|`,
six structural inspections (*Unclosed block*, *Orphan branch*,
*Loop-scope depth mismatch*, *Redundant `@`*, *Duplicate {else}*,
*Undefined PHP symbol*), Unused-declaration awareness for template-only
references, and HTML/XML/JS/CSS noise filtering inside SkyTemplate ranges.
