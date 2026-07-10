package com.euedrc.bugsc.wbccu

import java.util.PriorityQueue

data class WbccuChainPlanRequest(
    val startShipName: String,
    val startShipPriceCents: Int,
    val targetShipName: String,
    val targetShipPriceCents: Int,
    val inventoryCcus: List<WbccuInventoryCcuCandidate>
)

data class WbccuChainPlanResult(
    val steps: List<WbccuChainStep>,
    val errors: List<String> = emptyList()
)

object WbccuChainPlanner {

    fun plan(request: WbccuChainPlanRequest): WbccuChainPlanResult {
        if (request.startShipName.isBlank() || request.targetShipName.isBlank()) {
            return WbccuChainPlanResult(emptyList(), listOf("请选择起始飞船和目标飞船"))
        }
        if (request.startShipPriceCents <= 0 || request.targetShipPriceCents <= 0) {
            return WbccuChainPlanResult(emptyList(), listOf("起始飞船和目标飞船必须有有效船价"))
        }
        if (request.startShipPriceCents >= request.targetShipPriceCents) {
            return WbccuChainPlanResult(emptyList(), listOf("目标飞船价格必须高于起始飞船"))
        }

        val nodes = buildNodes(request)
        val startKey = NodeKey(request.startShipName, request.startShipPriceCents)
        val targetKey = NodeKey(request.targetShipName, request.targetShipPriceCents)
        val edges = buildEdges(nodes, request.inventoryCcus)
        val best = shortestPath(startKey, targetKey, edges)
        val ccuSteps = best.mapIndexed { index, edge ->
            when (edge) {
                is PlannerEdge.Inventory -> edge.toStep(index)
                is PlannerEdge.Standard -> edge.toStep(index)
            }
        }
        val base = WbccuChainStep(
            id = WbccuChainCodec.newId("start"),
            fromShipName = "",
            toShipName = request.startShipName,
            fromShipPriceCents = 0,
            toShipPriceCents = request.startShipPriceCents,
            paidCents = 0,
            saleType = WbccuSaleType.BASE,
            source = WbccuChainStepSource.BASE,
            note = "起始飞船"
        )
        return WbccuChainPlanResult(listOf(base) + ccuSteps)
    }

    private fun buildNodes(request: WbccuChainPlanRequest): List<NodeKey> {
        val nodes = linkedMapOf<String, NodeKey>()
        fun add(name: String, priceCents: Int) {
            if (name.isBlank() || priceCents <= 0) return
            if (priceCents < request.startShipPriceCents || priceCents > request.targetShipPriceCents) return
            val key = "${WbccuChainValidator.normalizeShipName(name)}@$priceCents"
            nodes.putIfAbsent(key, NodeKey(name, priceCents))
        }
        add(request.startShipName, request.startShipPriceCents)
        add(request.targetShipName, request.targetShipPriceCents)
        request.inventoryCcus.forEach { candidate ->
            val summary = candidate.summary
            if (summary.fromShipPriceCents < summary.toShipPriceCents) {
                add(summary.fromShipName, summary.fromShipPriceCents)
                add(summary.toShipName, summary.toShipPriceCents)
            }
        }
        return nodes.values.sortedWith(compareBy<NodeKey> { it.priceCents }.thenBy { it.name })
    }

    private fun buildEdges(
        nodes: List<NodeKey>,
        inventoryCcus: List<WbccuInventoryCcuCandidate>
    ): Map<NodeKey, List<PlannerEdge>> {
        val out = linkedMapOf<NodeKey, MutableList<PlannerEdge>>()
        nodes.forEach { out[it] = mutableListOf() }
        for (from in nodes) {
            for (to in nodes) {
                if (from.priceCents < to.priceCents) {
                    out.getValue(from) += PlannerEdge.Standard(from, to)
                }
            }
        }
        inventoryCcus.forEach { candidate ->
            val summary = candidate.summary
            val from = nodes.firstOrNull {
                it.priceCents == summary.fromShipPriceCents &&
                    WbccuChainValidator.normalizeShipName(it.name) ==
                    WbccuChainValidator.normalizeShipName(summary.fromShipName)
            }
            val to = nodes.firstOrNull {
                it.priceCents == summary.toShipPriceCents &&
                    WbccuChainValidator.normalizeShipName(it.name) ==
                    WbccuChainValidator.normalizeShipName(summary.toShipName)
            }
            if (from != null && to != null && from.priceCents < to.priceCents) {
                out.getValue(from) += PlannerEdge.Inventory(from, to, candidate)
            }
        }
        return out
    }

