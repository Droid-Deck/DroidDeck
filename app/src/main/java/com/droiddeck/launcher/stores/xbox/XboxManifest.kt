package com.droiddeck.launcher.stores.xbox

import java.io.File
import org.xmlpull.v1.XmlPullParser

internal object XboxManifest {
    data class Info(val storeId: String, val name: String, val publisher: String, val version: String,
        val dependency: String, val related: List<String>, val executables: List<String>, val entryPoint: String,
        val runtimeBehavior: String = "")

    /** A GameCore title describes itself with the config; a legacy package does not. */
    private const val GAME_CONFIG = "MicrosoftGame.config"
    private const val APPX_MANIFEST = "AppxManifest.xml"
    private const val FULL_TRUST = "Windows.FullTrustApplication"
    private const val APP_MODEL = "windowsApp"
    private val directBehaviors = setOf("packagedClassicApp", "win32App")

    fun read(folder: File): Info {
        // Both manifests are read the same way: an identity, an executable path and,
        // for a Store package, related content.
        val files = folder.listFiles().orEmpty()
        val config = files.filter { it.name.equals(GAME_CONFIG, true) && it.isFile }
        val legacy = files.filter { it.name.equals(APPX_MANIFEST, true) && it.isFile }
        val manifests = config.ifEmpty { legacy }
        require(manifests.size == 1) { "Missing or ambiguous game manifest." }
        val manifest = manifests.single()
        val rootTag = if (config.isEmpty()) "Package" else "Game"
        require(manifest.length() <= 1_048_576)
        val parser = android.util.Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        val identities = ArrayList<List<String>>()
        val storeIds = ArrayList<String>()
        val dependencies = ArrayList<String>()
        val related = ArrayList<String>()
        val entries = ArrayList<String>()
        val entryPoints = ArrayList<String>()
        val behaviors = ArrayList<String>()
        fun attribute(name: String) = parser.getAttributeValue(null, name).orEmpty()
        fun localAttribute(name: String) = (0 until parser.attributeCount)
            .firstOrNull { parser.getAttributeName(it).substringAfterLast(':') == name }?.let(parser::getAttributeValue).orEmpty()
        var root = false
        var elements = 0
        manifest.inputStream().use { input ->
            parser.setInput(input, null)
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> error("Xbox game manifests cannot contain a DTD.")
                    XmlPullParser.START_TAG -> {
                        require(++elements <= 10_000 && parser.depth <= 64) { "Xbox game manifest is too complex." }
                        if (parser.depth == 1) { require(!root && parser.name == rootTag); root = true }
                        when (parser.name) {
                            "Identity" -> identities.add(listOf(attribute("Name"), attribute("Publisher"), attribute("Version")))
                            "StoreId" -> storeIds.add(parser.nextText().trim())
                            "MainPackageDependency" -> dependencies.add(attribute("Name"))
                            "RelatedProduct" -> related.add(parser.nextText().trim())
                            // Both carry the launch path: an attribute on the config's
                            // Executable, or on the AppxManifest's Application.
                            "Executable", "Application" -> {
                                val path = attribute("Name").ifEmpty { attribute("Executable") }.replace('\\', '/')
                                if (path.endsWith(".exe", true) && attribute("TargetDeviceFamily") in listOf("", "PC", "Windows.Desktop") &&
                                    !attribute("IsDevOnly").equals("true", true)) entries.add(path)
                                if (parser.name == "Application") {
                                    attribute("EntryPoint").takeIf { it.isNotEmpty() }?.let(entryPoints::add)
                                    localAttribute("RuntimeBehavior").takeIf { it.isNotEmpty() }?.let(behaviors::add)
                                }
                            }
                        }
                    }
                }
            }
        }
        require(root && identities.size <= 1 && storeIds.size <= 1 && dependencies.size <= 1) { "Invalid Xbox game manifest." }
        val ids = related.distinct()
        require(ids.size <= 128 && ids.all { it.matches(Regex("[A-Z0-9]{12}")) })
        val entry = entryPoints.distinct()
        val behavior = behaviors.distinct()
        require(entry.size <= 1 && behavior.size <= 1) { "Invalid Xbox game manifest." }
        val identity = identities.singleOrNull()
        return Info(storeIds.singleOrNull().orEmpty(), identity?.get(0).orEmpty(), identity?.get(1).orEmpty(), identity?.get(2).orEmpty(),
            dependencies.singleOrNull().orEmpty(), ids, entries, entry.singleOrNull().orEmpty(), behavior.singleOrNull().orEmpty())
    }

    /**
     * A full trust package starts its executable directly. Anything else is activated
     * through the Windows app model, which this runtime has no host for, so starting
     * the executable would only crash.
     */
    fun needsAppModel(folder: File): Boolean {
        val info = runCatching { read(folder) }.getOrNull() ?: return false
        val behavior = info.runtimeBehavior
        if (behavior.equals(APP_MODEL, true)) return true
        if (directBehaviors.any { it.equals(behavior, true) }) return false
        return info.entryPoint.isNotEmpty() && !info.entryPoint.equals(FULL_TRUST, true)
    }

    fun executable(folder: File): String {
        for (path in read(folder).executables) {
            require(!path.startsWith('/') && !path.contains(':') && path.split('/').none { it == ".." || it.isEmpty() })
            var file = folder
            for (part in path.split('/')) {
                val matches = file.listFiles().orEmpty().filter { it.name.equals(part, true) }
                require(matches.size == 1) { "Missing or ambiguous Xbox launch path." }
                file = matches.single()
            }
            require(file.canonicalPath.startsWith(folder.canonicalPath + File.separator))
            if (file.isFile) return forwarded(folder, file) ?: file.relativeTo(folder).invariantSeparatorsPath
        }
        error("The Xbox package has no supported PC launch executable.")
    }

    private fun forwarded(folder: File, launcher: File): String? = try {
        val arguments = File(launcher.parentFile, launcher.nameWithoutExtension + "LaunchArguments.txt")
        if (!arguments.isFile || arguments.length() > 65_536) null
        else arguments.readLines().firstNotNullOfOrNull { line ->
            line.trim().takeIf { it.startsWith("exe=", ignoreCase = true) }?.substring(4)?.trim()
        }?.takeIf { it.isNotEmpty() && !it.contains(':') }?.let { target -> located(folder.canonicalFile, launcher.parentFile.canonicalFile, target) }
    } catch (_: java.io.IOException) {
        null
    }

    private fun located(root: File, start: File, target: String): String? {
        var file = start
        for (part in target.split('/', '\\').filter { it.isNotEmpty() && it != "." }) {
            file = if (part == "..") file.parentFile ?: return null
            else file.listFiles().orEmpty().filter { it.name.equals(part, true) }.singleOrNull() ?: return null
        }
        val resolved = file.canonicalFile
        if (!resolved.isFile || !resolved.path.startsWith(root.path + File.separator) || !resolved.extension.equals("exe", true)) return null
        return resolved.relativeTo(root).invariantSeparatorsPath
    }
}
