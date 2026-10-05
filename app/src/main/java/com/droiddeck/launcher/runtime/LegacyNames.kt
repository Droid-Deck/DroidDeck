package com.droiddeck.launcher.runtime

import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object LegacyNames {
    private const val TAG = "LegacyNames"
    const val SESSION_SCRIPT = "/usr/local/bin/bannerlator-session"
    val SCRIPTS = listOf(
        "appimage-run", "bwrap", "desktop-games", "flatpak", "flatpak-run", "flatpak-setup",
        "game-env", "login1", "netmanager", "pad-defaults", "proton-extra", "script-run",
        "seed-redists", "session", "steam-games", "steam-install", "steam-launch",
        "steam-library", "steam-shim", "steam-shortcuts",
    )
    private val LINKED_DIRS = listOf("etc/bannerlator" to "etc/droiddeck", "usr/local/share/bannerlator" to "usr/local/share/droiddeck")
    private val MOVED_FILES = listOf(
        "etc/bannerlator-net" to "etc/droiddeck-net",
        "etc/bannerlator-network.json" to "etc/droiddeck-network.json",
        "etc/bannerlator-wifi-scan-request" to "etc/droiddeck-wifi-scan-request",
    )
    private const val MENU_DIR = "usr/local/share/applications"
    private const val OLD_BIN = "/usr/local/bin/bannerlator-"
    private const val NEW_BIN = "/usr/local/bin/droiddeck-"

    @JvmStatic
    @Synchronized
    fun migrateEtc(root: File) {
        for ((old, new) in LINKED_DIRS) moveAndLink(File(root, old), File(root, new))
        File(root, "etc/droiddeck/empty").mkdirs()
    }

    @Synchronized
    fun migrateRootfs(root: File) {
        migrateEtc(root)
        for ((old, new) in MOVED_FILES) move(File(root, old), File(root, new))
        linkScripts(root)
        rewriteMenuEntries(root)
    }

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    private fun move(legacy: File, target: File) {
        if (isLink(legacy) || !legacy.exists()) return
        if (legacy.isDirectory && !isLink(target) && (target.isDirectory || !target.exists())) {
            target.mkdirs()
            legacy.listFiles()?.forEach { move(it, File(target, it.name)) }
            if (!legacy.delete()) Log.w(TAG, "could not empty $legacy")
            return
        }
        if (target.exists() || isLink(target)) {
            FileUtils.delete(legacy)
        } else if (!legacy.renameTo(target)) {
            Log.w(TAG, "could not move $legacy to $target")
        }
    }

    private fun moveAndLink(legacy: File, target: File) {
        if (isLink(legacy) || !legacy.exists()) return
        move(legacy, target)
        if (!legacy.exists()) replaceWithLink(legacy, target)
    }

    private fun replaceWithLink(legacy: File, target: File) {
        val link = File(legacy.parentFile, legacy.name + ".link")
        FileUtils.delete(link)
        try {
            Files.createSymbolicLink(link.toPath(), Paths.get(target.name))
            if (!link.renameTo(legacy)) {
                FileUtils.delete(link)
                Log.w(TAG, "could not link $legacy to ${target.name}")
            }
        } catch (e: Exception) {
            FileUtils.delete(link)
            Log.w(TAG, "could not link $legacy to ${target.name}", e)
        }
    }

    private fun linkScripts(root: File) {
        val bin = File(root, "usr/local/bin")
        for (name in SCRIPTS) {
            val target = File(bin, "droiddeck-$name")
            val legacy = File(bin, "bannerlator-$name")
            if (!target.isFile || isLink(target)) continue
            if (isLink(legacy)) {
                val points = runCatching { Files.readSymbolicLink(legacy.toPath()).toString() }.getOrNull()
                if (points == target.name) continue
            } else if (!legacy.exists()) {
                continue
            }
            replaceWithLink(legacy, target)
        }
    }

    private fun rewriteMenuEntries(root: File) {
        val entries = File(root, MENU_DIR).listFiles { f -> f.isFile && f.name.startsWith("droiddeck-") && f.name.endsWith(".desktop") } ?: return
        for (entry in entries) {
            val text = FileUtils.readString(entry) ?: continue
            if (!text.contains(OLD_BIN)) continue
            val staged = File(entry.parentFile, entry.name + ".staged")
            if (!FileUtils.writeString(staged, text.replace(OLD_BIN, NEW_BIN)) || !staged.renameTo(entry)) {
                staged.delete()
                Log.w(TAG, "could not rewrite $entry")
            }
        }
    }
}