    private fun shortestPath(
        start: NodeKey,
        target: NodeKey,
        edges: Map<NodeKey, List<PlannerEdge>>
    ): List<PlannerEdge> {
        val startState = Score(missingCashCents = 0, ownedValueCents = 0, steps = 0)
        val best = mutableMapOf(start to startState)
        val previous = mutableMapOf<NodeKey, Pair<NodeKey, PlannerEdge>>()
        val queue = PriorityQueue<QueueItem>(compareBy<QueueItem> { it.score.missingCashCents }
            .thenByDescending { it.score.ownedValueCents }
            .thenBy { it.score.steps })
        queue += QueueItem(start, startState)
        while (queue.isNotEmpty()) {
            val item = queue.remove()
            if (best[item.node] != item.score) continue
            if (item.node == target) break
            edges[item.node].orEmpty().forEach { edge ->
                val next = edge.to
                val nextScore = item.score + edge
                val current = best[next]
                if (current == null || nextScore.isBetterThan(current)) {
                    best[next] = nextScore
                    previous[next] = item.node to edge
                    queue += QueueItem(next, nextScore)
                }
            }
        }
        val path = ArrayDeque<PlannerEdge>()
        var cursor = target
        while (cursor != start) {
            val entry = previous[cursor] ?: return emptyList()
            path.addFirst(entry.second)
            cursor = entry.first
        }
        return path.toList()
    }

    private data class NodeKey(
        val name: String,
        val priceCents: Int
    )

    private data class Score(
        val missingCashCents: Int,
        val ownedValueCents: Int,
        val steps: Int
    ) {
        operator fun plus(edge: PlannerEdge): Score {
            return Score(
                missingCashCents = missingCashCents + edge.missingCashCents,
                ownedValueCents = ownedValueCents + edge.ownedValueCents,
                steps = steps + 1
            )
        }

        fun isBetterThan(other: Score): Boolean {
            return when {
                missingCashCents != other.missingCashCents -> missingCashCents < other.missingCashCents
                ownedValueCents != other.ownedValueCents -> ownedValueCents > other.ownedValueCents
                else -> steps < other.steps
            }
        }
    }

    private data class QueueItem(
        val node: NodeKey,
        val score: Score
    )

    private sealed class PlannerEdge(
        val from: NodeKey,
        val to: NodeKey,
        val missingCashCents: Int,
        val ownedValueCents: Int
    ) {
        class Standard(from: NodeKey, to: NodeKey) :
            PlannerEdge(from, to, to.priceCents - from.priceCents, 0)

        class Inventory(
            from: NodeKey,
            to: NodeKey,
            val candidate: WbccuInventoryCcuCandidate
        ) : PlannerEdge(from, to, 0, to.priceCents - from.priceCents)
    }

    private fun PlannerEdge.Standard.toStep(index: Int): WbccuChainStep {
        return WbccuChainStep(
            id = WbccuChainCodec.newId("std"),
            fromShipName = from.name,
            toShipName = to.name,
            fromShipPriceCents = from.priceCents,
            toShipPriceCents = to.priceCents,
            paidCents = to.priceCents - from.priceCents,
            saleType = WbccuSaleType.STANDARD,
            source = WbccuChainStepSource.MANUAL,
            note = "自动补标准 CCU #${index + 1}"
        )
    }

    private fun PlannerEdge.Inventory.toStep(index: Int): WbccuChainStep {
        return WbccuChainStep(
            id = WbccuChainCodec.newId("inv"),
            fromShipName = candidate.summary.fromShipName,
            toShipName = candidate.summary.toShipName,
            fromShipPriceCents = candidate.summary.fromShipPriceCents,
            toShipPriceCents = candidate.summary.toShipPriceCents,
            paidCents = candidate.item.priceCents,
            saleType = candidate.saleType,
            source = WbccuChainStepSource.INVENTORY,
            inventoryItemId = candidate.item.id,
            note = "自动采用库存 CCU #${index + 1}：${candidate.item.name}"
        )
    }
}
