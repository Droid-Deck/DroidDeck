package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.security.MessageDigest
import java.util.Locale

/** Android shared storage cannot create the links in Wine prefixes or native Steam depots. */
object SecondaryLibrary {
    private val bootstrapTools = linkedMapOf(
        "4185400" to "SteamLinuxRuntime_4-arm64",
        "4427310" to "Proton Experimental (ARM64)",
        "4628740" to "Proton 11.0 (ARM64)",
    )

    fun privateRoot(files: File, library: File): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(library.canonicalPath.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(files, "steam-libraries/$digest")
    }

    /** Parent binds come first; the more specific private directories override them in PRoot. */
    fun binds(files: File, library: File): List<String> {
        val private = privateRoot(files, library)
        val apps = File(library, "steamapps")
        val tools = LinkedHashMap(bootstrapTools)
        // Include other installed Proton/runtime versions, without routing ordinary game depots.
        apps.listFiles()?.filter { it.name.matches(Regex("appmanifest_\\d+\\.acf")) }?.forEach { manifest ->
            val text = manifest.readText()
            val id = Regex("\"appid\"\\s+\"(\\d+)\"").find(text)?.groupValues?.get(1)
            val dir = Regex("\"installdir\"\\s+\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            if (id != null && dir != null && File(dir).name == dir &&
                (dir.startsWith("Proton ") || dir.startsWith("SteamLinuxRuntime"))) tools[id] = dir
        }
        val overrides = ArrayList<Pair<File, String>>()
        for (name in listOf("compatdata", "shadercache")) {
            val target = File(private, "steamapps/$name")
            migrate(File(apps, name), target)
            overrides.add(target to "steamapps/$name")
        }
        for ((id, dir) in tools) {
            for (relative in listOf("steamapps/common/$dir", "steamapps/downloading/$id")) {
                val target = File(private, relative)
                migrate(File(library, relative), target)
                // FUSE discarded the executable bits. Steam will restore the depot's exact modes
                // on verification; its launchers must already be executable when importing it.
                target.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, true) }
                overrides.add(target to relative)
                // Steam normalizes depot install directories to lowercase on Android's
                // case-insensitive shared storage. PRoot binds are case-sensitive, so both
                // spellings must reach the same private directory during the commit.
                val lower = relative.lowercase(Locale.ROOT)
                if (lower != relative) overrides.add(target to lower)
            }
        }
        return listOf("/mnt/droiddeck-sd", "/mnt/bannerlator-sd").flatMap { guest ->
            listOf("${library.path}:$guest") + overrides.map { (host, relative) -> "${host.path}:$guest/$relative" }
        }
    }

    /** Copy once into a staging directory; retain the source and never merge over a live prefix. */
    private fun migrate(source: File, target: File) {
        val migrated = File(target.parentFile, ".${target.name}.migrated")
        if (target.isDirectory) {
            if (!migrated.isFile) migrated.writeText(source.canonicalPath)
            return
        }
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "cannot create ${target.parent}" }
        if (!source.isDirectory || migrated.isFile) {
            check(target.mkdirs()) { "cannot create $target" }
            if (!migrated.isFile) migrated.writeText(source.canonicalPath)
            return
        }
        val stage = File(target.parentFile, "${target.name}.migrating")
        check(!stage.exists() || stage.deleteRecursively()) { "cannot clear $stage" }
        check(stage.mkdirs()) { "cannot create $stage" }
        try {
            Files.walk(source.toPath()).use { paths ->
                paths.forEach { path ->
                    val destination = stage.toPath().resolve(source.toPath().relativize(path))
                    if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(destination)
                    else Files.copy(path, destination, NOFOLLOW_LINKS, COPY_ATTRIBUTES)
                }
            }
            check(stage.renameTo(target)) { "cannot finish migration to $target" }
            migrated.writeText(source.canonicalPath)
        } catch (e: Exception) {
            stage.deleteRecursively()
            throw e
        }
    }
}
