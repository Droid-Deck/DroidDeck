package com.droiddeck.launcher.stores.ea

internal class EaSaveRules(private val rules: List<Pair<Boolean, String>>) {
    val includes get() = rules.filter { it.first }.map { it.second }
    val excludes get() = rules.filterNot { it.first }.map { it.second }

    fun base(): String? {
        val dirs = includes.filterNot(::bare).map { pattern ->
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
        val inside = base()?.let { p.startsWith(it, ignoreCase = true) } ?: false
        return rules.firstOrNull { (_, pattern) -> if (bare(pattern)) inside && hit(pattern, p.substringAfterLast('/')) else hit(pattern, p) }?.first ?: false
    }

    private fun bare(pattern: String) = !pattern.startsWith('%') && '/' !in pattern

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
        private val RULE = Regex("<(include|exclude)\\b([^>]*)>([^<]*)</\\1>", RegexOption.IGNORE_CASE)
        private val ORDER = Regex("\\border\\s*=\\s*[\"']?(\\d+)")
        private val TOKENS = mapOf(
            "documents" to "%DOCUMENTS%", "savedgames" to "%SAVED_GAMES%", "appdata" to "%APPDATA%",
            "localappdata" to "%LOCALAPPDATA%", "programdata" to "%PROGRAMDATA%", "userprofile" to "%USERPROFILE%",
            "locallowappdata" to "%APPLICATION_DATA_LOCAL_LOW%", "installdir" to "%INSTALL%",
        )

        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|quot|apos|amp);")

        fun normalize(path: String) = path.replace('\\', '/')

        fun parse(xml: String): EaSaveRules? {
            val rules = RULE.findAll(COMMENT.replace(xml, "")).mapIndexed { index, rule ->
                val order = ORDER.find(rule.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() ?: index
                Triple(order, rule.groupValues[1].equals("include", true), normalize(unescape(rule.groupValues[3]).trim()))
            }.filter { it.third.isNotEmpty() }.sortedBy { it.first }.map { it.second to it.third }.toList()
            if (rules.none { it.first }) return null
            return EaSaveRules(rules)
        }

        fun template(base: String): String? {
            val variable = VARIABLE.find(base)?.takeIf { it.range.first == 0 } ?: return null
            val token = TOKENS[variable.value.trim('%').lowercase()] ?: return null
            return token + base.substring(variable.value.length)
        }

        fun unescape(s: String): String = ENTITY.replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "amp" -> "&"
                else -> runCatching { String(Character.toChars(if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt())) }.getOrDefault(m.value)
            }
        }
    }
}
