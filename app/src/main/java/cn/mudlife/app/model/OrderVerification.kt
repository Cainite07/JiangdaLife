package cn.mudlife.app.model

/** A restored record is only a candidate until the server confirms it. */
enum class OrderVerification { ACTIVE, ENDED, UNKNOWN }

fun verifyOrder(response: BaseResponse<OrderStatus>, expectedOrderNo: String? = null): OrderVerification {
    if (!response.success && response.errorCode != 307) return OrderVerification.UNKNOWN
    val status = response.data ?: return if (response.success && response.errorCode != 307)
        OrderVerification.ENDED else OrderVerification.UNKNOWN
    if (status.isOwner == false) return OrderVerification.ENDED
    val number = status.orderNo?.takeIf(String::isNotBlank)
    if (number != null && !expectedOrderNo.isNullOrBlank()) {
        return if (number == expectedOrderNo) OrderVerification.ACTIVE else OrderVerification.ENDED
    }
    return if (status.isOwner == true && (number != null || response.errorCode == 307))
        OrderVerification.ACTIVE else OrderVerification.UNKNOWN
}
