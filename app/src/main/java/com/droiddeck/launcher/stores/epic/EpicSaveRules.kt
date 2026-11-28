package com.droiddeck.launcher.stores.epic

/**
 * Epic cloud-save include/exclude filters, matching the rules legendary uses against EGL's
 * CloudIncludeList/CloudExcludeList attributes:
 * - a pattern ending in `/` matches a directory prefix,
 * - otherwise a name that ends with the pattern matches (so "slot.sav" also hits "myslot.sav"),
 * - and fnmatch wildcards (`*`, `?`, `[seq]`) span path separators.
 * Every comparison is case-insensitive. Pure Kotlin so it tests without Android.
 */
class EpicSaveRules(include: List<String>, exclude: List<String>) {
    /** Patterns lowercased once; empty entries dropped. */
    val include: List<String> = include.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
    val exclude: List<String> = exclude.map { it.lowercase().trim() }.filter { it.isNotEmpty() }

    constructor(include: String, exclude: String) : this(parse(include), parse(exclude))

    /** True when a save file named [name] may be synced: not excluded, and included (or nothing is). */
    fun allows(name: String): Boolean {
        val n = name.lowercase()
        if (exclude.any { matches(it, n) }) return false
        if (include.isEmpty()) return true
        return include.any { matches(it, n) }
    }

    private fun matches(pattern: String, name: String): Boolean = when {
        pattern.endsWith("/") -> name.startsWith(pattern)
        name.endsWith(pattern) -> true
        else -> fnmatch(pattern, name)
    }

    companion object {
        /** Splits a comma-separated CloudIncludeList/CloudExcludeList attribute value. */
        fun parse(raw: String?): List<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /** Python-fnmatch style glob; `*` and `?` also match `/`, so "*.json" reaches "Saves/a.json". */
        fun fnmatch(pattern: String, name: String): Boolean =
            Regex(translate(pattern.lowercase())).matches(name.lowercase())

        private fun translate(pattern: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i++]) {
                    '*' -> out.append(".*")
                    '?' -> out.append('.')
                    '[' -> {
                        var j = i
                        if (j < pattern.length && pattern[j] == '!') j++
                        if (j < pattern.length && pattern[j] == ']') j++
                        while (j < pattern.length && pattern[j] != ']') j++
                        if (j >= pattern.length) out.append(Regex.escape("["))
                        else {
                            val inner = pattern.substring(i, j)
                            // fnmatch's negation is `[!...]`; a leading `^` is a class
                            // member, not a negation, and stays one in the regex.
                            val negated = inner.startsWith("!")
                            val members = (if (negated) inner.substring(1) else inner)
                                .replace("\\", "\\\\").replace("[", "\\[")
                            val body = if (!negated && members.startsWith("^")) "\\^${members.substring(1)}" else members
                            out.append('[').append(if (negated) "^" else "").append(body).append(']')
                            i = j + 1
                        }
                    }
                    else -> out.append(Regex.escape(c.toString()))
                }
            }
            return out.toString()
        }
    }
}
