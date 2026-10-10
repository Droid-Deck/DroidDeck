package com.droiddeck.launcher.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Compose test tags as Android resource ids below this node, so UI Automator (`uiautomator dump`,
 * droiddeckctl ui-dump) sees stable ids such as `rail-steam` or `setting-logs` rather than only
 * labels that change with the language. Tags are named in docs/agent-control.md.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }
