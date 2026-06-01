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
