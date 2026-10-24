package com.droiddeck.launcher.stores.amazon

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

internal object AmazonEngine {
    fun verify(directory: File): Boolean = runCatching {
        val manifest = File(directory, "engine.json")
        val library = File(directory, "libdroiddeckamazon.so")
        val root = directory.canonicalFile
        check(manifest.canonicalFile == File(root, manifest.name) && library.canonicalFile == File(root, library.name))
        check(manifest.length() in 1..131_072 && library.isFile)
        val data = JSONObject(manifest.readText())
        check(data.getInt("schema") == 1 && data.getInt("abi") == 1)
        check(data.getString("engine") == "amazon" && data.getString("target") == "aarch64-linux-android")
        val hash = MessageDigest.getInstance("SHA-256")
        library.inputStream().use { input ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                hash.update(block, 0, count)
            }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == data.getString("library_sha256"))
        true
    }.getOrDefault(false)
}
