package cn.mudlife.app.data

import cn.mudlife.app.api.NetworkModule
import cn.mudlife.app.api.getBillListSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

internal suspend fun queryDeviceSettlement(orderNo: String, projectId: String?, startTime: Long): Double? {
    if (orderNo.isBlank()) return null
    val accountId = NetworkModule.authFields(overrideProjectId = projectId)["accountId"]
        ?.takeIf(String::isNotBlank) ?: return null
    val zone = ZoneId.of("Asia/Shanghai")
    val currentMonth = YearMonth.now(zone)
    val startMonth = if (startTime > 0) YearMonth.from(Instant.ofEpochMilli(startTime).atZone(zone))
        else currentMonth.minusMonths(1)
    val months = listOf(currentMonth, startMonth).distinct()

    repeat(6) { attempt ->
        for (month in months) {
            val amount = try {
                val bills = NetworkModule.apiService.getBillListSafe(
                    month.toString(), projectId = projectId, accountId = accountId
                ).data.orEmpty()
                bills.asSequence().mapNotNull { it.consumeBillDTO }
                    .firstOrNull { it.orderNo == orderNo || it.orderId == orderNo }
                    ?.safeConsumeMoney?.toDoubleOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (amount != null && amount.isFinite() && amount > 0) return amount
        }
        if (attempt < 5) delay((attempt + 1L) * 2_000L)
    }
    return null
}
