package com.azizjon.network.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantToolSchemasTest {
    private val tools = AssistantToolSchemas.definitions().let { array -> (0 until array.length()).map(array::getJSONObject) }
    private val names = tools.map { it.getString("name") }

    @Test
    fun everyToolIsSomethingTheGatewayAccepts() {
        assertEquals("tool names are unique", names.size, names.toSet().size)
        tools.forEach { tool ->
            val name = tool.getString("name")
            assertTrue(name, Regex("^[A-Za-z0-9_]{1,64}$").matches(name))
            val description = tool.getString("description")
            assertTrue("$name needs a description", description.isNotBlank() && description.length <= 4_000)
            val schema = tool.getJSONObject("input_schema")
            assertEquals("$name arguments must be an object", "object", schema.getString("type"))
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val required = schema.optJSONArray("required")
            (0 until (required?.length() ?: 0)).forEach { index ->
                val key = required!!.getString(index)
                assertTrue("$name requires $key but never describes it", properties.has(key))
            }
        }
    }

    @Test
    fun theToolGroupsNameRealTools() {
        (AssistantToolSchemas.READ_TOOLS + AssistantToolSchemas.CONFIRMATION_TOOLS).forEach { name ->
            assertTrue("$name is not defined", name in names)
        }
    }

    @Test
    fun theDefinitionsFitComfortablyInOneRequest() {
        // The gateway caps a request body well above this; staying small keeps
        // every step's prompt small too.
        assertTrue(AssistantToolSchemas.definitions().toString().length < 20_000)
    }
}
