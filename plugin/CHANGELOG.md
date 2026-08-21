# Changelog

All notable changes to the **SkyTemplate** PhpStorm plugin are recorded in
this file. The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to semantic versioning.

## [1.2.5] — 2026-08-21

### Fixed

- **TODO colours inside `{*…*}` comments** — a `TODO` / `FIXME` (or any
  user-defined *Settings → Editor → TODO* pattern) written inside a
  SkyTemplate comment in an `*.html` host file was painted grey like the
  rest of the comment: the plugin's own comment overlay covers the whole
  comment range, and on the wrapped `<!--{* … *}-->` shape (a real
  `XmlComment`, so the platform does produce a TODO highlight there) the
  host-noise filter dropped that highlight along with the HTML noise. The
  overlay is now split around every TODO match, and each match is painted
  with the attributes configured for its pattern. `*.sky` files were
  unaffected — SkyTemplate's comment tokens reach the platform's TODO pass
  through the parser definition.

  Not covered: multi-line TODO continuation lines, and the TODO tool
  window, which keeps listing only what the host file's indexer sees —
  plain `<!-- … -->` comments in HTML hosts, every comment in `*.sky`.

## [1.2.4] — 2026-08-01

Wrapped-comment fixes plus a full-codebase stability audit wave (five parallel
review passes over the scan, indent, inspection, reference, and VSCode-port
layers).

### Fixed

- **Wrapped comment split at an inner `}-->`** — the lazy wrapper regex
  (`<!--+\{ … \}--+>`) stopped at the FIRST `}-->` inside the comment body,
  so a `<!--{* … *}-->` comment containing `<!--{? …}-->` … `<!--{/}-->`
  was cut short and its closing `*}-->` tail was covered by no template
  range. The host HTML parser's mismatched-comment errors on that tail were
  therefore not suppressed. Wrapped comments are now detected from the
  lexer's `{* … *}` range outward (extend over a directly adjacent
  `<!--` / `-->` shell), so any number of inner `}-->` markers stay inside
  ONE comment range.
- **Wrapped directives inside a comment broke block pairing** — a wrapped
  `<!--{/}-->` (or `<!--{? …}-->`) written inside a `{* … *}` /
  `<!--{* … *}-->` comment body was still registered as a live template
  range, so the inner closer popped the enclosing block's frame and every
  `{:}` / `{/}` after the comment was reported as orphan / unmatched.
  Comment ranges now take precedence: wrapped-directive matches overlapping
  a comment are dropped from template ranges, folding, and pairing (plain
  `{…}` tags inside comments were already inert).
- **`a || b` no longer mistaken for a pipe filter** — the tag heuristic's
  `|` scan consumed only one character of `||`, so the second `|` marked
  JS bodies like `{ y = a || b; }` as SkyTemplate tags: genuine JS errors
  were suppressed, the block was recoloured, and Reformat protected the
  whole `<script>` verbatim.
- **Static-method parameter info (Ctrl+P)** — an off-by-one in the
  `Cls::method(` class-name extraction dropped the final character
  (`Abc` → `Ab`), so parameter info never resolved for parenthesised
  static calls; single-letter classes fell back to a same-named global
  function.
- **Pipe positional-after-named false error** — the compiler reorders pipe
  arguments (`array_merge($positional, $named)`), so
  `{var|str_pad=pad_type=1,10}` compiles fine; the "positional after
  named" rule now applies to the paren form only.
- **v1.2.4 regressions from the wrapped-comment rework** — comment-scoped
  block indent inside `<!--{* … *}-->` went dead (body bounds assumed a
  `{*` start), and the argument inspections validated example calls inside
  wrapped comments (comment-skip only recognised `{*`-leading ranges).
- **A comment containing a PHP fragment is still a comment** — policy
  decision: comments take precedence over PHP regions. A `{* … <?=$x?> … *}`
  comment was discarded whole (its tags coming back alive as spurious
  "Unclosed block"); now only a comment whose `{*` itself starts inside a
  PHP region is rejected.
- **CSS Nesting bodies no longer read as template tags** — policy decision:
  for the CSS-colliding prefixes `&` / `:` / `+`, a body containing tabs,
  newlines, or nested braces is CSS, and `&` must match the compiler's
  refer-tag shape (`{&blockName}`) — `& .title { … }` / `:hover { … }` /
  `+ .sibling { … }` stay CSS.
- **Pipe filters honour the formatter class everywhere** — v1.2.3 applied
  formatter-method-first dispatch to resolve/navigation only; Ctrl+P
  parameter info, inlay parameter hints, and the argument-count /
  named-argument inspections still consulted the global function, producing
  wrong signatures and false arity warnings when names collided.
- **`{&block}` (refer) no longer pushes a block frame** — the scope
  analyzer treated refer as an if-opener, so a following `{/}` popped the
  wrong frame: loop-scope warnings went missing and duplicate-`{:}`
  detection was silenced.
