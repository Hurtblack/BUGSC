package com.euedrc.bugsc.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentLocalDataProviderInstrumentedTest {

    @Test
    fun chineseQuartzFindsBlueprintsUsingQuartzMaterial() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = LocalAgentDataProvider(context)

        val hits = provider.search(
            AgentQuery(
                rawText = "石英",
                normalizedText = "石英",
                intents = listOf(ScoredIntent(AgentIntent.BLUEPRINT, 10)),
                entities = emptyList(),
            ),
        )

        assertTrue(hits.any { it.summary.contains("Quartz") })
    }
}
