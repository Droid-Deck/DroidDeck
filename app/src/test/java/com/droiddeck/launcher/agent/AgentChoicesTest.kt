package com.droiddeck.launcher.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentChoicesTest {
    @Test fun aTrailingChoicesBlockBecomesOptions() {
        val reply = choices("Which build?\n\n```choices\n- Debug\n- Release\n```\n")
        assertEquals("Which build?", reply.text)
        assertEquals(listOf("Debug", "Release"), reply.options)
    }

    @Test fun plainLinesAreOptionsToo() {
        assertEquals(listOf("Yes", "No"), choices("Go on?\n```choices\nYes\nNo\n```").options)
    }

    @Test fun aReplyWithoutTheBlockStaysWhole() {
        val text = "Done.\n```kotlin\nval a = 1\n```"
        assertEquals(Choices(text, emptyList()), choices(text))
    }

    @Test fun aChoicesBlockFollowedByAnotherFenceIsNotAQuestion() {
        val text = "```choices\nA\nB\n```\nExplanation.\n```kotlin\nval x = 1\n```"
        assertEquals(Choices(text, emptyList()), choices(text))
    }

    @Test fun aChoicesBlockFollowedByTextIsNotAQuestion() {
        val text = "```choices\nA\nB\n```\nMore after it."
        assertEquals(Choices(text, emptyList()), choices(text))
    }
}
