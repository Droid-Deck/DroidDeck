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

    @Test fun backticksInsideAChoiceAreKept() {
        val reply = choices("How?\n```choices\nUse ``` for code\nUse indentation\n```")
        assertEquals("How?", reply.text)
        assertEquals(listOf("Use ``` for code", "Use indentation"), reply.options)
    }

    @Test fun aLongerOpenerNeedsAClosingFenceAsLong() {
        val reply = choices("````choices\nA\n```\nB\n````")
        assertEquals(listOf("A", "```", "B"), reply.options)
    }

    @Test fun aChoicesExampleInsideACodeBlockStaysLiteral() {
        val text = "Example:\n````markdown\n```choices\nA\nB\n````"
        assertEquals(Choices(text, emptyList()), choices(text))
    }

    @Test fun anUnclosedChoicesBlockIsNotAQuestion() {
        val text = "Pick:\n```choices\nA\nB"
        assertEquals(Choices(text, emptyList()), choices(text))
    }
}
