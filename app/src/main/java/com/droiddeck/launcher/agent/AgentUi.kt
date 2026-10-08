package com.droiddeck.launcher.agent

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's own UI as an agent sees it: every Compose node in every window of the process (popups
 * and menus included), read from Compose's semantics rather than through UI Automator, which on a
 * dual-screen device dumps whichever window has focus - often not this app's. Nodes carry their
 * test tag (ui/TestTags.kt), text, content description, role, state and on-screen bounds; click
 * and set-text act on a node by tag or text, so a test needs no coordinates.
 */
object AgentUi {
    private const val TIMEOUT_MS = 5_000L
    /** Setup's Debugging tools toggle: the bridge never acts on its own gate (AgentAccess). */
    private const val GATE_TAG = "setting-agent-commands"

    fun handle(request: JSONObject): JSONObject = when (val op = request.optString("op", "dump")) {
        "dump" -> onMain { dump(request.optBoolean("all")) }
        "click" -> onMain { act(request) { node -> perform(node, SemanticsActions.OnClick) } }
        "set-text" -> onMain {
            val text = request.optString("text")
            act(request) { node ->
                node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text))
                    ?: throw AgentException("NOT_EDITABLE", "That node takes no text")
            }
        }
        else -> throw AgentException("INVALID_REQUEST", "ui op '$op' is not dump, click or set-text")
    }.put("ok", true).put("command", "ui")

    private fun roots(): List<Pair<View, ViewRootForTest>> {
        if (Build.VERSION.SDK_INT < 29) throw AgentException("UNSUPPORTED", "The UI dump needs Android 10 or later")
        val found = mutableListOf<Pair<View, ViewRootForTest>>()
        fun walk(view: View) {
            if (view is ViewRootForTest) found += view to view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown }.forEach(::walk)
        return found
    }

    private fun dump(all: Boolean): JSONObject {
        val windows = JSONArray()
        for ((view, root) in roots()) {
            val nodes = JSONArray()
            val location = IntArray(2).also { view.getLocationOnScreen(it) }
            fun visit(node: SemanticsNode) {
                val json = describe(node, location)
                if (all || json.has("tag") || json.has("text") || json.has("description") || json.optBoolean("clickable")) nodes.put(json)
                node.children.forEach(::visit)
            }
            visit(root.semanticsOwner.rootSemanticsNode)
            windows.put(JSONObject()
                .put("display", view.display?.displayId ?: JSONObject.NULL)
                .put("window", view.rootView.javaClass.simpleName)
                .put("nodes", nodes))
        }
        return JSONObject().put("windows", windows)
    }

    private fun describe(node: SemanticsNode, origin: IntArray): JSONObject {
        val config = node.config
        val json = JSONObject().put("id", node.id)
        config.getOrNull(SemanticsProperties.TestTag)?.let { json.put("tag", it) }
        val text = textFields(config)
        text.keys().forEach { json.put(it, text.get(it)) }
        config.getOrNull(SemanticsProperties.Role)?.let { json.put("role", it.toString()) }
        config.getOrNull(SemanticsProperties.ToggleableState)?.let { json.put("toggle", it.name) }
        config.getOrNull(SemanticsProperties.Selected)?.let { json.put("selected", it) }
        config.getOrNull(SemanticsProperties.Focused)?.let { json.put("focused", it) }
        if (config.contains(SemanticsProperties.Disabled)) json.put("disabled", true)
        if (config.contains(SemanticsActions.OnClick)) json.put("clickable", true)
        if (config.contains(SemanticsActions.SetText)) json.put("editable", true)
        val b = node.boundsInWindow
        json.put("bounds", JSONArray()
            .put(origin[0] + b.left.toInt()).put(origin[1] + b.top.toInt())
            .put(origin[0] + b.right.toInt()).put(origin[1] + b.bottom.toInt()))
        return json
    }

    internal fun textFields(config: SemanticsConfiguration): JSONObject {
        val password = config.contains(SemanticsProperties.Password)
        val json = JSONObject()
        for ((key, value) in listOf(
            "text" to config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
            "editableText" to config.getOrNull(SemanticsProperties.EditableText)?.text,
            "description" to config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            "state" to config.getOrNull(SemanticsProperties.StateDescription),
        )) value?.let { json.put(key, if (password) "<redacted>" else it) }
        return json
    }

    /**
     * The node a request names by "tag" (exact) or "match" (text or description containing it,
     * ignoring case); "index" picks among several, in tree order.
     */
    private fun act(request: JSONObject, action: (SemanticsNode) -> Unit): JSONObject {
        val tag = request.optString("tag").takeIf { it.isNotEmpty() }
        val text = if (tag == null) request.optString("match").takeIf { it.isNotEmpty() } else null
        if (tag == null && text == null) throw AgentException("INVALID_REQUEST", "Name the node by tag or match")
        val matches = mutableListOf<Pair<SemanticsNode, IntArray>>()
        for ((view, root) in roots()) {
            val origin = IntArray(2).also { view.getLocationOnScreen(it) }
            fun visit(node: SemanticsNode) {
                val config = node.config
                val hit = if (tag != null) config.getOrNull(SemanticsProperties.TestTag) == tag
                else (config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                    ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "")
                    .contains(text!!, ignoreCase = true)
                if (hit) matches += node to origin
                node.children.forEach(::visit)
            }
            visit(root.semanticsOwner.rootSemanticsNode)
        }
        val index = request.optInt("index", 0)
        val (node, origin) = matches.getOrNull(index)
            ?: throw AgentException("NO_SUCH_NODE", "No node with ${if (tag != null) "tag '$tag'" else "text '$text'"} (${matches.size} found)")
        if (generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == GATE_TAG }) {
            throw AgentException("PROTECTED_CONTROL", "Debugging tools are switched on the device only")
        }
        action(node)
        return JSONObject().put("matches", matches.size).put("node", describe(node, origin))
    }

    private fun perform(node: SemanticsNode, key: androidx.compose.ui.semantics.SemanticsPropertyKey<androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>>) {
        val action = node.config.getOrNull(key)?.action ?: throw AgentException("NOT_CLICKABLE", "That node has no click action")
        action()
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Result<T>>()
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching(block))
            done.countDown()
        }
        if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw AgentException("TIMEOUT", "The UI thread did not answer")
        return result.get().getOrThrow()
    }
}
