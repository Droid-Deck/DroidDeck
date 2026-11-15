package com.droiddeck.launcher.frontend

object SoundtrackTracks {
    enum class Sort { DEFAULT, TITLE_ASC, TITLE_DESC, LONGEST, SHORTEST }

    fun <T> sort(items: List<T>, sort: Sort, title: (T) -> String, duration: (T) -> Long): List<T> = when (sort) {
        Sort.DEFAULT -> items
        Sort.TITLE_ASC -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, title))
        Sort.TITLE_DESC -> items.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER, title))
        Sort.LONGEST -> items.sortedByDescending(duration)
        Sort.SHORTEST -> items.sortedBy(duration)
    }

    fun matches(query: String, vararg fields: String): Boolean {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = fields.map { it.lowercase() }
        return words.all { word -> haystack.any { it.contains(word) } }
    }

    fun rank(query: String, title: String): Int {
        val q = query.trim().lowercase()
        val t = title.lowercase()
        return when {
            t == q -> 0
            t.startsWith(q) -> 1
            else -> 2
        }
    }
}
