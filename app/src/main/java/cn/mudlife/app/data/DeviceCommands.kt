package cn.mudlife.app.data

import android.os.SystemClock
import cn.mudlife.app.api.QzxyService
import cn.mudlife.app.api.closeOrderResultSafe
import cn.mudlife.app.api.downRateResultSafe
import cn.mudlife.app.api.queryUsingSafe
import cn.mudlife.app.model.BaseResponse
import cn.mudlife.app.model.CloseOrderResult
import cn.mudlife.app.model.DownRateResult
import cn.mudlife.app.model.OrderVerification
import cn.mudlife.app.model.verifyOrder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

internal object DeviceCommands {
    private val busy = mutableSetOf<String>()

    @Synchronized fun acquire(snCode: String): Boolean = busy.add(snCode)
    @Synchronized fun release(snCode: String) { busy.remove(snCode) }
    @Synchronized fun isBusy(snCode: String): Boolean = snCode in busy

    private data class WaterStop(val owner: String, val snCode: String, val at: Long)
    private val unfinishedOrder = Regex("(?:存在|有)(?:一笔)?未完成的?订单")
    @Volatile private var lastWaterStop: WaterStop? = null

    fun recordWaterStop(owner: String?, snCode: String) {
        if (owner != null) lastWaterStop = WaterStop(owner, snCode, SystemClock.elapsedRealtime())
    }

    suspend fun <T> afterWaterStop(
        owner: String?, snCode: String, stillRequested: () -> Boolean,
        onWaiting: () -> Unit = {}, request: suspend () -> BaseResponse<T>
    ): BaseResponse<T> {
        val stopped = lastWaterStop?.takeIf { it.owner == owner && it.snCode != snCode }
        return retryUnfinishedWaterOrder(
            canWait = {
                stopped != null && stopped == lastWaterStop &&
                    SystemClock.elapsedRealtime() - stopped.at <= 6_500 && stillRequested()
            }, onWaiting = onWaiting, request = request
        )
    }

    fun isUnfinishedWaterOrder(response: BaseResponse<*>): Boolean =
        !response.success && response.data == null && response.errorCode >= 0 &&
            unfinishedOrder.containsMatchIn(response.displayMessage.orEmpty())

    internal suspend fun <T> retryUnfinishedWaterOrder(
        canWait: () -> Boolean, onWaiting: () -> Unit = {},
        wait: suspend (Long) -> Unit = { delay(it) },
        request: suspend () -> BaseResponse<T>
    ): BaseResponse<T> {
        var response = request()
        for (interval in listOf(650L, 650L, 750L)) {
            if (!isUnfinishedWaterOrder(response) || !canWait()) break
            onWaiting()
            wait(interval)
            if (!canWait()) break
            response = request()
        }
        return response
    }

    data class Opened(val orderNo: String?, val autoDisconnectSeconds: Int)

    suspend fun confirmOpen(
        service: QzxyService, snCode: String, auth: Map<String, String>, accepted: DownRateResult?
    ): Opened? {
        val orderNo = accepted?.orderNo?.takeIf(String::isNotBlank)
        repeat(9) {
            delay(700)
            val response = ioResult { service.downRateResultSafe(snCode = snCode, auth = auth) }
            val data = response?.data
            if (response?.success == true && data != null &&
                (data.snCode.isNullOrBlank() || data.snCode == snCode) &&
                (orderNo == null || data.orderNo.isNullOrBlank() || data.orderNo == orderNo) &&
                (data.state == 0 || data.result == 0 || !data.orderNo.isNullOrBlank())
            ) {
                return Opened(data.orderNo?.takeIf(String::isNotBlank) ?: orderNo,
                    data.autoDisConTime?.takeIf { it > 0 } ?: accepted?.autoDisConTime ?: 0)
            }
        }
        return null
    }

    suspend fun confirmedEnded(
        service: QzxyService, snCode: String, orderNo: String, auth: Map<String, String>
    ): Boolean {
        repeat(2) { attempt ->
            if (attempt > 0) delay(500)
            val response = ioResult { service.queryUsingSafe(snCode = snCode, auth = auth) } ?: return false
            if (verifyOrder(response, orderNo) != OrderVerification.ENDED) return false
        }
        return true
    }

    suspend fun confirmClose(
        service: QzxyService, snCode: String, orderNo: String, auth: Map<String, String>,
        onRejected: (String?) -> Unit = {}
    ): Boolean {
        repeat(5) {
            delay(1000)
            val response = ioResult { service.closeOrderResultSafe(snCode = snCode, orderNo = orderNo, auth = auth) }
            if (response?.confirmsClose(snCode, orderNo) == true) return true
            if (response?.success == false) onRejected(response.displayMessage)
        }
        return confirmedEnded(service, snCode, orderNo, auth)
    }

    suspend fun confirmWidgetClose(
        service: QzxyService, snCode: String, orderNo: String, auth: Map<String, String>,
        send: suspend () -> Boolean
    ): Boolean = withTimeoutOrNull(9_000) {
        firstCloseConfirmation(
            result = result@{
                if (!send()) return@result false
                repeat(5) { attempt ->
                    if (attempt > 0) delay(450)
                    val response = withTimeoutOrNull(1500) {
                        ioResult { service.closeOrderResultSafe(snCode = snCode, orderNo = orderNo, auth = auth) }
                    }
                    if (response?.confirmsClose(snCode, orderNo) == true) return@result true
                }
                false
            },
            status = status@{
                delay(250)
                var ended = false
                repeat(12) {
                    val response = withTimeoutOrNull(1500) {
                        ioResult { service.queryUsingSafe(snCode = snCode, auth = auth) }
                    }
                    val nowEnded = response != null && verifyOrder(response, orderNo) == OrderVerification.ENDED
                    if (ended && nowEnded) return@status true
                    ended = nowEnded
                    delay(350)
                }
                false
            }
        )
    } ?: false

    private fun BaseResponse<CloseOrderResult>.confirmsClose(snCode: String, orderNo: String): Boolean {
        val result = data ?: return false
        return success && (result.orderNo.isNullOrBlank() || result.orderNo == orderNo) &&
            (result.deviceSnCode.isNullOrBlank() || result.deviceSnCode == snCode) &&
            (result.state == 0 || result.status == 0)
    }

    private suspend fun <T> ioResult(request: suspend () -> T): T? = try {
        request()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}


internal suspend fun firstCloseConfirmation(
    result: suspend () -> Boolean, status: suspend () -> Boolean
): Boolean = coroutineScope {
    val resultCheck = async(start = CoroutineStart.UNDISPATCHED) { result() }
    val statusCheck = async { status() }
    try {
        select {
            resultCheck.onAwait { it || statusCheck.await() }
            statusCheck.onAwait { it || resultCheck.await() }
        }
    } finally {
        resultCheck.cancel()
        statusCheck.cancel()
    }
}
