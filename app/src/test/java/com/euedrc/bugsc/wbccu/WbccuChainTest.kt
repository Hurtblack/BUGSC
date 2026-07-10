package com.euedrc.bugsc.wbccu

import com.euedrc.bugsc.InventoryShipPrice
import com.euedrc.bugsc.InventoryCcuInfo
import com.euedrc.bugsc.InventoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WbccuChainTest {

    @Test
    fun validatesDescendingManualStepAsError() {
        val chain = WbccuChain(
            id = "chain-1",
            name = "Bad chain",
            steps = listOf(
                step(
                    from = "Railen",
                    to = "Ballista",
                    fromPrice = 40000,
                    toPrice = 14000,
                    paid = 500,
                    source = WbccuChainStepSource.MANUAL
                )
            )
        )

        val validation = WbccuChainValidator.validate(chain)

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("价格顺序错误") })
    }

    @Test
    fun validatesBrokenContinuityAsError() {
        val chain = WbccuChain(
            id = "chain-1",
            name = "Broken chain",
            steps = listOf(
                step(from = "Cutter Scout", to = "325a", fromPrice = 5000, toPrice = 7000, paid = 2000),
                step(from = "Spartan", to = "Herald", fromPrice = 8000, toPrice = 8500, paid = 500)
            )
        )

        val validation = WbccuChainValidator.validate(chain)

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("不连续") })
    }

    @Test
    fun summarizesOwnedInventorySeparatelyFromMissingCash() {
        val chain = WbccuChain(
            id = "chain-1",
            name = "Perseus",
            steps = listOf(
                WbccuChainStep(
                    id = "base",
                    fromShipName = "",
                    toShipName = "Cutter Scout",
                    fromShipPriceCents = 0,
                    toShipPriceCents = 5000,
                    paidCents = 4500,
                    saleType = WbccuSaleType.BASE,
                    source = WbccuChainStepSource.BASE
                ),
                step(
                    from = "325a",
                    to = "Spartan",
                    fromPrice = 7000,
                    toPrice = 8000,
                    paid = 1000,
                    source = WbccuChainStepSource.MANUAL
                ),
                step(
                    from = "Spartan",
                    to = "Herald",
                    fromPrice = 8000,
                    toPrice = 8500,
                    paid = 500,
                    source = WbccuChainStepSource.INVENTORY,
                    inventoryId = "pledge-1"
                )
            )
        )

        val summary = WbccuChainValidator.summarize(chain)

        assertEquals(6000, summary.totalPaidCents)
        assertEquals(1500, summary.ccuPaidCents)
        assertEquals(1000, summary.missingCashCents)
        assertEquals(500, summary.ownedInventoryPaidCents)
        assertEquals(8500, summary.finalShipValueCents)
    }

    @Test
    fun summarizesManualOwnedSeparatelyFromMissingCash() {
        val chain = WbccuChain(
            id = "chain-1",
            name = "Manual owned",
            steps = listOf(
                step(
                    from = "Tyilui",
                    to = "Prowler",
                    fromPrice = 42500,
                    toPrice = 44000,
                    paid = 1500,
                    source = WbccuChainStepSource.MANUAL_OWNED
                ),
                step(
                    from = "Prowler",
                    to = "600i Explorer",
                    fromPrice = 44000,
                    toPrice = 47500,
                    paid = 3500,
                    source = WbccuChainStepSource.MANUAL
                )
            )
        )

        val summary = WbccuChainValidator.summarize(chain)

        assertEquals(5000, summary.ccuPaidCents)
        assertEquals(3500, summary.missingCashCents)
        assertEquals(1500, summary.ownedManualPaidCents)
    }

    @Test
    fun shareCodeStripsOwnedInventorySource() {
        val chain = WbccuChain(
            id = "chain-1",
            name = "Share",
            steps = listOf(
                step(
                    from = "Prowler",
                    to = "600i Explorer",
                    fromPrice = 44000,
                    toPrice = 47500,
                    paid = 500,
                    source = WbccuChainStepSource.INVENTORY,
                    inventoryId = "private-inventory-id"
                )
            )
        )

        val decoded = WbccuChainCodec.decodeShare(WbccuChainCodec.encodeShare(chain))

        require(decoded is WbccuChainDecodeResult.Success)
        val importedStep = decoded.chain.steps.single()
        assertEquals(WbccuChainStepSource.IMPORTED, importedStep.source)
        assertEquals("", importedStep.inventoryItemId)
    }

    @Test
    fun localCodecRoundTripsInventorySource() {
        val chain = WbccuChain(
            id = "chain-1",
            name = "Local",
            steps = listOf(
                step(
                    from = "Starlancer TAC",
                    to = "Tyilui",
                    fromPrice = 37500,
                    toPrice = 42500,
                    paid = 1000,
                    source = WbccuChainStepSource.CURRENT_WB
                ),
                step(
                    from = "Tyilui",
                    to = "Prowler",
                    fromPrice = 42500,
                    toPrice = 44000,
                    paid = 1500,
                    source = WbccuChainStepSource.MANUAL
                )
            )
        )

        val decoded = WbccuChainCodec.decodeChains(WbccuChainCodec.encodeChains(listOf(chain))).single()

        assertEquals(chain, decoded)
    }

    @Test
    fun shipMatcherFindsEnglishKeywordAndReturnsCanonicalPrice() {
        val match = WbccuShipMatcher.match(
            query = "tac",
            shipPrices = listOf(
                InventoryShipPrice(279, "Starlancer TAC", 37500),
                InventoryShipPrice(320, "Tyilui", 42500)
            ),
            shipAliases = emptyMap()
        )

        requireNotNull(match)
        assertEquals("Starlancer TAC", match.name)
        assertEquals(37500, match.priceCents)
    }

    @Test
    fun shipMatcherFindsChineseAliasKeywordAndKeepsCanonicalName() {
        val match = WbccuShipMatcher.match(
            query = "潜行",
            shipPrices = listOf(
                InventoryShipPrice(80, "Prowler", 44000),
                InventoryShipPrice(320, "Tyilui", 42500)
            ),
            shipAliases = mapOf("Prowler" to "潜行者")
        )

        requireNotNull(match)
        assertEquals("Prowler", match.name)
        assertEquals("潜行者", match.displayName)
        assertEquals(44000, match.priceCents)
    }

    @Test
    fun inventoryMapperPullsCachedCcusWithWarbondFirst() {
        val items = listOf(
            inventoryCcu(
                id = "standard",
                name = "Upgrade - Tyilui to Prowler Standard Edition",
                from = "Tyilui",
                to = "Prowler",
                paid = 1500
            ),
            inventoryCcu(
                id = "warbond",
                name = "Upgrade - Starlancer TAC to Tyilui Warbond Edition",
                from = "Starlancer TAC",
                to = "Tyilui",
                paid = 1000
            ),
            inventoryCcu(
                id = "ship",
                name = "Standalone Ship - Cutter Scout",
                from = "",
                to = "",
                paid = 4500,
                isCcu = false
            )
        )

        val candidates = WbccuInventoryCcuMapper.candidates(
            items = items,
            shipPrices = listOf(
                InventoryShipPrice(279, "Starlancer TAC", 37500),
                InventoryShipPrice(320, "Tyilui", 42500),
                InventoryShipPrice(80, "Prowler", 44000)
            )
        )

        assertEquals(2, candidates.size)
        assertEquals("warbond", candidates.first().item.id)
        assertEquals(WbccuSaleType.WARBOND, candidates.first().saleType)
        assertEquals("standard", candidates[1].item.id)
        assertEquals(WbccuSaleType.STANDARD, candidates[1].saleType)
    }

    @Test
    fun inventoryExporterIncludesFullCcuFields() {
        val candidates = WbccuInventoryCcuMapper.candidates(
            items = listOf(
                inventoryCcu(
                    id = "warbond",
                    name = "Upgrade - Starlancer TAC to Tyilui Warbond Edition",
                    from = "Starlancer TAC",
                    to = "Tyilui",
                    paid = 500
                )
            ),
            shipPrices = listOf(
                InventoryShipPrice(279, "Starlancer TAC", 37500),
                InventoryShipPrice(320, "Tyilui", 42500)
            )
        )

        val text = WbccuInventoryCcuExporter.exportText(candidates)

        assertTrue(text.contains("库存 CCU 1 张，其中 WB 1 张"))
        assertTrue(text.contains("Starlancer TAC -> Tyilui"))
        assertTrue(text.contains("WB"))
        assertTrue(text.contains("实付 $5"))
        assertTrue(text.contains("船价 $375 -> $425"))
        assertTrue(text.contains("标准差价 $50"))
        assertTrue(text.contains("省 $45"))
        assertTrue(text.contains("warbond"))
    }

    @Test
    fun plannerUsesOwnedInventoryCcusAndFillsStandardGaps() {
        val candidates = WbccuInventoryCcuMapper.candidates(
            items = listOf(
                inventoryCcu(
                    id = "owned-b-c",
                    name = "Upgrade - Ship B to Ship C Warbond Edition",
                    from = "Ship B",
                    to = "Ship C",
                    paid = 500
                ),
                inventoryCcu(
                    id = "owned-d-e",
                    name = "Upgrade - Ship D to Ship E Standard Edition",
                    from = "Ship D",
                    to = "Ship E",
                    paid = 1000
                )
            ),
            shipPrices = plannerShipPrices()
        )

        val result = WbccuChainPlanner.plan(
            WbccuChainPlanRequest(
                startShipName = "Ship A",
                startShipPriceCents = 10000,
                targetShipName = "Ship E",
                targetShipPriceCents = 50000,
                inventoryCcus = candidates
            )
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(
            listOf(
                WbccuChainStepSource.BASE,
                WbccuChainStepSource.MANUAL,
                WbccuChainStepSource.INVENTORY,
                WbccuChainStepSource.MANUAL,
                WbccuChainStepSource.INVENTORY
            ),
            result.steps.map { it.source }
        )
        assertEquals(listOf("Ship A", "Ship B", "Ship C", "Ship D", "Ship E"), result.steps.map { it.toShipName })
        assertEquals(20000, WbccuChainValidator.summarize(WbccuChain(id = "p", name = "p", steps = result.steps)).missingCashCents)
    }

    @Test
    fun plannerFallsBackToDirectStandardCcuWhenInventoryCannotHelp() {
        val candidates = WbccuInventoryCcuMapper.candidates(
            items = listOf(
                inventoryCcu(
                    id = "outside",
                    name = "Upgrade - Ship E to Ship F Warbond Edition",
                    from = "Ship E",
                    to = "Ship F",
                    paid = 500
                )
            ),
            shipPrices = plannerShipPrices()
        )

        val result = WbccuChainPlanner.plan(
            WbccuChainPlanRequest(
                startShipName = "Ship A",
                startShipPriceCents = 10000,
                targetShipName = "Ship D",
                targetShipPriceCents = 40000,
                inventoryCcus = candidates
            )
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(listOf(WbccuChainStepSource.BASE, WbccuChainStepSource.MANUAL), result.steps.map { it.source })
        assertEquals("Ship A", result.steps[1].fromShipName)
        assertEquals("Ship D", result.steps[1].toShipName)
        assertEquals(30000, result.steps[1].paidCents)
    }

    @Test
    fun plannerRejectsTargetBelowStartShip() {
        val result = WbccuChainPlanner.plan(
            WbccuChainPlanRequest(
                startShipName = "Ship D",
                startShipPriceCents = 40000,
                targetShipName = "Ship A",
                targetShipPriceCents = 10000,
                inventoryCcus = emptyList()
            )
        )

        assertTrue(result.steps.isEmpty())
        assertTrue(result.errors.any { it.contains("目标飞船价格必须高于起始飞船") })
    }

    private fun step(
        from: String,
        to: String,
        fromPrice: Int,
        toPrice: Int,
        paid: Int,
        source: WbccuChainStepSource = WbccuChainStepSource.MANUAL,
        inventoryId: String = ""
    ): WbccuChainStep {
        return WbccuChainStep(
            id = "step-$from-$to",
            fromShipName = from,
            toShipName = to,
            fromShipPriceCents = fromPrice,
            toShipPriceCents = toPrice,
            paidCents = paid,
            saleType = if (source == WbccuChainStepSource.INVENTORY) WbccuSaleType.WARBOND else WbccuSaleType.STANDARD,
            source = source,
            inventoryItemId = inventoryId
        )
    }

    private fun inventoryCcu(
        id: String,
        name: String,
        from: String,
        to: String,
        paid: Int,
        isCcu: Boolean = true
    ): InventoryItem {
        return InventoryItem(
            id = id,
            name = name,
            priceCents = paid,
            currentPriceCents = paid,
            status = "Attributed",
            date = "",
            insurance = "",
            contains = name,
            imageUrl = "",
            page = 1,
            canGift = true,
            canReclaim = true,
            canUpgrade = isCcu,
            ccuInfo = if (isCcu) InventoryCcuInfo(from, to) else null
        )
    }

    private fun plannerShipPrices(): List<InventoryShipPrice> {
        return listOf(
            InventoryShipPrice(1, "Ship A", 10000),
            InventoryShipPrice(2, "Ship B", 20000),
            InventoryShipPrice(3, "Ship C", 30000),
            InventoryShipPrice(4, "Ship D", 40000),
            InventoryShipPrice(5, "Ship E", 50000),
            InventoryShipPrice(6, "Ship F", 60000)
        )
    }
}
