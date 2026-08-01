package com.novaframework.templatelang.reference

import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.novaframework.templatelang.settings.TemplateLangSettings

/**
 * Resolution of the configured SkyTemplate formatter class and its methods.
 *
 * Mirrors the compiler's pipe-filter dispatch in `parseFunction()`:
 *
 *     if (method_exists($this->formatter, $func)) { _F::$func(...) }
 *     else                                        { $func(...) }
 *
 * i.e. when the `formatter` config names a class that has a method matching
 * the pipe filter name, the compiled code calls the formatter method and the
 * global function is never invoked. The reference / completion layers use this
 * helper to apply the same formatter-first order.
 *
 * The `formatter` config value is a class-string handed to `method_exists`
 * via a plain `use {$formatter} as _F;` (SkyTemplateCompiler.php:229) — PHP's
 * `use` always resolves from the root namespace regardless of the compiled
 * file's own `namespace` declaration, so unlike template-side identifiers
 * the configured-namespace prefix is never a valid candidate here, and no
 * simple-name fallback across other namespaces applies either: `raw` already
 * denotes an absolute FQN once prefixed with `\` (PHP normalises `use Foo` /
 * `use \Foo` identically), so any class living under a different namespace
 * than the one literally written is a class `method_exists()` would never
 * see — matching it anyway would misrepresent which code the compiler
 * actually dispatches to. It is also not subject to `useClass` alias
 * expansion.
 */
internal object SkyTemplateFormatterLookup {

    /** Classes the `formatterClass` setting resolves to. Empty when unset / unresolvable. */
    fun formatterClasses(phpIndex: PhpIndex, settings: TemplateLangSettings): Collection<PhpClass> {
        val raw = settings.formatterClass.trimEnd('\\')
        if (raw.isEmpty()) return emptyList()
        if (raw.startsWith("\\")) return phpIndex.getClassesByFQN(raw)
        return phpIndex.getClassesByFQN("\\$raw")
    }

    /**
     * Formatter methods matching a pipe-filter name, inherited members
     * included (same visibility as `method_exists`). Qualified names never
     * match — a method name cannot contain `\` — and the compiler's filter
     * pattern requires a leading letter, so such names skip the lookup.
     */
    fun findMethods(
        phpIndex: PhpIndex,
        settings: TemplateLangSettings,
        filterName: String,
    ): List<Method> {
        if (filterName.isEmpty() || !filterName[0].isLetter() || '\\' in filterName) return emptyList()
        return formatterClasses(phpIndex, settings).mapNotNull { it.findMethodByName(filterName) }
    }
}
