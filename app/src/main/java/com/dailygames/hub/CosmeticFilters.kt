package com.dailygames.hub

import java.util.Locale

/**
 * Element-hiding ("cosmetic") filters: hides ad slots, banners and empty ad containers that network
 * blocking leaves behind.
 *
 * - Simple generic selectors (`.ad-banner`, `#ad_slot`) are looked up on demand: the page reports
 *   the class/id names it actually contains and only matching rules are sent back. (Injecting all
 *   ~14k generic rules into every page would cost noticeable parse time.)
 * - Complex generic selectors (main frame only) plus every rule specific to the page's site are sent
 *   once per page.
 * - Site rules may exclude subdomains (`example.com,~m.example.com##.ad`); a rule with only
 *   exclusions (`~example.com##.ad`) applies everywhere else.
 * - `#@#` exceptions remove rules for the domains they name; `$generichide` / `$elemhide` sites get
 *   no generic / no hiding rules at all.
 */
class CosmeticFilters(
    genericLines: Sequence<String>,
    specificLines: Sequence<String>,
    exceptionLines: Sequence<String>,
    noHideLines: Sequence<String> = emptySequence()
) {
    private class SiteRule(val selector: String, val excluded: List<String>)

    private val simpleGeneric = HashSet<String>()
    private val complexGeneric = ArrayList<String>()
    /** Exclusion-only rules: generic, but not on the listed domains. Simple ones are token-indexed. */
    private val simpleGenericExcept = HashMap<String, MutableList<List<String>>>()
    private val complexGenericExcept = ArrayList<SiteRule>()
    private val specificByDomain = HashMap<String, MutableList<SiteRule>>()
    private val exceptionsByDomain = HashMap<String, MutableSet<String>>()
    private val globalExceptions = HashSet<String>()
    private val noGeneric = HashSet<String>()
    private val noHide = HashSet<String>()

    init {
        for (raw in genericLines) {
            val sel = raw.trim()
            if (sel.isEmpty()) continue
            if (SIMPLE_RE.matches(sel)) simpleGeneric.add(sel) else complexGeneric.add(sel)
        }
        for (raw in specificLines) {
            val tab = raw.indexOf('\t')
            if (tab <= 0) continue
            val sel = raw.substring(tab + 1).trim()
            if (sel.isEmpty()) continue
            val doms = raw.substring(0, tab).split(',').map { it.trim().lowercase(Locale.US) }.filter { it.isNotEmpty() }
            val excluded = doms.filter { it.startsWith("~") }.map { it.substring(1) }
            val included = doms.filter { !it.startsWith("~") }
            if (included.isEmpty()) {
                if (excluded.isEmpty()) continue
                if (SIMPLE_RE.matches(sel)) simpleGenericExcept.getOrPut(sel) { ArrayList(1) }.add(excluded)
                else complexGenericExcept.add(SiteRule(sel, excluded))
                continue
            }
            val rule = SiteRule(sel, excluded)
            included.forEach { d -> specificByDomain.getOrPut(d) { ArrayList(2) }.add(rule) }
        }
        for (raw in exceptionLines) {
            val tab = raw.indexOf('\t')
            if (tab <= 0) continue
            val dom = raw.substring(0, tab).trim().lowercase(Locale.US)
            val sel = raw.substring(tab + 1).trim()
            if (dom == "*") globalExceptions.add(sel) else exceptionsByDomain.getOrPut(dom) { HashSet() }.add(sel)
        }
        for (raw in noHideLines) {
            val tab = raw.indexOf('\t')
            if (tab <= 0) continue
            val site = raw.substring(0, tab).trim().lowercase(Locale.US)
            if (raw.substring(tab + 1).trim() == "all") noHide.add(site) else noGeneric.add(site)
        }
    }

    val ruleCount: Int
        get() = simpleGeneric.size + complexGeneric.size + simpleGenericExcept.size + complexGenericExcept.size +
            specificByDomain.values.sumOf { it.size }

    private fun exceptionsFor(host: String): Set<String> {
        val out = HashSet<String>(globalExceptions)
        forEachDomainSuffix(host) { d -> exceptionsByDomain[d]?.let { out.addAll(it) } }
        return out
    }

    private fun anySuffixIn(host: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var hit = false
        forEachDomainSuffix(host) { if (it in set) hit = true }
        return hit
    }

    private fun excludedHere(host: String, excluded: List<String>): Boolean =
        excluded.any { host == it || host.endsWith(".$it") }

    /** CSS sent when a page starts: complex generic rules (if [includeComplexGeneric]) + rules for this site. */
    fun pageCss(host: String, includeComplexGeneric: Boolean = true): String {
        val h = host.lowercase(Locale.US)
        if (anySuffixIn(h, noHide)) return ""
        val generic = includeComplexGeneric && !anySuffixIn(h, noGeneric)
        val except = exceptionsFor(h)
        val sb = StringBuilder()
        if (generic) {
            for (sel in complexGeneric) if (sel !in except) appendRule(sb, sel)
            for (r in complexGenericExcept) if (r.selector !in except && !excludedHere(h, r.excluded)) appendRule(sb, r.selector)
        }
        val added = HashSet<String>()
        forEachDomainSuffix(h) { d ->
            specificByDomain[d]?.forEach { r ->
                if (r.selector !in except && !excludedHere(h, r.excluded) && added.add(r.selector)) appendRule(sb, r.selector)
            }
        }
        return sb.toString()
    }

    /** CSS for the `.class` / `#id` tokens a page reported (only those with a generic hiding rule). */
    fun cssForTokens(host: String, tokens: Collection<String>): String {
        if (tokens.isEmpty()) return ""
        val h = host.lowercase(Locale.US)
        if (anySuffixIn(h, noHide) || anySuffixIn(h, noGeneric)) return ""
        val except = exceptionsFor(h)
        val sb = StringBuilder()
        for (t in tokens) {
            if (t in except || !SIMPLE_RE.matches(t)) continue
            val hit = t in simpleGeneric ||
                simpleGenericExcept[t]?.any { excl -> !excludedHere(h, excl) } == true
            if (hit) appendRule(sb, t)
        }
        return sb.toString()
    }

    private fun appendRule(sb: StringBuilder, sel: String) {
        // One rule per selector: an invalid selector then only drops itself, not its neighbours.
        sb.append(sel).append("{display:none!important}\n")
    }

    companion object {
        private val SIMPLE_RE = Regex("""^[.#][A-Za-z_][A-Za-z0-9_-]*$""")

        fun forEachDomainSuffix(host: String, fn: (String) -> Unit) {
            var h = host.lowercase(Locale.US)
            while (true) {
                fn(h)
                val dot = h.indexOf('.')
                if (dot < 0) return
                h = h.substring(dot + 1)
                if (!h.contains('.')) {
                    fn(h) // also allow "example" TLD-less forms some lists use for entity rules
                    return
                }
            }
        }
    }
}
