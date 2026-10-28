package com.droiddeck.launcher.stores.itch

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object ItchStaging {
    const val DIR = ".droiddeck-update"
    const val RECORD = ".droiddeck-store.json"
    private const val JOURNAL = "itch-commit.json"
    private const val INVENTORY = "itch-inventory.json"
    private const val BACKUPS = "itch-backups"
    private const val DIRECTORY = "<directory>"

    fun inventory(root: File): Map<String, String> = linkedMapOf<String, String>().also { out ->
        fun visit(dir: File, prefix: String) {
            for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (Files.isSymbolicLink(child.toPath())) continue
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (prefix.isEmpty() && (child.name == RECORD || child.name == DIR || child.name == ".droiddeck-maintenance")) continue
                if (child.isDirectory) {
                    val children = child.listFiles()
                    if (children != null && children.isEmpty()) out["$path/"] = DIRECTORY
                    else visit(child, path)
                } else if (child.isFile) out[path] = sha256(child)
            }
        }
        visit(root, "")
    }

    private fun protectedUserData(relative: String): Boolean = relative.split('/').any {
        it.lowercase() in setOf("save", "saves", "userdata", "user data", "profiles")
    } || relative.substringAfterLast('/').substringAfterLast('.', "").lowercase() in setOf("sav", "save")

    fun readInventory(root: File): Map<String, String> {
        val metadata = File(root, ".droiddeck-maintenance")
        require(!Files.isSymbolicLink(metadata.toPath())) { "Maintenance metadata directory is a symbolic link" }
        val file = File(metadata, INVENTORY)
        require(!Files.isSymbolicLink(file.toPath())) { "itch.io inventory is a symbolic link" }
        if (!file.exists()) return emptyMap()
        require(file.isFile) { "itch.io inventory is not a regular file" }
        val value = runCatching { JSONObject(file.readText()) }
            .getOrElse { throw IllegalStateException("itch.io inventory is corrupt", it) }
        return linkedMapOf<String, String>().apply {
            value.keys().forEach { path ->
                val hash = value.optString(path)
                require(validInventoryEntry(root.canonicalFile, path, hash)) { "itch.io inventory entry is invalid" }
                put(path, hash)
            }
        }
    }

    fun writeInventory(root: File, inventory: Map<String, String>) {
        val metadata = File(root, ".droiddeck-maintenance")
        require(!Files.isSymbolicLink(metadata.toPath())) { "Maintenance metadata directory is a symbolic link" }
        metadata.mkdirs()
        val file = File(metadata, INVENTORY)
        require(!Files.isSymbolicLink(file.toPath())) { "itch.io inventory is a symbolic link" }
        writeJournal(file, JSONObject().apply { inventory.forEach { (path, hash) -> put(path, hash) } })
    }

    fun commit(from: File, to: File, previous: Map<String, String>, afterEntry: ((Int) -> Unit)? = null): Map<String, String> {
        require(!Files.isSymbolicLink(to.toPath())) { "The itch.io install folder is a symbolic link" }
        val metadata = File(to, ".droiddeck-maintenance").apply { mkdirs() }
        require(!Files.isSymbolicLink(metadata.toPath())) { "Maintenance metadata directory is a symbolic link" }
        val backups = File(metadata, BACKUPS)
        val journal = File(metadata, JOURNAL)
        val existing = readJournal(journal)
        val discovered = inventory(from)
        val existingNext = existing?.getJSONObject("next")
        val next = existing?.optJSONObject("next")?.let { saved ->
            linkedMapOf<String, String>().apply {
                saved.keys().forEach { relative ->
                    val hash = saved.optString(relative)
                    require(validInventoryEntry(to.canonicalFile, relative, hash)) { "The interrupted itch.io inventory is invalid" }
                    put(relative, hash)
                }
                for ((relative, hash) in discovered) {
                    val becameEmptyAfterMove = hash == DIRECTORY && relative.endsWith('/') &&
                        keys.any { it != relative && it.startsWith(relative) }
                    require(this[relative] == hash || becameEmptyAfterMove) {
                        "The staged itch.io archive does not match the interrupted target"
                    }
                }
            }
        } ?: discovered
        val files = JSONObject().apply { next.forEach { (path, hash) -> put(path, hash) } }
        val obsolete = previous.keys.filter { it !in next }.sorted()
        val root = to.canonicalFile
        val stage = from.canonicalFile
        val paths = obsolete.sortedWith(compareByDescending<String> { it.count { ch -> ch == '/' } }.thenBy { it }) +
            next.keys.sortedWith(compareBy<String> { it.count { ch -> ch == '/' } }.thenBy { it })
        val state = existing ?: JSONObject().put("schema", 1).put("next", files).put("obsolete", JSONObject().apply { obsolete.forEach { put(it, previous[it]) } }).put("done", JSONObject())
        check(state.optInt("schema") == 1 && existingNext != null && state.optJSONObject("done") != null && state.optJSONObject("obsolete") != null || existing == null) {
            "An interrupted itch.io replacement has an unsupported journal"
        }
        writeJournal(journal, state)
        require(!Files.isSymbolicLink(backups.toPath())) { "The itch.io backup directory is a symbolic link" }
        backups.mkdirs()
        require(!Files.isSymbolicLink(backups.toPath())) { "The itch.io backup directory is a symbolic link" }
        var count = 0
        for (relative in paths) {
            val directory = next[relative] == DIRECTORY
            val path = relative.removeSuffix("/")
            val dest = safe(root, path) ?: throw IllegalStateException("Unsafe itch.io archive path: $relative")
            val staged = safe(stage, path)
            val expected = next[relative]
            val oldHash = previous[relative]
            val backup = safe(backups, path)!!
            val done = state.optJSONObject("done") ?: JSONObject().also { state.put("done", it) }
            val wasJournaledDone = done.optBoolean(relative)
            if (wasJournaledDone) {
                val expectedHash = expected.takeUnless { it == DIRECTORY }
                if (expectedHash != null && dest.isFile && sha256(dest) == expectedHash) { count++; continue }
                if (expected == DIRECTORY && dest.isDirectory && !Files.isSymbolicLink(dest.toPath())) { count++; continue }
                if (expectedHash == null && expected != DIRECTORY && (!dest.isFile || oldHash == null || sha256(dest) != oldHash)) { count++; continue }
                done.put(relative, false)
                writeJournal(journal, state)
            }
            if (expected == null) {
                if (oldHash == DIRECTORY) {
                    if (dest.isDirectory && !Files.isSymbolicLink(dest.toPath()) && dest.listFiles().isNullOrEmpty()) dest.delete()
                } else if (dest.isFile && oldHash != null && sha256(dest) == oldHash) {
                    backup.parentFile?.mkdirs()
                    move(dest, backup)
                }
                done.put(relative, true)
                writeJournal(journal, state)
            } else if (directory) {
                require(!Files.isSymbolicLink(dest.toPath())) { "itch.io archive directory collides with a symbolic link: $path" }
                if (dest.exists() && !dest.isDirectory) {
                    val oldFileHash = previous[path]
                    require(oldFileHash != null && sha256(dest) == oldFileHash) { "itch.io archive directory collides with an unmanaged file: $path" }
                    backup.parentFile?.mkdirs()
                    move(dest, backup)
                }
                check(dest.isDirectory || dest.mkdirs()) { "Could not create itch.io archive directory: $path" }
                done.put(relative, true)
                writeJournal(journal, state)
            } else {
                if (protectedUserData(path) && dest.exists()) {
                    require(oldHash != null && dest.isFile && sha256(dest) == oldHash) {
                        "itch.io archive collides with untracked or modified user data: $path"
                    }
                }
                val currentHash = if (dest.isFile) sha256(dest) else null
                if (currentHash == expected) {
                    staged?.delete()
                } else {
                    val stagedFile = staged?.takeIf { it.isFile } ?: throw IllegalStateException("Staged itch.io file is missing: $relative")
                    val emptyManagedDirectory = dest.isDirectory && !Files.isSymbolicLink(dest.toPath()) && dest.listFiles().isNullOrEmpty()
                    require(!dest.exists() || oldHash != null || emptyManagedDirectory || wasJournaledDone || backup.exists()) { "itch.io update collides with an unmanaged file: $relative" }
                    if (dest.isDirectory && !Files.isSymbolicLink(dest.toPath()) && dest.listFiles().isNullOrEmpty()) dest.delete()
                    require(!dest.exists() || dest.isFile) { "itch.io update collides with an unmanaged directory: $relative" }
                    require(!Files.isSymbolicLink(dest.toPath())) { "itch.io update collides with a symbolic link: $relative" }
                    if (dest.exists() && !backup.exists()) {
                        backup.parentFile?.mkdirs()
                        move(dest, backup)
                    }
                    dest.parentFile?.mkdirs()
                    move(stagedFile, dest)
                    check(sha256(dest) == expected) { "itch.io replacement failed verification: $relative" }
                }
                done.put(relative, true)
                writeJournal(journal, state)
            }
            count++
            afterEntry?.invoke(count)
        }
        StoreInstallsSafeDelete.delete(from)
        StoreInstallsSafeDelete.delete(backups)
        journal.delete()
        return next
    }

    fun recover(to: File): Boolean {
        val file = File(to, ".droiddeck-maintenance/$JOURNAL")
        val state = readJournal(file) ?: return false
        require(state.optInt("schema") == 1) { "The interrupted itch.io replacement has an unsupported journal" }
        val next = state.optJSONObject("next") ?: throw IllegalStateException("The interrupted itch.io inventory is missing")
        val done = state.optJSONObject("done") ?: throw IllegalStateException("The interrupted itch.io progress is missing")
        val obsolete = state.optJSONObject("obsolete") ?: throw IllegalStateException("The interrupted itch.io cleanup list is missing")
        for (relative in next.keys()) {
            require(validInventoryEntry(to.canonicalFile, relative, next.optString(relative))) {
                "The interrupted itch.io inventory is invalid"
            }
        }
        for (relative in done.keys()) {
            require(safe(to.canonicalFile, relative.removeSuffix("/")) != null && done.get(relative) is Boolean) {
                "The interrupted itch.io progress is invalid"
            }
        }
        for (relative in obsolete.keys()) {
            require(validInventoryEntry(to.canonicalFile, relative, obsolete.optString(relative))) {
                "The interrupted itch.io cleanup list is invalid"
            }
        }
        return true
    }

    fun merge(from: File, to: File, top: Boolean = true) {
        for (child in from.listFiles().orEmpty()) {
            if (top && child.name == RECORD) continue
            val dest = File(to, child.name)
            if (child.isDirectory && !Files.isSymbolicLink(child.toPath()) && dest.isDirectory && !Files.isSymbolicLink(dest.toPath())) {
                merge(child, dest, false)
            } else {
                if (Files.isSymbolicLink(dest.toPath())) dest.delete()
                require(!dest.exists() || !dest.isDirectory) { "itch.io archive path collides with a directory: ${child.name}" }
                dest.parentFile?.mkdirs()
                move(child, dest)
            }
        }
    }

    fun delete(file: File) = StoreInstallsSafeDelete.delete(file)

    private fun safe(root: File, relative: String): File? {
        if (relative.isBlank() || relative.startsWith('/') || relative.contains('\\') || relative.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        val file = File(root, relative)
        var current = root
        for (segment in relative.split('/')) {
            current = File(current, segment)
            if (Files.isSymbolicLink(current.toPath())) return null
        }
        val canonical = file.canonicalFile
        return canonical.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun validInventoryEntry(root: File, relative: String, value: String): Boolean =
        safe(root, relative.removeSuffix("/")) != null &&
            (if (value == DIRECTORY) relative.endsWith('/') else value.matches(HASH) && !relative.endsWith('/'))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun move(from: File, to: File) {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun readJournal(file: File): JSONObject? {
        file.parentFile?.let { require(!Files.isSymbolicLink(it.toPath())) { "Maintenance metadata directory is a symbolic link" } }
        if (Files.isSymbolicLink(file.toPath())) throw IllegalStateException("The itch.io replacement journal is a symbolic link")
        if (!file.exists()) return null
        require(file.isFile) { "The itch.io replacement journal is not a regular file" }
        return runCatching { JSONObject(file.readText()) }.getOrElse { throw IllegalStateException("The itch.io replacement journal is corrupt", it) }
    }

    private fun writeJournal(file: File, value: JSONObject) {
        val temp = File.createTempFile(".itch-commit-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temp).use { it.write(value.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            move(temp, file)
        } finally { temp.delete() }
    }

    private val HASH = Regex("[0-9a-f]{64}")
}

private object StoreInstallsSafeDelete {
    fun delete(file: File) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) file.listFiles().orEmpty().forEach(::delete)
        file.delete()
    }
}
