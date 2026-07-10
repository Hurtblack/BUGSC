package com.euedrc.bugsc.wbccu

import java.util.Locale
import kotlin.math.max

data class WbccuChain(
    val id: String,
    val name: String,
    val steps: List<WbccuChainStep>,
    val updatedAtMillis: Long = 0L
)

data class WbccuChainStep(
    val id: String,
    val fromShipName: String,
    val toShipName: String,
    val fromShipPriceCents: Int,
    val toShipPriceCents: Int,
    val paidCents: Int,
    val saleType: WbccuSaleType,
    val source: WbccuChainStepSource,
    val inventoryItemId: String = "",
    val note: String = ""
) {
    val isBase: Boolean get() = source == WbccuChainStepSource.BASE
    val isOwnedInventory: Boolean get() = source == WbccuChainStepSource.INVENTORY
    val isOwnedManual: Boolean get() = source == WbccuChainStepSource.MANUAL_OWNED
    val isOwned: Boolean get() = isOwnedInventory || isOwnedManual
    val standardUpgradeCents: Int get() = max(0, toShipPriceCents - fromShipPriceCents)
    val savingCents: Int get() = standardUpgradeCents - paidCents
}

enum class WbccuSaleType {
    BASE,
    WARBOND,
    STANDARD,
    UNKNOWN
}

enum class WbccuChainStepSource {
    BASE,
    INVENTORY,
    MANUAL_OWNED,
    MANUAL,
    WAITING_WB,
    CURRENT_WB,
    IMPORTED
}

data class WbccuChainSummary(
    val totalPaidCents: Int,
    val ccuPaidCents: Int,
    val missingCashCents: Int,
    val ownedInventoryPaidCents: Int,
    val ownedManualPaidCents: Int,
    val finalShipValueCents: Int,
    val standardUpgradeValueCents: Int,
    val savingCents: Int
)

data class WbccuChainValidation(
    val errors: List<String>,
    val warnings: List<String>
) {
    val isValid: Boolean get() = errors.isEmpty()
}

object WbccuChainValidator {

    fun validate(chain: WbccuChain): WbccuChainValidation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (chain.steps.isEmpty()) {
            warnings += "链路为空"
        }

        chain.steps.forEachIndexed { index, step ->
            validateStep(index, step, errors, warnings)
            val previous = chain.steps.getOrNull(index - 1)
            if (previous != null && !step.isBase) {
                val previousTo = normalizeShipName(previous.toShipName)
                val currentFrom = normalizeShipName(step.fromShipName)
                if (previousTo.isNotBlank() && currentFrom.isNotBlank() && previousTo != currentFrom) {
                    errors += "第 ${index + 1} 段不连续：上一段到 ${previous.toShipName}，本段从 ${step.fromShipName} 开始"
                }
            }
        }

        return WbccuChainValidation(errors = errors, warnings = warnings)
    }

    fun summarize(chain: WbccuChain): WbccuChainSummary {
        val ccuSteps = chain.steps.filterNot { it.isBase }
        val totalPaid = chain.steps.sumOf { it.paidCents }
        val ccuPaid = ccuSteps.sumOf { it.paidCents }
        val missingCash = chain.steps
            .filterNot { it.isBase || it.isOwned }
            .sumOf { it.paidCents }
        val ownedInventoryPaid = chain.steps
            .filter { it.isOwnedInventory }
            .sumOf { it.paidCents }
        val ownedManualPaid = chain.steps
            .filter { it.isOwnedManual }
            .sumOf { it.paidCents }
        val standardUpgradeValue = ccuSteps.sumOf { it.standardUpgradeCents }
        return WbccuChainSummary(
            totalPaidCents = totalPaid,
            ccuPaidCents = ccuPaid,
            missingCashCents = missingCash,
            ownedInventoryPaidCents = ownedInventoryPaid,
            ownedManualPaidCents = ownedManualPaid,
            finalShipValueCents = chain.steps.lastOrNull()?.toShipPriceCents ?: 0,
            standardUpgradeValueCents = standardUpgradeValue,
            savingCents = standardUpgradeValue - ccuPaid
        )
    }

    fun normalizeShipName(name: String): String {
        return name.lowercase(Locale.US)
            .replace("&", " and ")
            .replace(Regex("""\b(anvil|crusader|aegis|drake|rsi|misc|origin|esperia|gatac|argo|tumbril|greycat|kruger)\b"""), " ")
            .replace(Regex("""\bhercules(?:\s+starlifter)?\s+([acm]2)\b"""), "$1 hercules")
            .replace(Regex("""\bmk\s+i\b"""), "")
            .replace(Regex("""\b(mk|mark)\s+([ivx]+|\d+)\b"""), "$2")
            .replace(Regex("""\bstarlifter\b"""), " ")
            .replace(Regex("""\btank\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private fun validateStep(
        index: Int,
        step: WbccuChainStep,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val label = "第 ${index + 1} 段"
        if (step.toShipName.isBlank()) {
            errors += "$label 目标船不能为空"
        }
        if (step.paidCents < 0) {
            errors += "$label 实付不能为负数"
        }
        if (step.isBase) {
            if (step.paidCents < 0) errors += "$label 起始飞船实付不能为负数"
            return
        }
        if (step.fromShipName.isBlank()) {
            errors += "$label 起始船不能为空"
        }
        if (step.fromShipPriceCents <= 0 || step.toShipPriceCents <= 0) {
            errors += "$label 船价必须大于 0"
        }
        if (step.fromShipPriceCents >= step.toShipPriceCents) {
            errors += "$label 价格顺序错误：${step.fromShipName} 不能高于或等于 ${step.toShipName}"
        }
        if (step.paidCents <= 0) {
            errors += "$label CCU 实付必须大于 0"
        }
        if (step.source != WbccuChainStepSource.INVENTORY && step.inventoryItemId.isNotBlank()) {
            warnings += "$label 不是库存节点，但带有库存物品 ID，请确认来源"
        }
        if (step.paidCents > step.standardUpgradeCents && step.standardUpgradeCents > 0) {
            warnings += "$label 实付高于标准差价，请确认价格"
        }
    }
}
