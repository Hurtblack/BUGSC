package com.euedrc.bugsc.agent

import com.euedrc.bugsc.mining.MiningElement
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMaterialAliasTest {

    @Test
    fun chineseRawMiningNameMatchesBlueprintMaterialBaseName() {
        val aliases = AgentMaterialAliasIndex(
            listOf(
                miningElement(
                    nameEn = "Quartz (Raw)",
                    nameCn = "石英（粗制） (原料 8SCU货箱)",
                ),
            ),
        )

        assertTrue(aliases.matches("Quartz", "石英"))
    }

    private fun miningElement(nameEn: String, nameCn: String): MiningElement =
        MiningElement(
            guid = nameEn,
            nameEn = nameEn,
            nameCn = nameCn,
            rarity = "common",
            density = 0.0,
            instability = 0.0,
            resistance = 0.0,
            optimalWindowMidpoint = 0.0,
            optimalWindowRandomness = 0.0,
            optimalWindowThinness = 0.0,
            explosionMultiplier = 0.0,
            clusterFactor = 0.0,
            scanSignature = null,
            fpsScanSignature = null,
            groundScanSignature = null,
        )
}
