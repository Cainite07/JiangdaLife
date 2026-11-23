package cn.mudlife.app.data

import cn.mudlife.app.model.BaseResponse
import cn.mudlife.app.model.OrderStatus
import cn.mudlife.app.model.OrderVerification
import cn.mudlife.app.model.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal suspend fun monitorDeviceOrder(
    drinkingWater: Boolean,
    foreground: Boolean,
    immediate: Boolean,
    expectedOrderNo: () -> String?,
    current: () -> Boolean,
    query: suspend () -> BaseResponse<OrderStatus>,
    onOrder: (String) -> Unit,
    onEnded: () -> Unit
) {
    val period = when {
        !foreground -> 10_000L
        drinkingWater -> 200L
        else -> 2_000L
    }
    var wait = if (immediate) 0L else period
    var failures = 0
    val evidence = DeviceOrderEndEvidence()

    while (current()) {
        delay(wait)
        if (!current()) return
        val response = try {
            query()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (!current()) return

        val status = response?.let { verifyOrder(it, expectedOrderNo()) } ?: OrderVerification.UNKNOWN
        if (status == OrderVerification.ACTIVE) {
            response?.data?.orderNo?.takeIf(String::isNotBlank)?.let(onOrder)
        }
        if (evidence.observe(status)) {
            onEnded()
            return
        }
        failures = if (status == OrderVerification.UNKNOWN) (failures + 1).coerceAtMost(4) else 0
        wait = if (failures == 0) period else (maxOf(period, 1_000L) * (1L shl failures)).coerceAtMost(30_000L)
    }
}

internal class DeviceOrderEndEvidence {
    private var consecutive = 0

    fun observe(status: OrderVerification): Boolean {
        consecutive = if (status == OrderVerification.ENDED) consecutive + 1 else 0
        return consecutive >= 2
    }
}