- **Member access after `.` / `->` / `::` is not a variable reference** —
  `{row._value}` outside a loop warned RESERVED_OUTSIDE_LOOP although the
  compiler compiles it as plain array access; trailing identifiers are no
  longer collected as standalone variable refs (also resolves the
  `{user.roles@2}` redundant-`@` false positive).
- **Wrapped `<!--{…}-->` directives are first-class for indent** — the
  indent-layer classifiers (Reformat, Enter, `}` alignment, block actions,
  duplicate suppression) read the wrapper shell as body text, so a wrapped
  closer never popped its frame (whole-file over-indent after
  `{loop}…<!--{/}-->`) and wrapped-only files got no indent support. All
  classifiers now share folding's `innerBraceBounds`.

- **Multi-range Reformat keeps every `<script>`/`<style>` snapshot** —
  "Reformat only changed lines" style invocations split one reformat into
  several ranges; each range used to discard the previous ranges'
  protection snapshots, mangling earlier SkyTemplate-bearing script bodies.
  Snapshots now merge.
- **Move Statement out of a block re-indents the moved line** — the
  lift-only re-indent policy left a line moved *out* of `{loop}`/`{?}`
  at its old deeper indent; lines the mover relocates are now set to
  their exact expected indent (manual indent elsewhere stays respected).
- **`.sky` multi-tree annotator double-run guard** — the HTML-language
  annotators also fired on the HTML data tree of `.sky` files, duplicating
  analysis (and potentially markers) already produced by the
  SkyTemplate-language inspections.
- **Pipe-argument tooling matches the compiler's argument model** — Ctrl+P
  highlighting now accounts for the auto-prepended `##` input and quoted
  commas (and works at all — the call-start scanner bailed on `{`, so pipe
  mode was unreachable); inlay hints and Ctrl+P reflect the compiler's
  positional-before-named reordering; empty pipe arguments (`{v|fn=a,,b}`)
  count as real `''` positionals; static pipe filters (`{var|Cls::m}`) are
  validated; nested calls (`{=outer(inner(...))}`) are validated; filter
  names the compiler ignores (`{var|_foo}`) no longer produce references
  or undefined-symbol warnings.
- **`{ loop x}` is not a keyword** — the lexer coloured a
  leading-whitespace keyword the compiler would treat as a variable.
- **Scope analysis edge cases** — `{: // comment}` counts as a bare else
  (duplicate-`{:}` detection restored); `{\…}` escape-literal bodies are
  no longer scope-checked; `{?:expr}` participates in branch-aware
  duplicate suppression; wrapped directives inside PHP string literals are
  ignored.
- **Completion / Find Usages parity** — `Cls::` member completion uses the
  same FQN candidate chain as reference resolution (useClass aliases,
  global fallback); `{?a || b}` no longer triggers pipe-style completion;
  `{@…}`/`{%…}` loop tags offer expression completion; Find Usages matches
  PHP's case-insensitive function names and reports multiple usages within
  one element.
- **Missing loop name is reported** — `{loop}` / `{foreach}` with no
  argument (a compile-time fatal) now gets an ERROR annotation.
- Settings polish: the formatter-class field no longer leaves the Apply
  button armed after trimming; duplicate `projectService` registration
  removed; the `elif` live template inserts the compiler-supported
  `{:cond}` form instead of unsupported `{elseif}`.

### Changed

- **Formatter class lookup is compiler-strict** — the configured formatter
  class now resolves as an absolute FQN only (the compiler emits
  `use <formatter> as _F` from the root namespace). The old
  namespace-prefixed and simple-name fallbacks could report
  formatter dispatch for a class the compiled template never calls.

### Performance

- **One scan per analysis per highlighting pass** — the inspections,
  annotators, folding, highlight-usages, and parameter-info paths each
  re-scanned the whole file independently (7–12 full scans per pass, some
  with PHP-index lookups). Scope analysis, block pairing, argument
  analysis, and template/comment ranges are now computed once per document
  state and shared (`SkyTemplateRangeCache` lazies + file-level cached
  values), with cache-invalidation regression tests.

## [1.2.3] — 2026-07-17

Formatter-class method matching for pipe filters (`{var|func}`).

### Added

- **Formatter class setting** (*Settings → Tools → SkyTemplate → Formatter
  class*) — mirrors the SkyTemplate `formatter` compiler config. The compiler
  dispatches pipe filters formatter-method-first
  (`method_exists($this->formatter, $func)` → `_F::func(...)`), falling back
  to a plain function call; the plugin previously resolved every pipe filter
  name as a global function only.
- **Pipe filter resolution honors the formatter class** — `{price|money}`
  now resolves (Go to Definition / Find Usages) to `Formatter::money()` when
  the configured formatter class declares or inherits that method, and only
  falls back to the global function otherwise. When a formatter method and a
  global function share a name, the method wins — matching what the compiled
  template actually calls. Expression-context calls (`{=func()}`) are
  unaffected; the compiler never consults the formatter for those.
