package cn.mudlife.app.model

import cn.mudlife.app.data.DeviceOrderEndEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderVerificationTest {
    @Test fun onlyIdentifiableDrinkingDevicesUseFastPolling() {
        val device = DeviceInfo(1, "宿舍楼热水表", "sn", "mac", 0.0, 1)
        assertFalse(device.copy(bigTypeId = 5).isDrinkingWater)
        assertFalse(device.copy(deviceName = "热水设备").isDrinkingWater)
        assertTrue(device.copy(deviceName = "一楼", smallTypeName = "直饮水").isDrinkingWater)
    }

    @Test fun monitorRequiresConsecutiveEndedReplies() {
        val confirmation = DeviceOrderEndEvidence()
        assertFalse(confirmation.observe(OrderVerification.ENDED))
        assertFalse(confirmation.observe(OrderVerification.UNKNOWN))
        assertFalse(confirmation.observe(OrderVerification.ENDED))
        assertTrue(confirmation.observe(OrderVerification.ENDED))
    }

    @Test fun replacementOrderEndsTheOriginalSession() {
        assertEquals(OrderVerification.ENDED, verifyOrder(BaseResponse(true, OrderStatus("next")), "original"))
        assertEquals(OrderVerification.UNKNOWN, verifyOrder(BaseResponse(false, OrderStatus("next"), 500), "original"))
        assertEquals(OrderVerification.UNKNOWN, verifyOrder(BaseResponse(false, null, 307)))
    }

    @Test fun active307IsNotTreatedAsFailureOrRemoved() {
        assertEquals(OrderVerification.ACTIVE, verifyOrder(BaseResponse(false, OrderStatus(orderNo = "order-a", isOwner = true), 307)))
    }
    @Test fun successfulEmptyStatusConfirmsStoppedDevice() {
        assertEquals(OrderVerification.ENDED, verifyOrder(BaseResponse<OrderStatus>(true, null)))
    }
    @Test fun networkOrLoginFailureMustNotEraseSavedOrder() {
        assertEquals(OrderVerification.UNKNOWN, verifyOrder(BaseResponse<OrderStatus>(false, null, 401)))
        assertEquals(OrderVerification.UNKNOWN, verifyOrder(BaseResponse<OrderStatus>(false, null, 500)))
    }
    @Test fun anotherPersonsOrderDoesNotRestoreMine() {
        assertEquals(OrderVerification.ENDED, verifyOrder(BaseResponse(false, OrderStatus("other", isOwner = false), 307)))
    }
    @Test fun ambiguousPayloadCannotAuthorizeAStartOrAdoptAnUnknownOrder() {
        assertEquals(OrderVerification.UNKNOWN, verifyOrder(BaseResponse(true, OrderStatus())))
        assertEquals(OrderVerification.UNKNOWN, verifyOrder(BaseResponse(true, OrderStatus("unknown"))))
        assertEquals(OrderVerification.ACTIVE, verifyOrder(BaseResponse(true, OrderStatus("known")), "known"))
    }
    @Test fun normalActiveResponseCanRestoreOrder() {
        assertEquals(OrderVerification.ACTIVE, verifyOrder(BaseResponse(true, OrderStatus("mine", isOwner = true))))
    }
}
