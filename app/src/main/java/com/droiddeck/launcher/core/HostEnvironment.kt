package com.droiddeck.launcher.core

class HostEnvironment {
    private val entries = LinkedHashMap<String, String>()

    operator fun set(name: String, value: String) {
        entries[name] = value
    }

    fun asArray(): Array<String> = entries.map { (name, value) -> "$name=$value" }.toTypedArray()
}
