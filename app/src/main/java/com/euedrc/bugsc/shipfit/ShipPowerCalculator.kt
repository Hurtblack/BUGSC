package com.euedrc.bugsc.shipfit

data class PowerSummary(
    val output: Double,
    val consumption: Double,
    val ratio: Int,
    val usedSegments: Int,
    val spareSegments: Int,
    val totalSegments: Int,
    val groups: List<Pair<String, Int>>,
)

object ShipPowerCalculator {
    fun calculate(
        slotsMap: Map<String, String>,
        componentsById: Map<String, FitComponent>,
    ): PowerSummary {
        val selected = slotsMap.values.mapNotNull { componentsById[it] }

        var output = 0.0
        var consumption = 0.0
        val groupRaw = linkedMapOf(
            "WPN" to 0.0,
            "SHD" to 0.0,
            "QTM" to 0.0,
            "RDR" to 0.0,
            "COOL" to 0.0,
        )

        for (c in selected) {
            val size = c.size ?: 1
            when (c.type) {
                "power_plant" -> output += c.powerValue ?: powerPlantPips(size)
                "weapon_gun", "turret", "missile_rack", "missile", "mining_laser" -> {
                    val v = c.powerValue ?: size.toDouble()
                    consumption += v
                    groupRaw["WPN"] = (groupRaw["WPN"] ?: 0.0) + v
                }
                "mining_module" -> {
                    val v = c.powerValue ?: 1.0
                    consumption += v
                    groupRaw["WPN"] = (groupRaw["WPN"] ?: 0.0) + v
                }
                "shield_generator" -> {
                    val v = c.powerValue ?: (size * 2.0)
                    consumption += v
                    groupRaw["SHD"] = (groupRaw["SHD"] ?: 0.0) + v
                }
                "quantum_drive" -> {
                    val v = c.powerValue ?: (size * 2.0)
                    consumption += v
                    groupRaw["QTM"] = (groupRaw["QTM"] ?: 0.0) + v
                }
                "radar" -> {
                    val v = c.powerValue ?: (size * 2.0)
                    consumption += v
                    groupRaw["RDR"] = (groupRaw["RDR"] ?: 0.0) + v
                }
                "cooler" -> {
                    val v = c.powerValue ?: size.toDouble()
                    consumption += v
                    groupRaw["COOL"] = (groupRaw["COOL"] ?: 0.0) + v
                }
            }
        }

        val totalSegments = output.roundToIntCompat().coerceAtLeast(0)
        val usedSeg = consumption.roundToIntCompat().coerceAtLeast(0)
        val ratio = if (totalSegments <= 0) 0 else ((usedSeg.toDouble() / totalSegments) * 100).toInt().coerceIn(0, 999)
        val spare = (totalSegments - usedSeg).coerceIn(0, totalSegments)
        val groups = groupRaw.map { (k, v) ->
            k to v.roundToIntCompat().coerceAtLeast(0)
        }

        return PowerSummary(
            output = output,
            consumption = consumption,
            ratio = ratio,
            usedSegments = usedSeg,
            spareSegments = spare,
            totalSegments = totalSegments,
            groups = groups,
        )
    }

    private fun powerPlantPips(size: Int): Double = when (size) {
        0 -> 4.0
        1 -> 10.0
        2 -> 15.0
        3 -> 22.0
        4 -> 30.0
        else -> size * 8.0
    }
}

private fun Double.roundToIntCompat(): Int =
    kotlin.math.round(this).toInt()
