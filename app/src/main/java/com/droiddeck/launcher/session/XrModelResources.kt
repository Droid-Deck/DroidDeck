package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import org.json.JSONObject
import java.io.File

internal object XrModelResources {
    fun child(root: File, relative: String): File {
        require(relative.isNotEmpty() && !File(relative).isAbsolute)
        return File(root, relative).canonicalFile.also {
            require(it.path.startsWith(root.canonicalPath + File.separator))
        }
    }

    fun model(directory: File, name: String): Boolean = runCatching {
        val components = JSONObject(child(directory, "$name.json").readText()).getJSONObject("components")
        var geometry = false
        for (key in components.keys()) {
            val component = components.getJSONObject(key)
            if (!component.has("filename")) continue
            val mesh = child(directory, component.getString("filename"))
            check(mesh.isFile && mesh.length() > 0)
            geometry = true
            if (mesh.extension == "obj") mesh.useLines { lines ->
                lines.filter { it.startsWith("mtllib ") }.forEach { line ->
                    val material = child(directory, line.removePrefix("mtllib ").trim())
                    check(material.isFile && material.length() > 0)
                    material.useLines { textures ->
                        textures.filter { it.startsWith("map_Kd ") }.forEach { texture ->
                            val image = child(directory, texture.removePrefix("map_Kd ").trim())
                            check(image.isFile && image.length() > 0)
                        }
                    }
                }
            }
        }
        geometry
    }.getOrDefault(false)

    fun pack(directory: File): Boolean = runCatching {
        check(File(directory, "LICENSE.txt").length() > 0)
        val provenance = JSONObject(File(directory, "provenance.json").readText())
        check(provenance.getString("revision") == REVISION)
        val files = provenance.getJSONObject("files")
        check(files.length() > 0)
        for (relative in files.keys()) {
            val file = child(directory, relative)
            check(file.isFile && file.length() > 0)
            check(Hashes.sha256(file).equals(files.getString(relative), ignoreCase = true))
        }
        val models = provenance.getJSONObject("models")
        check(models.length() == 2)
        models.keys().asSequence().all { name -> model(child(directory, "rendermodels/$name"), name) }
    }.getOrDefault(false)

    const val REVISION = "f4992299601614adbfefd398dc8e281556bb7444"
}