- **Pipe named-arg parameters resolve against formatter methods** —
  `{price|money=digits=2}` resolves `digits` to the formatter method's
  parameter when the filter dispatches to the formatter.
- **Completion offers formatter methods in pipe position** — typing after
  `{var|` lists the formatter class's methods alongside global functions
  (parenless insertion, pipe form).

## [1.2.2] — 2026-07-07

Bug-fix and performance wave from the full-codebase audit recorded in
`docs/BACKLOG.md` (items P-BUG-01…12, P-IMP-02/03/04/06/08). No new features.

### Fixed

- **Master switch honored on Enter and typed `}`** — the Enter handler and the
  closing-tag aligner ran even with the plugin disabled in *Settings → Tools →
  SkyTemplate*, and on file extensions outside the whitelist. Both entry
  points are now gated by the same file filter as every other feature, so
  disabling the plugin really stops auto-`{/}` insertion and indent rewriting.
- **`{?:expr}` (elvis) indent classification** — the indent paths (Enter,
  Reformat, `}` alignment, block actions, duplicate-suppression ranges)
  treated the elvis tag as a non-block tag while folding and the unclosed-block
  inspection treat it as a block opener. A `{?:…}…{/}` pair therefore popped
  an *outer* frame during re-indent and shifted the rest of the file by one
  level. All classifiers now agree that `{?:expr}` opens a block.
- **`{/foo}` no longer pops a block on indent paths** — trailing text after
  `/` disqualifies a closer for folding and inspections; the four indent
  classifiers used a looser `first == '/'` rule and popped anyway, producing
  indent drift that contradicted the inspection's "Unclosed block" report.
  They now share folding's stricter closer pattern (`{/}` plus an optional
  trailing `// comment` only).
- **Reformat vs Enter depth mismatch on unbalanced closers** — the reformat
  line walker popped the nearest opener (plain LIFO) while Enter smart-split,
  the `}` aligner, and folding unwind by indent. On a "forgot to close the
  inner block" file the two paths settled on different depths. The line
  walker now unwinds indent-deeper unclosed openers first, matching the
  other three.
- **One-level over-indent for `}` lines in embedded script/style** — inside a
  SkyTemplate-bearing `<script>` / `<style>`, a line whose first character is
  `}` counted its own closing brace into the embedded brace depth, so Enter
  and smart-indent pushed it one step too deep.
- **Trailing content after an opener** — `{loop x}<caret>text` + Enter left
  `text` after the auto-inserted `{/}`; it now moves onto the indented body
  line.
- **Stale range after verbatim script/style restore** — the post-format pass
  captured the document length *after* the restore pass had already shrunk
  the document, so a no-op re-indent could hand the platform a range whose
  end lay beyond the document. The length is captured up front and the
  returned range is clamped.
- **Join gluing words together** — joining a block collapsed the line break
  between two words to nothing (`hello` + `world` → `helloworld`). A newline
  run bordered by word characters on both sides now collapses to a single
  space; tag-adjacent joins stay tight as before.
- **Tab width in indent comparisons** — indent-width comparisons counted a
  tab as width 1, mis-judging "user indented deeper than baseline" in
  tab-indented projects. A tab now weighs one indent step.
- **Possible NPE in Move Statement Down** on the file's last physical line,
  where the platform provides no swap target.

### Changed

- **Per-snapshot range caching** — template ranges, comment ranges, indent
  ranges, protected embedded ranges, and block-pairing analysis are computed
  once per document snapshot (keyed by text identity + length, invalidated on
  self-edits) instead of 4–6 full-file rescans per Enter / typed char / Tab.
  Purely internal; no behavior change. The `embeddedBraceDepth` scan also
  dropped from O(region × ranges) to a two-pointer O(region + ranges), and
  folding now reads the same char sequence as the structural annotator.

## [1.2.1] — 2026-07-03

Rolls up the work on top of 1.1.0: block-tag indent / Reformat groundwork,
embedded `<script>` / `<style>` Reformat and Enter handling, branch-aware
duplicate suppression, and the comment-handling and fixes below.

### Compatibility

- **No IDE upper bound** — the `until-build` cap (previously `261.*`) was
  removed, so the plugin stays installable on PhpStorm 2026.2 (build 262) and
  later branches. The compile / test baseline stays at 2024.2.

### Changed

- **Relative indentation** — Enter, Paste, Reformat Code, and the platform's
  smart-indent / Tab paths now compute a line's indent **relative to the
  nearest enclosing HTML or template opener's actual indent** (parent indent +
  one step), instead of a depth re-derived from the top of the file. A block
  nested under an unindented ancestor chain (`<html>` / `<body>` / `<div>` all
  at column 0) no longer over-indents; each structural level — HTML or
  template — adds exactly one step. A new `lineIndentProvider` extends the same
  rule to the platform's smart-indent query, and the Enter opener-lift now
  recognises a template parent (`{loop}`, `{?…}`, …) as an anchor on par with
  an HTML parent.

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
