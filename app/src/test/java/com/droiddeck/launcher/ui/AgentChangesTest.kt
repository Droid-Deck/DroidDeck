package com.droiddeck.launcher.ui

import com.droiddeck.launcher.agent.AgentHost.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentChangesTest {
    @Test fun aTurnAddsUpTheEditsItMade() {
        val entries = listOf(
            Entry.Asked("before"), Entry.Used("e0", "Edit", "old.kt", "old.kt\n-a\n+b"), Entry.Finished(null),
            Entry.Asked("fix it"),
            Entry.Used("e1", "Edit", "a.kt", "src/a.kt\n x\n-y\n+z\n+w"),
            Entry.Used("e2", "Write", "b.kt", "src/b.kt\n+one"),
            Entry.Used("e3", "Edit", "a.kt", "src/a.kt\n-q", failed = true),
            Entry.Used("e4", "Edit", "a.kt", "src/a.kt\n-r"),
            Entry.Used("b1", "Bash", "ls", "ls"),
            Entry.Finished(null),
        )
        assertEquals(Changes(files = 2, added = 3, removed = 2), turnChanges(entries, entries.lastIndex))
        assertEquals(Changes(1, 1, 1), turnChanges(entries, 2))
    }

    @Test fun onlyEditsCarryingADiffCount() {
        assertNull(diffOf(Entry.Used("c", "Edit", "a.kt, b.kt", "a.kt\nb.kt")))
        assertNull(diffOf(Entry.Used("b", "Bash", "ls", "ls\n+x")))
        assertEquals(2, diffOf(Entry.Used("e", "Edit", "a.kt", "a.kt\n\n- old\n\n+ new"))?.lines?.size)
        assertNull(turnChanges(listOf(Entry.Asked("hi"), Entry.Said("hello"), Entry.Finished(null)), 2))
    }

    @Test fun aSavedPlanIsNotAChange() {
        val entries = listOf(Entry.Asked("plan it"), Entry.Used("w", "Write", "plan.md", "/root/.claude/plans/plan.md\n+step"), Entry.Finished(null))
        assertNull(turnChanges(entries, 2))
    }

    @Test fun bigCountsReadAsMillions() {
        assertEquals("1M", tokens(1_000_000))
        assertEquals("1.5M", tokens(1_500_000))
        assertEquals("23k", tokens(23_400))
    }
}
