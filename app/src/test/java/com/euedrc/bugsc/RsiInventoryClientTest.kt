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

    @Test
    fun buildsReclaimPledgeRequestLikeRsiHangarApi() {
        val request = RsiInventoryClient.accountActionRequest(
            RsiAccountAction.Reclaim(pledgeId = "12345", currentPassword = "secret")
        )

        assertEquals("api/account/reclaimPledge", request.endpoint)
        assertEquals("12345", request.body.getString("pledge_id"))
        assertEquals("secret", request.body.getString("current_password"))
    }

    @Test
    fun buildsGiftPledgeRequestLikeRsiHangarApi() {
        val request = RsiInventoryClient.accountActionRequest(
            RsiAccountAction.Gift(
                pledgeId = "12345",
                currentPassword = "secret",
                email = "target@example.com",
                name = "Friend"
            )
        )

        assertEquals("api/account/giftPledge", request.endpoint)
        assertEquals("12345", request.body.getString("pledge_id"))
        assertEquals("secret", request.body.getString("current_password"))
        assertEquals("target@example.com", request.body.getString("email"))
        assertEquals("Friend", request.body.getString("name"))
    }

    @Test
    fun buildsCancelGiftRequestLikeRsiHangarApi() {
        val request = RsiInventoryClient.accountActionRequest(
            RsiAccountAction.CancelGift(pledgeId = "12345")
        )

        assertEquals("api/account/cancelGift", request.endpoint)
        assertEquals("12345", request.body.getString("pledge_id"))
    }
}
