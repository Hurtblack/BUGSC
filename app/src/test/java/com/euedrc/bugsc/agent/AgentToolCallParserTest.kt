package com.euedrc.bugsc.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCallParserTest {

    @Test
    fun parsesStrictToolCallJson() {
        val action = AgentToolCallParser.parse(
            """
            {
              "type": "tool_call",
              "tool": "search_ship",
              "arguments": {
                "query": "C2"
              }
            }
            """.trimIndent(),
        ).getOrThrow()

        assertTrue(action is AgentModelAction.ToolCall)
        action as AgentModelAction.ToolCall
        assertEquals("search_ship", action.tool)
        assertEquals("C2", action.arguments["query"])
    }

    @Test
    fun parsesStrictFinalAnswerJson() {
        val action = AgentToolCallParser.parse(
            """
            {
              "type": "final_answer",
              "answer": "C2 是十字军的大型货船。"
            }
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(AgentModelAction.FinalAnswer("C2 是十字军的大型货船。"), action)
    }

    @Test
    fun rejectsNaturalLanguageAroundJson() {
        val error = AgentToolCallParser.parse(
            """
            我去查一下
            {"type":"tool_call","tool":"search_ship","arguments":{"query":"C2"}}
            """.trimIndent(),
        ).exceptionOrNull()

        assertTrue(error is AgentToolCallParseException)
    }

    @Test
    fun rejectsUnknownOutputType() {
        val error = AgentToolCallParser.parse("""{"type":"search","query":"C2"}""").exceptionOrNull()

        assertTrue(error is AgentToolCallParseException)
    }
}
