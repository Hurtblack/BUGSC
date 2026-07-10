package com.euedrc.bugsc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryParserTest {

    @Test
    fun parsesHangarRowsFromRsiPledgeHtml() {
        val html = """
            <html>
              <body>
                <div class="list-items">
                  <div class="row">
                    <input class="js-pledge-id" value="12345" />
                    <input class="js-pledge-value" value="${'$'}75.00 USD" />
                    <input class="js-pledge-name" value="Avenger Titan Starter Pack" />
                    <div class="image" style="background-image:url('/media/titan.jpg');"></div>
                    <div class="availability">Attributed</div>
                    <div class="date-col">Created:
                      January 02, 2026
                    </div>
                    <button class="shadow-button js-gift"></button>
                    <button class="shadow-button js-reclaim"></button>
                    <div class="title">Avenger Titan</div>
                    <div class="title">120 Month Insurance</div>
                    <div class="with-images">
                      <div class="item">
                        <div class="image" style="background-image:url('/media/sub.jpg');"></div>
                        <div class="title">Avenger Titan</div>
                        <div class="kind">Ship</div>
                      </div>
                    </div>
                  </div>
                </div>
              </body>
            </html>
        """.trimIndent()

        val items = InventoryParser.parseHangarItems(html, 2)

        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("12345", item.id)
        assertEquals("Avenger Titan Starter Pack", item.name)
        assertEquals(7500, item.priceCents)
        assertEquals("Attributed", item.status)
        assertEquals("2026年01月02日", item.date)
        assertEquals("10Y", item.insurance)
        assertEquals(2, item.page)
        assertTrue(item.canGift)
        assertTrue(item.canReclaim)
        assertEquals("Avenger Titan#120 Month Insurance", item.contains)
        assertEquals("https://robertsspaceindustries.com/media/titan.jpg", item.imageUrl)
        assertEquals(1, item.subItems.size)
        assertEquals("Avenger Titan", item.subItems.first().title)
        assertEquals("Ship", item.subItems.first().kind)
        assertEquals("https://robertsspaceindustries.com/media/sub.jpg", item.subItems.first().imageUrl)
    }

    @Test
    fun parsesUpgradeDataFromHangarRow() {
        val upgradeJson = "{&quot;id&quot;:1,&quot;match_items&quot;:[{&quot;id&quot;:4,&quot;name&quot;:&quot;Aurora MR&quot;}],&quot;target_items&quot;:[{&quot;id&quot;:102,&quot;name&quot;:&quot;Avenger Titan&quot;}]}"
        val html = """
            <html>
              <body>
                <article class="row">
                  <input class="js-pledge-id" value="86420" />
                  <input class="js-pledge-value" value="${'$'}15.00 USD" />
                  <input class="js-pledge-name" value="Upgrade - Aurora MR to Avenger Titan Standard Edition" />
                  <input class="js-upgrade-data" value="$upgradeJson" />
                  <span class="image" style="background-image:url('/media/ccu.jpg');"></span>
                  <span class="availability">Attributed</span>
                  <span class="date-col">Created: April 05, 2026</span>
                  <span class="title">Upgrade - Aurora MR to Avenger Titan Standard Edition</span>
                </article>
              </body>
            </html>
        """.trimIndent()

        val item = InventoryParser.parseHangarItems(html, 1).first()

        assertTrue(item.canUpgrade)
        assertEquals("""{"id":1,"match_items":[{"id":4,"name":"Aurora MR"}],"target_items":[{"id":102,"name":"Avenger Titan"}]}""", item.upgradeData)
        assertEquals("Aurora MR", item.ccuInfo?.fromShipName)
        assertEquals("Avenger Titan", item.ccuInfo?.toShipName)
        assertEquals(4, item.ccuInfo?.fromShipId)
        assertEquals(102, item.ccuInfo?.toShipId)
    }

    @Test
    fun parsesCreatedDateWhenDateColumnContainsNestedTags() {
        val html = """
            <html>
              <body>
                <article class="row">
                  <input class="js-pledge-id" value="97531" />
                  <input class="js-pledge-value" value="${'$'}5.00 USD" />
                  <input class="js-pledge-name" value="Poster" />
                  <span class="availability">Attributed</span>
                  <span class="date-col"><span>Created:</span><strong>May 06, 2026</strong></span>
                  <span class="title">Poster</span>
                </article>
              </body>
            </html>
        """.trimIndent()

        val item = InventoryParser.parseHangarItems(html, 1).first()

        assertEquals("2026年05月06日", item.date)
    }

    @Test
    fun calculatesCcuPriceSummaryFromShipPricesAndMeltValue() {
        val item = InventoryItem(
            id = "86420",
            name = "Upgrade - Aurora MR to Avenger Titan Standard Edition",
            priceCents = 1500,
            currentPriceCents = 1500,
            status = "Attributed",
            date = "",
            insurance = "",
            contains = "",
            imageUrl = "",
            page = 1,
            canGift = true,
            canReclaim = true,
            canUpgrade = true,
            ccuInfo = InventoryCcuInfo("Aurora MR", "Avenger Titan")
        )

        val summary = InventoryCcuPriceCalculator.summarize(
            item = item,
            ccuInfo = item.ccuInfo!!,
            shipPrices = mapOf(
                "Aurora Mk I MR" to 3000,
                "Avenger Titan" to 6000
            )
        )

        requireNotNull(summary)
        assertEquals(3000, summary.fromShipPriceCents)
        assertEquals(6000, summary.toShipPriceCents)
        assertEquals(3000, summary.standardUpgradeValueCents)
        assertEquals(1500, summary.savingCents)
    }

    @Test
    fun calculatesCcuPriceSummaryWhenHerculesTargetIncludesStarlifterSuffix() {
        val item = InventoryItem(
            id = "24680",
            name = "Upgrade - Paladin to C2 Hercules Starlifter Standard Edition",
            priceCents = 5000,
            currentPriceCents = 5000,
            status = "Attributed",
            date = "",
            insurance = "",
            contains = "",
            imageUrl = "",
            page = 1,
            canGift = true,
            canReclaim = true,
            canUpgrade = true,
            ccuInfo = InventoryCcuInfo("Paladin", "C2 Hercules Starlifter")
        )

        val summary = InventoryCcuPriceCalculator.summarize(
            item = item,
            ccuInfo = item.ccuInfo!!,
            shipPrices = mapOf(
                "Paladin" to 35000,
                "C2 Hercules" to 40000
            )
        )

        requireNotNull(summary)
        assertEquals(35000, summary.fromShipPriceCents)
        assertEquals(40000, summary.toShipPriceCents)
        assertEquals(0, summary.savingCents)
    }

    @Test
    fun calculatesCcuPriceSummaryWhenHerculesTargetUsesWbReversedName() {
        val item = InventoryItem(
            id = "24681",
            name = "Upgrade - Paladin to Hercules Starlifter C2 Standard Edition",
            priceCents = 5000,
            currentPriceCents = 5000,
            status = "Attributed",
            date = "",
            insurance = "",
            contains = "",
            imageUrl = "",
            page = 1,
            canGift = true,
            canReclaim = true,
            canUpgrade = true,
            ccuInfo = InventoryCcuInfo("Paladin", "Hercules Starlifter C2")
        )

        val summary = InventoryCcuPriceCalculator.summarize(
            item = item,
            ccuInfo = item.ccuInfo!!,
            shipPrices = mapOf(
                "Paladin" to 35000,
                "C2 Hercules" to 40000
            )
        )

        requireNotNull(summary)
        assertEquals(35000, summary.fromShipPriceCents)
        assertEquals(40000, summary.toShipPriceCents)
        assertEquals(0, summary.savingCents)
    }

    @Test
    fun calculatesCcuPriceSummaryWithChineseAliasesWithoutBlankNameCollision() {
        val item = InventoryItem(
            id = "24682",
            name = "Upgrade - 圣骑士 to 大力神 C2 Standard Edition",
            priceCents = 5000,
            currentPriceCents = 5000,
            status = "Attributed",
            date = "",
            insurance = "",
            contains = "",
            imageUrl = "",
            page = 1,
            canGift = true,
            canReclaim = true,
            canUpgrade = true,
            ccuInfo = InventoryCcuInfo("圣骑士", "大力神 C2")
        )

        val summary = InventoryCcuPriceCalculator.summarize(
            item = item,
            ccuInfo = item.ccuInfo!!,
            shipPrices = listOf(
                InventoryShipPrice(null, "圣骑士", 35000),
                InventoryShipPrice(null, "徘徊者", 44000),
                InventoryShipPrice(null, "大力神 C2", 40000)
            )
        )

        requireNotNull(summary)
        assertEquals(35000, summary.fromShipPriceCents)
        assertEquals(40000, summary.toShipPriceCents)
        assertEquals(0, summary.savingCents)
    }

    @Test
    fun expandsShipPricesWithChineseAliasesUsingNormalizedEnglishNames() {
        val prices = InventoryShipPriceAliases.expand(
            prices = listOf(
                InventoryShipPrice(282, "Paladin", 35000),
                InventoryShipPrice(162, "C2 Hercules", 40000)
            ),
            aliases = mapOf(
                "Paladin" to "圣骑士",
                "C2 Hercules Starlifter" to "大力神 C2"
            )
        )

        assertEquals(35000, prices.first { it.name == "圣骑士" }.priceCents)
        assertEquals(40000, prices.first { it.name == "大力神 C2" }.priceCents)
        assertEquals(null, prices.first { it.name == "圣骑士" }.id)
        assertEquals(null, prices.first { it.name == "大力神 C2" }.id)
    }

    @Test
    fun calculatesCcuPriceSummaryWhenNovaIncludesTankSuffix() {
        val item = InventoryItem(
            id = "13579",
            name = "Upgrade - Nova Tank to RAFT Standard Edition",
            priceCents = 7000,
            currentPriceCents = 7000,
            status = "Attributed",
            date = "",
            insurance = "",
            contains = "",
            imageUrl = "",
            page = 1,
            canGift = true,
            canReclaim = true,
            canUpgrade = true,
            ccuInfo = InventoryCcuInfo("Nova Tank", "RAFT")
        )

        val summary = InventoryCcuPriceCalculator.summarize(
            item = item,
            ccuInfo = item.ccuInfo!!,
            shipPrices = mapOf(
                "Nova" to 12000,
                "RAFT" to 19000
            )
        )

        requireNotNull(summary)
        assertEquals(12000, summary.fromShipPriceCents)
        assertEquals(19000, summary.toShipPriceCents)
        assertEquals(0, summary.savingCents)
    }

    @Test
    fun calculatesCcuPriceSummaryByShipIdsWhenTargetNameIsAmbiguous() {
        val item = InventoryItem(
            id = "112233",
            name = "Upgrade - Prowler to 600i Standard Edition",
            priceCents = 3500,
            currentPriceCents = 3500,
            status = "Attributed",
            date = "",
            insurance = "",
            contains = "",
            imageUrl = "",
            page = 1,
            canGift = true,
            canReclaim = true,
            canUpgrade = true,
            ccuInfo = InventoryCcuInfo(
                fromShipName = "Prowler",
                toShipName = "600i",
                fromShipId = 117,
                toShipId = 141
            )
        )

        val summary = InventoryCcuPriceCalculator.summarize(
            item = item,
            ccuInfo = item.ccuInfo!!,
            shipPrices = listOf(
                InventoryShipPrice(117, "Prowler", 44000),
                InventoryShipPrice(140, "600i Touring", 43500),
                InventoryShipPrice(141, "600i Explorer", 47500)
            )
        )

        requireNotNull(summary)
        assertEquals(44000, summary.fromShipPriceCents)
        assertEquals(47500, summary.toShipPriceCents)
        assertEquals(0, summary.savingCents)
    }

    @Test
    fun fallsBackToShipNameWhenUpgradeDataIdDoesNotMatchShipName() {
        val item = InventoryItem(
            id = "445566",
            name = "Upgrade - Carrack to 600i Explorer Standard Edition",
            priceCents = 16500,
            currentPriceCents = 16500,
            status = "Attributed",
            date = "",
            insurance = "",
            contains = "",
            imageUrl = "",
            page = 1,
            canGift = true,
            canReclaim = true,
            canUpgrade = true,
            ccuInfo = InventoryCcuInfo(
                fromShipName = "Carrack",
                toShipName = "600i Explorer",
                fromShipId = 64,
                toShipId = 141
            )
        )

        val summary = InventoryCcuPriceCalculator.summarize(
            item = item,
            ccuInfo = item.ccuInfo!!,
            shipPrices = listOf(
                InventoryShipPrice(62, "Carrack", 60000),
                InventoryShipPrice(64, "Gladiator", 16500),
                InventoryShipPrice(141, "600i Explorer", 47500)
            )
        )

        requireNotNull(summary)
        assertEquals(60000, summary.fromShipPriceCents)
        assertEquals(47500, summary.toShipPriceCents)
    }

    @Test
    fun parsesHangarRowsWhenRsiUsesNonDivContainers() {
        val html = """
            <html>
              <body>
                <section class="list-items">
                  <article class="row">
                    <input class="js-pledge-id" value="67890" />
                    <input class="js-pledge-value" value="${'$'}10.00 USD" />
                    <input class="js-pledge-name" value="Paint Pack" />
                    <div class="image" style="background-image:url('/media/paint.jpg');"></div>
                    <span class="availability">Attributed</span>
                    <span class="date-col">Created: February 03, 2026</span>
                    <span class="title">Paint Pack</span>
                  </article>
                </section>
              </body>
            </html>
        """.trimIndent()

        val items = InventoryParser.parseHangarItems(html, 1)

        assertEquals(1, items.size)
        assertEquals("67890", items.first().id)
        assertEquals("Paint Pack", items.first().name)
    }

    @Test
    fun parsesImageFromNonDivImageContainer() {
        val html = """
            <html>
              <body>
                <article class="row">
                  <input class="js-pledge-id" value="13579" />
                  <input class="js-pledge-value" value="${'$'}5.00 USD" />
                  <input class="js-pledge-name" value="Poster" />
                  <span class="image" style="background-image:url('/media/poster.jpg');"></span>
                  <span class="availability">Attributed</span>
                  <span class="date-col">Created: March 04, 2026</span>
                  <span class="title">Poster</span>
                </article>
              </body>
            </html>
        """.trimIndent()

        val item = InventoryParser.parseHangarItems(html, 1).first()

        assertEquals("https://robertsspaceindustries.com/media/poster.jpg", item.imageUrl)
    }

    @Test
    fun parsesImageFromImgSrcFallback() {
        val html = """
            <html>
              <body>
                <article class="row">
                  <input class="js-pledge-id" value="24680" />
                  <input class="js-pledge-value" value="${'$'}5.00 USD" />
                  <input class="js-pledge-name" value="Poster" />
                  <img class="image" src="/media/poster-src.jpg" />
                  <span class="availability">Attributed</span>
                  <span class="date-col">Created: March 04, 2026</span>
                  <span class="title">Poster</span>
                </article>
              </body>
            </html>
        """.trimIndent()

        val item = InventoryParser.parseHangarItems(html, 1).first()

        assertEquals("https://robertsspaceindustries.com/media/poster-src.jpg", item.imageUrl)
    }

    @Test
    fun parsesImageFromAnyBackgroundImageInRow() {
        val html = """
            <html>
              <body>
                <article class="row">
                  <input class="js-pledge-id" value="11223" />
                  <input class="js-pledge-value" value="${'$'}5.00 USD" />
                  <input class="js-pledge-name" value="Poster" />
                  <span class="thumb" data-extra="x" style="background-image: url('/media/fallback.jpg');"></span>
                  <span class="availability">Attributed</span>
                  <span class="date-col">Created: March 04, 2026</span>
                  <span class="title">Poster</span>
                </article>
              </body>
            </html>
        """.trimIndent()

        val item = InventoryParser.parseHangarItems(html, 1).first()

        assertEquals("https://robertsspaceindustries.com/media/fallback.jpg", item.imageUrl)
    }
}
