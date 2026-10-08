package com.droiddeck.launcher.session

import android.content.Context
import android.util.AtomicFile
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import java.io.File

/**
 * The recipes behind "DroidDeck Proton (Auto)": per game, a DXVK, VKD3D-Proton and/or FEX package
 * from the Nightlies "-Linux" releases and a few environment variables.
 *
 * The app keeps the recipes ([OVERRIDE] in its files, else the bundled [ASSET]), makes sure every
 * package they name is stored (the same download Components uses), unpacks each into the runtime's
 * recipe store, and publishes [GUEST_FILE] with those paths. droiddeck-recipe reads that file when
 * the Auto tool launches a game and starts Proton from a tree assembled for it; nothing is swapped
 * into any Proton, so every other tool stays as it shipped.
 *
 * A recipe may only set tuning variables: the same rule droiddeck-recipe applies, kept here so a
 * bad file is refused before it is published.
 */
object DdProtonRecipes {
    private const val TAG = "DdProtonRecipes"
    const val ASSET = "recipes/default.json"
    const val OVERRIDE = "recipes.json"
    private const val GUEST_FILE = "root/.config/droiddeck/recipes.json"
    private const val STORE = "root/.local/share/droiddeck-recipes/store"

    private val ENV_PREFIXES = listOf("DXVK_", "VKD3D_", "FEX_", "MESA_", "PROTON_")
    private val ENV_EXACT = setOf("mesa_glthread", "WINEDLLOVERRIDES")
    private val ENV_DENIED_PARTS = listOf("PATH", "_DIR", "_FILE", "LIBRARY", "ROOTFS", "THUNK", "PRELOAD")
    private val DLL_OVERRIDE = Regex("[A-Za-z0-9_.*-]+(?:,[A-Za-z0-9_.*-]+)*=(?:n|b|n,b|b,n|d)?")

    data class PackageRef(val comp: String, val file: String, val release: String)
    data class Recipe(val id: String, val packages: List<PackageRef>, val env: Map<String, String>, val note: String)

    fun envAllowed(name: String, value: String): Boolean {
        if (!GameEnvironment.validName(name) || name.startsWith("LD_")) return false
        if (ENV_DENIED_PARTS.any { it in name.uppercase() }) return false
        if (name !in ENV_EXACT && ENV_PREFIXES.none { name.startsWith(it) }) return false
        if (!GameEnvironment.validValue(value)) return false
        if (name == "WINEDLLOVERRIDES") return value.split(';').filter { it.isNotEmpty() }.all { DLL_OVERRIDE.matches(it) }
        return true
    }

    /** The recipes in [text]; throws IllegalArgumentException naming the first thing wrong. */
    fun parse(text: String): List<Recipe> {
        val json = JSONObject(text)
        require(json.optInt("version") == 1) { "recipes: version must be 1" }
        val games = json.optJSONObject("games") ?: return emptyList()
        return games.keys().asSequence().sorted().map { id ->
            require(id.isNotEmpty() && GameEnvironment.validScope(id)) { "recipes: $id is not a Steam app or shortcut id" }
            val game = games.getJSONObject(id)
            val packages = ComponentsManager.COMPONENTS.mapNotNull { comp ->
                val ref = game.optJSONObject(comp) ?: return@mapNotNull null
                val file = ref.optString("file")
                val release = ref.optString("release")
                require(file.endsWith(".wcp") && ComponentsManager.safeName(file) == file) { "recipes: $id: $comp file '$file'" }
                require(release.isNotEmpty() && ComponentsManager.safeName(release) == release) { "recipes: $id: $comp release '$release'" }
                PackageRef(comp, file, release)
            }
            val env = buildMap {
                game.optJSONObject("env")?.let { vars ->
                    vars.keys().forEach { name ->
                        val value = vars.get(name)
                        require(value is String && envAllowed(name, value)) { "recipes: $id: $name is not a variable a recipe may set" }
                        put(name, value)
                    }
                }
            }
            Recipe(id, packages, env, game.optString("note").take(200))
        }.toList()
    }

    /** The recipe store directory a package unpacks into. */
    internal fun storeKey(file: String) = ComponentsManager.safeName(file.removeSuffix(".wcp"))

    /**
     * The guest file: each game's components as unpacked store paths. A component whose package is
     * not in [available] (file name -> guest path) is left out, so that game runs on the depot's copy.
     */
    internal fun publishJson(recipes: List<Recipe>, available: Map<String, String>): JSONObject {
        val games = JSONObject()
        for (recipe in recipes) {
            val game = JSONObject()
            for (ref in recipe.packages) available[ref.file]?.let { game.put(ref.comp, it) }
            if (recipe.env.isNotEmpty()) game.put("env", JSONObject(recipe.env))
            if (recipe.note.isNotEmpty()) game.put("note", recipe.note)
            games.put(recipe.id, game)
        }
        return JSONObject().put("version", 1).put("games", games)
    }

    private fun source(context: Context): String? {
        val override = File(context.filesDir, OVERRIDE)
        if (override.isFile) return override.readText()
        return runCatching { context.assets.open(ASSET).use { it.readBytes().decodeToString() } }.getOrNull()
    }

    /**
     * Fetches and unpacks what the recipes need, then publishes them; returns a line for the session
     * log. A package that cannot be fetched is left out of the published file and named in the line.
     */
    fun ensure(context: Context): String = ensure(
        context,
        { file, release -> ComponentsManager.catalogItem(context, file, release) },
        { ComponentsManager.download(context, it) {} },
    )

    internal fun ensure(
        context: Context,
        lookup: (file: String, release: String) -> ComponentsManager.CatalogItem?,
        fetch: (ComponentsManager.CatalogItem) -> Unit,
        kind: (file: String) -> String? = { ComponentsManager.storedPackage(context, it)?.comp },
        unpack: (file: String, dir: File) -> Unit = { file, dir -> ComponentsManager.unpack(ComponentsManager.packageFile(context, file), dir) },
    ): String {
        val text = source(context)
        val recipes = if (text == null) emptyList() else parse(text)
        val root = LinuxRuntime.rootDir(context)
        val store = File(root, STORE).apply { mkdirs() }
        val available = LinkedHashMap<String, String>()
        val problems = ArrayList<String>()
        for (ref in recipes.flatMap { it.packages }.distinctBy { it.file }) {
            val fetched = runCatching { ComponentsManager.missingPackage(context, ref.comp, ref.file, ref.release, lookup, fetch) }
            val missing = fetched.getOrElse { "${ref.file}: ${it.message}" }
            if (missing != null) { problems += missing; continue }
            when (kind(ref.file)) {
                ref.comp -> Unit
                null -> { problems += "${ref.file} could not be read"; continue }
                else -> { problems += "${ref.file} is not a ${ref.comp} package"; continue }
            }
            val dir = File(store, storeKey(ref.file))
            runCatching { unpack(ref.file, dir) }
                .onFailure { dir.deleteRecursively(); problems += "${ref.file}: ${it.message}" }
                .onSuccess { available[ref.file] = "/" + dir.absolutePath.removePrefix(root.absolutePath.trimEnd('/')).trimStart('/') }
        }
        val keep = available.keys.map { storeKey(it) }.toSet()
        store.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
        write(File(root, GUEST_FILE), publishJson(recipes, available).toString())
        val line = "$TAG: ${recipes.size} recipe(s), ${available.size} package(s) ready"
        return if (problems.isEmpty()) line else "$line; " + problems.joinToString("; ")
    }

    private fun write(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
