package com.euedrc.bugsc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RsiInventoryClientTest {

    @Test
    fun extractsMaxLinkedPageFromPaginationLinks() {
        val html = """
            <html>
              <body>
                <div class="pagination">
                  <a href="/account/pledges?page=1">1</a>
                  <a href="/account/pledges?page=2">2</a>
                  <a href="/account/pledges?page=3">3</a>
                </div>
              </body>
            </html>
        """.trimIndent()

        val info = RsiInventoryClient().extractPaginationInfo(html)

        assertEquals(3, info.maxLinkedPage)
    }

    @Test
    fun returnsNullWhenPaginationLinksAreMissing() {
        val html = """
            <html>
              <body>
                <div class="list-items">
                  <div class="row">
                    <input class="js-pledge-id" value="12345" />
                  </div>
                </div>
              </body>
            </html>
        """.trimIndent()

        val info = RsiInventoryClient().extractPaginationInfo(html)

        assertNull(info.maxLinkedPage)
    }
}
