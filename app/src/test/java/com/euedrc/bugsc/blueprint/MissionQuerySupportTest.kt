package com.euedrc.bugsc.blueprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionQuerySupportTest {

    @Test
    fun `display fields prefer Chinese and expose searchable metadata`() {
        val mission = sampleMission(
            title = "[DESTINATION] Errand",
            titleCn = "[DESTINATION] 跑腿",
            factionName = "Headhunters",
            factionNameCn = "猎头帮",
            systems = listOf("Pyro"),
            locations = listOf(
                MissionLocation("Last Landings", "终末降落点", "Pyro", "派罗", "Terminus", null, "Outpost")
            ),
            destinations = listOf(
                MissionLocation("Rustville", "锈镇", "Pyro", "派罗", "Pyro I", null, "Destination")
            ),
        )

        assertEquals("[DESTINATION] 跑腿", mission.displayTitle)
        assertEquals("猎头帮", mission.displayFaction)
        assertEquals("Pyro", mission.displaySystems)
        assertTrue(mission.searchTokens.contains("猎头帮"))
        assertTrue(mission.searchTokens.contains("Last Landings"))
    }

    @Test
    fun `mission filter matches query system and faction together`() {
        val missions = listOf(
            sampleMission(titleCn = "赏金任务", factionNameCn = "猎头帮", systems = listOf("Pyro")),
            sampleMission(titleCn = "送货任务", factionNameCn = "十字军安保", systems = listOf("Stanton")),
        )

        val filtered = missions.filter { mission ->
            mission.matchesMissionQuery(query = "赏金", selectedSystem = "Pyro", selectedFaction = "猎头帮")
        }

        assertEquals(1, filtered.size)
        assertEquals("赏金任务", filtered.first().displayTitle)
    }

    @Test
    fun `mission query also matches by location and faction`() {
        val mission = sampleMission(
            titleCn = "清理据点",
            factionNameCn = "猎头帮",
            systems = listOf("Pyro"),
            locations = listOf(
                MissionLocation("Last Landings", "终末降落点", "Pyro", "派罗", "Terminus", null, "Outpost")
            ),
        )

        assertTrue(mission.matchesMissionQuery(query = "终末", selectedSystem = "__all__", selectedFaction = "__all__"))
        assertTrue(mission.matchesMissionQuery(query = "猎头", selectedSystem = "__all__", selectedFaction = "__all__"))
    }

    private fun sampleMission(
        title: String? = null,
        titleCn: String? = null,
        factionName: String? = "Headhunters",
        factionNameCn: String? = "猎头帮",
        systems: List<String> = listOf("Pyro"),
        locations: List<MissionLocation> = emptyList(),
        destinations: List<MissionLocation> = emptyList(),
    ) = RewardMission(
        guid = "m1",
        debugName = "debug",
        category = "career",
        missionType = "Mercenary",
        missionTypeCn = "雇佣兵",
        title = title,
        titleCn = titleCn,
        description = null,
        descriptionCn = null,
        factionName = factionName,
        factionNameCn = factionNameCn,
        canBeShared = true,
        illegal = false,
        onceOnly = false,
        isCombat = true,
        timeToComplete = 10,
        rewardUec = 48250,
        buyIn = null,
        minStanding = null,
        maxStanding = null,
        factionRewards = emptyList(),
        availableSystems = systems,
        pyroRegion = emptyList(),
        rewardChance = null,
        locations = locations,
        destinations = destinations,
        blueprintRewards = emptyList(),
        hasPersonalCooldown = false,
        personalCooldownTime = 0,
        abandonedCooldownTime = 0,
        maxPlayersPerInstance = 1,
        availableInPrison = false,
        canReacceptAfterAbandoning = true,
        canReacceptAfterFailing = false,
        blueprints = listOf(DroppedBlueprint("Test Blueprint", 0.25)),
    )
}
