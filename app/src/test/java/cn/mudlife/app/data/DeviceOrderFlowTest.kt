package cn.mudlife.app.data

import cn.mudlife.app.model.BaseResponse
import cn.mudlife.app.model.BillDTO
import cn.mudlife.app.model.BillItem
import cn.mudlife.app.model.OrderStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.YearMonth

class DeviceOrderFlowTest {
    @Test fun probeBacksOffAndReturnsToTheNormalInterval() = runBlocking {
        val waits = mutableListOf<Long>()
        var queries = 0
        monitorDeviceOrder(
            drinkingWater = false, foreground = true, immediate = true,
            expectedOrderNo = { "current" }, current = { queries < 8 },
            query = {
                queries++
                if (queries <= 5) BaseResponse(false, null, 503)
                else BaseResponse(true, OrderStatus("current"))
            },
            onOrder = { assertEquals("current", it) },
            onEnded = { throw AssertionError("An unavailable or active order must not end the session") },
            pause = { waits += it }
        )
        assertEquals(listOf(0L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 2_000L, 2_000L), waits)
    }

    @Test fun settlementChecksBothMonthsAndIgnoresOtherOrders() = runBlocking {
        val queried = mutableListOf<YearMonth>()
        val current = YearMonth.of(2026, 11)
        val start = Instant.parse("2026-10-31T15:59:00Z").toEpochMilli()
        val amount = queryDeviceSettlement("target", start, current,
            load = { month ->
                queried += month
                if (month == current) listOf(BillItem(consumeBillDTO = BillDTO(orderNo = "other", consumeMoney = "9.99")))
                else listOf(BillItem(consumeBillDTO = BillDTO(orderId = "target", consumeMoney = "1.25")))
            },
            pause = { throw AssertionError("A settled matching bill should return immediately") }
        )
        assertEquals(1.25, amount)
        assertEquals(listOf(current, current.minusMonths(1)), queried)
    }

    @Test fun unrelatedBillsNeverSettleAnOrder() = runBlocking {
        val waits = mutableListOf<Long>()
        val current = YearMonth.of(2026, 11)
        val start = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        val amount = queryDeviceSettlement("target", start, current,
            load = { listOf(BillItem(consumeBillDTO = BillDTO(orderNo = "other", consumeMoney = "9.99"))) },
            pause = { waits += it }
        )
        assertNull(amount)
        assertEquals(listOf(2_000L, 4_000L, 6_000L, 8_000L, 10_000L), waits)
    }
    @Test fun fastStopStatusDoesNotWaitForTheResultEndpoint() = runBlocking {
        var submitted = 0
        var cancelled = false
        assertTrue(firstCloseConfirmation(
            result = {
                submitted++
                try { awaitCancellation() } finally { cancelled = true }
            },
            status = { assertEquals(1, submitted); true }
        ))
        assertTrue(cancelled)
        assertEquals(1, submitted)
    }

    @Test fun anUnavailableStatusDoesNotOverrideTheResultEndpoint() = runBlocking {
        assertTrue(firstCloseConfirmation(result = { true }, status = { false }))
        assertFalse(firstCloseConfirmation(result = { false }, status = { false }))
    }
    @Test fun waterSwitchWaitsForAnExplicitlyRejectedOrderThenStartsOnce() = runBlocking {
        val waits = mutableListOf<Long>()
        var sends = 0
        val result = DeviceCommands.retryUnfinishedWaterOrder(canWait = { true }, wait = { waits += it }) {
            if (++sends <= 2) BaseResponse<Unit>(false, null, 98, "存在未完成的订单")
            else BaseResponse(true, Unit)
        }
        assertTrue(result.success)
        assertEquals(listOf(650L, 650L), waits)
        assertEquals(3, sends)
    }

    @Test fun waterSwitchDoesNotRetryAnUnknownResultOrARequestCancelledDuringTheWait() = runBlocking {
        var sends = 0
        val unknown = BaseResponse<Unit>(false, null, -1, "服务响应格式异常")
        assertEquals(unknown, DeviceCommands.retryUnfinishedWaterOrder(canWait = { true }, wait = {
            throw AssertionError("Unknown responses must never retrigger water")
        }) { sends++; unknown })
        assertEquals(1, sends)

        var waiting = true
        val rejected = BaseResponse<Unit>(false, null, 98, "存在未完成的订单")
        assertEquals(rejected, DeviceCommands.retryUnfinishedWaterOrder(
            canWait = { waiting }, wait = { waiting = false }
        ) { sends++; rejected })
        assertEquals(2, sends)
    }
}
