# Changelog

All notable changes to the **SkyTemplate** PhpStorm plugin are recorded in
this file. The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to semantic versioning.

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
