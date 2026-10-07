package com.droiddeck.launcher.session

import android.util.Log
import java.io.File

/**
 * The Steam accounts the client remembers, and which one the next session starts as.
 *
 * The client keeps every account it has a saved sign-in for in loginusers.vdf and signs in as the
 * one marked MostRecent (and named by AutoLoginUser in ~/.steam/registry.vdf). Switching is made
 * in the guest, by droiddeck-steam-account, right before the client starts and only when no client
 * is running: this side never writes Steam's files. It only leaves one SteamID64 in
 * ~/.config/droiddeck/steam-account-next, which that helper consumes at the next start. Adding an
 * account stays Steam's own: Power > Add Account inside the client.
 */
object SteamAccounts {
    private const val TAG = "SteamAccounts"
    private val STEAMID = Regex("""7656119\d{10}""")
    // OfflineMode's account check before accounts could be switched, kept as it was: activeLabel
    // is non-null exactly when this matches, and a single account is labelled as before.
    private val REMEMBER = Regex(""""RememberPassword"\s*"1"""")
    private val PERSONA = Regex(""""PersonaName"\s*"([^"]*)"""")
    // The block AddedGames.steamRoute reads its MostRecent account from.
    private val BLOCK = Regex(""""(\d{5,})"\s*\{([^}]*)\}""")
    private val RECENT = Regex(""""MostRecent"\s*"1"""")

    data class Account(val id64: Long, val name: String, val persona: String, val mostRecent: Boolean)

    fun loginUsers(root: File) = File(root, "root/.local/share/Steam/config/loginusers.vdf")

    private fun requestFile(root: File) = File(root, "root/.config/droiddeck/steam-account-next")

    /** The accounts in loginusers.vdf, as the client would read it; empty when it is unreadable. */
    fun read(root: File): List<Account> {
        val file = loginUsers(root)
        return try {
            if (file.isFile) parse(file.readText()) else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "could not read loginusers.vdf", e); emptyList()
        }
    }

    /**
     * The remembered accounts (RememberPassword "1") in file order. Read like the guest helper
     * reads it, so the list here is the list it will accept: an empty list when the braces do not
     * balance, there is no "users" root, an id is not a SteamID64 or appears twice, or a
     * remembered account has no AccountName.
     */
    fun parse(text: String): List<Account> {
        val root = tree(tokens(text) ?: return emptyList()) ?: return emptyList()
        val users = root.filter { it.first.equals("users", ignoreCase = true) }
        val list = users.singleOrNull()?.second as? List<*> ?: return emptyList()
        val out = ArrayList<Account>()
        val seen = HashSet<String>()
        for (entry in list) {
            val (id, value) = entry as Pair<*, *>
            id as String
            if (!STEAMID.matches(id) || !seen.add(id) || value !is List<*>) return emptyList()
            val fields = HashMap<String, String>()
            for (kv in value) {
                val (k, v) = kv as Pair<*, *>
                if (v !is String || fields.put((k as String).lowercase(), v) != null) return emptyList()
            }
            if (fields["rememberpassword"] != "1") continue
            val name = fields["accountname"].orEmpty()
            if (name.isBlank()) return emptyList()
            out += Account(id.toLong(), name, unescape(fields["personaname"].orEmpty()), fields["mostrecent"] == "1")
        }
        return out
    }

    /** What the Setup page calls the signed-in account; null when there is none to be offline as. */
    fun activeLabel(text: String): String? {
        if (!REMEMBER.containsMatchIn(text)) return null
        val recent = BLOCK.findAll(text).firstOrNull { RECENT.containsMatchIn(it.groupValues[2]) }
        recent?.let { PERSONA.find(it.groupValues[2])?.groupValues?.get(1) }?.takeIf { it.isNotBlank() }?.let { return it }
        return PERSONA.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }.orEmpty()
    }

    /** The account a menu shows: its persona, with the account name too when two personas match. */
    fun label(account: Account, all: List<Account>): String {
        val persona = account.persona.ifBlank { return account.name }
        return if (all.count { it.persona == persona } > 1) "$persona · ${account.name}" else persona
    }

    /** The account the next session will be switched to, when the user picked one. */
    fun pending(root: File): Long? = try {
        val file = requestFile(root)
        if (file.isFile) file.readText().trim().takeIf { STEAMID.matches(it) }?.toLong() else null
    } catch (e: Exception) {
        null
    }

    /** A pick only while no session runs: the guest reads it once, as the client starts. */
    fun requestAllowed(running: Boolean) = !running

    /**
     * Ask for the next session to start as [id64]; the account already active cancels the request
     * instead. Through a staged file and a rename, so the guest never reads half a request.
     */
    fun request(root: File, id64: Long, activeId: Long?): Boolean {
        val file = requestFile(root)
        if (id64 == activeId) return !file.exists() || file.delete()
        if (!STEAMID.matches(id64.toString())) return false
        val staged = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            staged.writeText("$id64\n")
            staged.renameTo(file).also { if (!it) { staged.delete(); Log.w(TAG, "could not write the account request") } }
        } catch (e: Exception) {
            staged.delete()
            Log.w(TAG, "could not write the account request", e)
            false
        }
    }

    // ---- text KeyValues, as droiddeck-steam-account tokenizes them ----

    /** Quoted strings (raw, escapes kept) and the braces as "{" / "}" markers; null on anything else. */
    private fun tokens(text: String): List<Any>? {
        val out = ArrayList<Any>()
        var i = if (text.startsWith("﻿")) 1 else 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000C' || c == '\u000B' -> i++
                c == '/' && text.startsWith("//", i) -> text.indexOf('\n', i).let { i = if (it < 0) text.length else it }
                c == '{' || c == '}' -> { out += Brace(c); i++ }
                c == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') j += if (text[j] == '\\') 2 else 1
                    if (j >= text.length) return null
                    out += text.substring(i + 1, j)
                    i = j + 1
                }
                else -> return null
            }
        }
        return out
    }

    private data class Brace(val c: Char)

    /** Key and value pairs per level, a value a String or a nested list; null when unbalanced. */
    private fun tree(tokens: List<Any>): List<Pair<String, Any>>? {
        val stack = ArrayList<MutableList<Pair<String, Any>>>().apply { add(ArrayList()) }
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == Brace('}')) {
                if (stack.size == 1) return null
                stack.removeAt(stack.size - 1); i++; continue
            }
            if (t !is String || i + 1 >= tokens.size) return null
            when (val next = tokens[i + 1]) {
                is String -> stack.last() += t to next
                Brace('{') -> ArrayList<Pair<String, Any>>().let { stack.last() += t to it; stack += it }
                else -> return null
            }
            i += 2
        }
        return if (stack.size == 1) stack[0] else null
    }

    private fun unescape(raw: String): String = Regex("""\\(.)""").replace(raw) { it.groupValues[1] }
}
