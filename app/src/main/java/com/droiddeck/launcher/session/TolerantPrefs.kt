package com.droiddeck.launcher.session

import android.content.Context
import android.content.SharedPreferences

internal class TolerantPrefs(private val base: SharedPreferences) : SharedPreferences by base {
    override fun getString(key: String?, defValue: String?): String? =
        read(key, defValue, { base.getString(key, defValue) }) { it }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        try { base.getStringSet(key, defValues) } catch (_: ClassCastException) { defValues }

    override fun getInt(key: String?, defValue: Int): Int =
        read(key, defValue, { base.getInt(key, defValue) }) { it.toIntOrNull() }

    override fun getLong(key: String?, defValue: Long): Long =
        read(key, defValue, { base.getLong(key, defValue) }) { it.toLongOrNull() }

    override fun getFloat(key: String?, defValue: Float): Float =
        read(key, defValue, { base.getFloat(key, defValue) }) { it.toFloatOrNull() }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        read(key, defValue, { base.getBoolean(key, defValue) }) { it.toBooleanStrictOrNull() }

    private inline fun <T> read(key: String?, defValue: T, typed: () -> T, parse: (String) -> T?): T =
        try {
            typed()
        } catch (_: ClassCastException) {
            base.all[key]?.toString()?.let(parse) ?: defValue
        }

    companion object {
        fun session(context: Context): SharedPreferences =
            TolerantPrefs(context.getSharedPreferences("session", Context.MODE_PRIVATE))
    }
}
