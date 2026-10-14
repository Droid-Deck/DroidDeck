package com.droiddeck.launcher.stores

import org.json.JSONObject

object CloudPlan {
    data class Entry(val localMd5: String, val cloudMd5: String?, val cloudModified: Long)

    class Local(val md5: String)
    class Remote(val md5: String?, val modified: Long)

    class Plan(val transfer: List<String>, val conflicts: List<String>, val same: List<String>, val refused: String? = null)

    private fun cloudChanged(r: Remote, b: Entry?): Boolean {
        if (b == null) return true
        if (r.md5 != null && b.cloudMd5 != null) return !r.md5.equals(b.cloudMd5, ignoreCase = true)
        return r.modified > 0 && b.cloudModified > 0 && kotlin.math.abs(r.modified - b.cloudModified) > 2000
    }

    private fun localChanged(l: Local, b: Entry?): Boolean = b == null || !l.md5.equals(b.localMd5, ignoreCase = true)

    private fun same(l: Local, r: Remote): Boolean = r.md5 != null && r.md5.equals(l.md5, ignoreCase = true)

    fun down(local: Map<String, Local>, remote: Map<String, Remote>, baseline: Map<String, Entry>?): Plan {
        val transfer = ArrayList<String>(); val conflicts = ArrayList<String>(); val same = ArrayList<String>()
        for ((name, r) in remote) {
            val l = local[name]
            val b = baseline?.get(name)
            when {
                l == null -> transfer += name
                same(l, r) -> same += name
                baseline == null -> transfer += name
                cloudChanged(r, b) && localChanged(l, b) -> conflicts += name
                cloudChanged(r, b) -> transfer += name
            }
        }
        return Plan(transfer, conflicts, same)
    }

    fun up(local: Map<String, Local>, remote: Map<String, Remote>, baseline: Map<String, Entry>?): Plan {
        if (baseline == null) return Plan(emptyList(), emptyList(), emptyList(), refused = "no-baseline")
        val transfer = ArrayList<String>(); val conflicts = ArrayList<String>(); val same = ArrayList<String>()
        for ((name, l) in local) {
            val r = remote[name]
            val b = baseline[name]
            when {
                r == null -> transfer += name
                same(l, r) -> same += name
                localChanged(l, b) && cloudChanged(r, b) -> conflicts += name
                localChanged(l, b) -> transfer += name
            }
        }
        return Plan(transfer, conflicts, same)
    }

    fun toJson(baseline: Map<String, Entry>): String = JSONObject().apply {
        for ((k, e) in baseline) put(k, JSONObject().put("l", e.localMd5).put("c", e.cloudMd5 ?: "").put("t", e.cloudModified))
    }.toString()

    fun fromJson(text: String?): Map<String, Entry>? {
        if (text == null) return null
        return runCatching {
            val o = JSONObject(text)
            o.keys().asSequence().associateWith { k ->
                val e = o.getJSONObject(k)
                Entry(e.optString("l"), e.optString("c").ifEmpty { null }, e.optLong("t", -1L))
            }
        }.getOrNull()
    }
}
