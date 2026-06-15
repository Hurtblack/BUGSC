package com.euedrc.bugsc.market.transaction

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransactionParsingTest {

    @Test
    fun prefersTransactionNumberWhenBackendReturnsString() {
        val result = TransactionParser.parseCreate(
            JSONObject("""{"code":0,"data":"T202606150001","msg":""}""")
        )

        assertEquals("T202606150001", result.identifier)
    }

    @Test
    fun prefersTransactionNumberFromCreateResponseObject() {
        val result = TransactionParser.parseCreate(
            JSONObject("""{"code":0,"data":{"id":3380,"transactionNumber":"T202606150002"},"msg":""}""")
        )

        assertEquals("T202606150002", result.identifier)
    }

    @Test
    fun rejectsObjectWithoutTransactionNumberInsteadOfShowingDatabaseId() {
        assertThrows(TransactionContractException::class.java) {
            TransactionParser.parseCreate(
                JSONObject(
                    """{"code":0,"data":{"id":3380,"orderNumber":"7384","number":10,"locationId":1},"msg":""}"""
                )
            )
        }
    }

    @Test
    fun rejectsScalarNumericIdInsteadOfShowingItAsTransactionNumber() {
        assertThrows(TransactionContractException::class.java) {
            TransactionParser.parseCreate(
                JSONObject("""{"code":0,"data":3380,"msg":""}""")
            )
        }
    }

    @Test
    fun rejectsNumericStringInsteadOfShowingItAsTransactionNumber() {
        assertThrows(TransactionContractException::class.java) {
            TransactionParser.parseCreate(
                JSONObject("""{"code":0,"data":"3380","msg":""}""")
            )
        }
    }

    @Test
    fun parsesPageAndDetailFields() {
        val json = JSONObject(
            """
            {
              "code":0,
              "data":{
                "list":[{
                  "transactionNumber":"T1",
                  "itemsName":"量子驱动器",
                  "creatorType":1,
                  "number":2,
                  "amount":2000000,
                  "transactionStatus":1,
                  "deliveryStatus":1,
                  "transactionLocationName":"轨道讣闻站",
                  "deliveryMethod":0,
                  "shippingFee":100,
                  "createTime":"2026-06-15 10:00:00"
                }],
                "total":1
              }
            }
            """.trimIndent()
        )

        val page = TransactionParser.parsePage(json)

        assertEquals(1L, page.total)
        assertEquals("T1", page.items.single().transactionNumber)
        assertEquals("面交", page.items.single().deliveryMethodLabel)
    }
}
