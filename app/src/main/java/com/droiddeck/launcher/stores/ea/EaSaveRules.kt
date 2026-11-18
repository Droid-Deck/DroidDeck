package com.droiddeck.launcher.stores.ea

internal class EaSaveRules(val includes: List<String>, val excludes: List<String>) {

    fun base(): String? {
        val dirs = includes.map { pattern ->
            val wild = pattern.indexOfFirst { it == '*' || it == '?' }
            val fixed = if (wild < 0) pattern else pattern.substring(0, wild)
            if (fixed.endsWith('/')) fixed else fixed.substringBeforeLast('/', "") + "/"
        }
        val parts = dirs.map { it.trimEnd('/').split('/') }
        if (parts.isEmpty()) return null
        val common = ArrayList<String>()
        for (i in parts.minOf { it.size }.let { 0 until it }) {
            val seg = parts[0][i]
            if (parts.all { it[i].equals(seg, ignoreCase = true) }) common += seg else break
        }
        if (common.isEmpty() || !VARIABLE.matches(common[0])) return null
        return common.joinToString("/") + "/"
    }

    fun matches(path: String): Boolean {
        val p = normalize(path)
        return includes.any { hit(it, p) } && excludes.none { hit(it, p) }
    }

    private fun hit(pattern: String, path: String): Boolean = when {
        pattern.endsWith('/') -> path.startsWith(pattern, ignoreCase = true)
        pattern.any { it == '*' || it == '?' } -> glob(pattern).matches(path)
        else -> path.equals(pattern, ignoreCase = true)
    }

    private fun glob(pattern: String): Regex = Regex(
        pattern.split('*').joinToString("[^/]*") { part -> part.split('?').joinToString("[^/]") { Regex.escape(it) } },
        RegexOption.IGNORE_CASE,
    )

    companion object {
        private val VARIABLE = Regex("%[A-Za-z]+%")
        private val RULE = Regex("<(include|exclude)\\b[^>]*>([^<]*)</\\1>", RegexOption.IGNORE_CASE)
        private val TOKENS = mapOf(
            "documents" to "%DOCUMENTS%", "savedgames" to "%SAVED_GAMES%", "appdata" to "%APPDATA%",
            "localappdata" to "%LOCALAPPDATA%", "programdata" to "%PROGRAMDATA%", "userprofile" to "%USERPROFILE%",
        )

        fun normalize(path: String) = path.trim().replace('\\', '/')

        fun parse(xml: String): EaSaveRules? {
            val rules = RULE.findAll(xml).map { it.groupValues[1].lowercase() to normalize(unescape(it.groupValues[2])) }
                .filter { it.second.isNotEmpty() }.toList()
            val includes = rules.filter { it.first == "include" }.map { it.second }
            if (includes.isEmpty()) return null
            return EaSaveRules(includes, rules.filter { it.first == "exclude" }.map { it.second })
        }

        fun template(base: String): String? {
            val variable = VARIABLE.find(base)?.takeIf { it.range.first == 0 } ?: return null
            val token = TOKENS[variable.value.trim('%').lowercase()] ?: return null
            return token + base.substring(variable.value.length)
        }

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}
