package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMapTest {
    private val strings: Map<String, String> by lazy {
        File("src/main/res/values").listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            (0 until nodes.length).map { i ->
                val n = nodes.item(i)
                n.attributes.getNamedItem("name").nodeValue to n.textContent.replace("\\'", "'").replace("\\\"", "\"")
            }
        }.toMap()
    }

    private val names: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private fun text(id: Int): String = strings.getValue(names.getValue(id))

    private fun render(): String = buildString {
        appendLine("# Where settings live")
        appendLine()
        appendLine("Generated from `SettingsIndex.kt` by `SettingsMapTest`. Run `UPDATE_SETTINGS_MAP=1 ./gradlew :app:testDebugUnitTest --tests '*SettingsMapTest*'` after changing the index.")
        appendLine()
        appendLine("Links of the form `droiddeck://settings/<section>` open a Settings section directly.")
        appendLine()
        SettingsIndex.entries.groupBy { it.place }.forEach { (place, entries) ->
            appendLine("## ${text(place.title)}")
            appendLine()
            appendLine("| Setting | Where | Also matches |")
            appendLine("| --- | --- | --- |")
            entries.forEach { e ->
                val where = listOfNotNull(text(place.title), e.within?.let(::text)).joinToString(" › ") +
                    (e.section?.let { " (`droiddeck://settings/${it.key}`)" } ?: "")
                appendLine("| ${text(e.title)} | $where | ${e.words.joinToString(", ")} |")
            }
            appendLine()
        }
    }

    @Test
    fun everyIndexedStringExists() {
        SettingsIndex.entries.forEach { e ->
            listOfNotNull(e.title, e.within).forEach { id -> assertTrue("missing string $id", names[id] in strings) }
        }
        SettingsSection.entries.forEach { assertTrue(names[it.title] in strings) }
    }

    @Test
    fun docsMatchIndex() {
        val doc = File("../docs/settings-map.md")
        val now = render()
        if (System.getenv("UPDATE_SETTINGS_MAP") == "1") doc.writeText(now)
        assertEquals("docs/settings-map.md is stale; regenerate it", now, doc.readText().replace("\r\n", "\n"))
    }

    @Test
    fun sectionKeysRoundTrip() {
        SettingsSection.entries.forEach { assertEquals(it, SettingsSection.of(it.key)) }
    }
}
