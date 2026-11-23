package cn.mudlife.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.mudlife.app.model.ActiveOrder
import cn.mudlife.app.model.BaseResponse
import cn.mudlife.app.model.OrderStatus
import cn.mudlife.app.ui.MainViewModel
import cn.mudlife.app.utils.PrefsHelper
import kotlinx.coroutines.CompletableDeferred
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith

/** All order responses are local fixtures; this test cannot open a valve or call its API. */
@RunWith(AndroidJUnit4::class)
class RestoredOrderStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val cached = ActiveOrder("fixture-sn", "fixture-order", "测试设备", "fixture-mac", "🚰", 0.0, "43")

    @After fun removeOnlyFixtureOrder() {
        if (InstrumentationRegistry.getArguments().getString("deviceOrderPhase") == "seed") return
        onMain {
            PrefsHelper.init(FixtureContext())
            PrefsHelper.saveActiveOrders(PrefsHelper.getActiveOrders().filterNot {
                it.snCode == cached.snCode && it.orderNo == cached.orderNo
            })
        }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(250) {
            var done = false
            onMain { done = predicate() }
            if (done) return
            Thread.sleep(20)
        }
        fail("Local fixture did not settle")
    }

    @Test fun repeatedTapOpensOnceAndFastStopUsesConfirmedOrderNumber() {
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val closedOrder = java.util.concurrent.atomic.AtomicReference<String>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val data = when (request.url.encodedPath.substringAfterLast('/')) {
                "using" -> "null"
                "rateOrder" -> { opens.incrementAndGet(); "{}" }
                "downRateResult" -> """{"state":0,"orderNo":"fixture-open"}"""
                "closeOrder" -> {
                    val form = request.body as okhttp3.FormBody
                    closedOrder.set((0 until form.size).first { form.name(it) == "orderNo" }.let(form::value))
                    "{}"
                }
                "query" -> """{"state":0,"orderNo":"fixture-open"}"""
                else -> error("Unexpected device request")
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body("""{"success":true,"data":$data}""".toResponseBody()).build()
        }.build()
        val api = retrofit2.Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .build().create(cn.mudlife.app.api.QzxyService::class.java)
        lateinit var model: MainViewModel
        onMain {
            PrefsHelper.init(FixtureContext())
            model = MainViewModel(deviceService = api)
            model.selectedDevice = cn.mudlife.app.model.DeviceInfo(1, "饮水机", cached.snCode, "fixture", 0.0, 1)
            // Viewing a previously ended order never opens the valve.
            model.startShower(resuming = cached)
        }
        waitUntil { !model.isStartingShower }
        assertEquals(0, opens.get())
        onMain { model.startShower(); model.startShower() }
        waitUntil { model.isShowering }
        onMain {
            assertEquals("fixture-open", model.currentOrderNo)
            assertEquals("fixture-open", PrefsHelper.getActiveOrders().single().orderNo)
            model.setDeviceForeground(false)
            model.stopShower()
        }
        waitUntil { !model.isShowering }
        assertEquals(1, opens.get())
        assertEquals("fixture-open", closedOrder.get())
        onMain { model.logout() }
    }

    @Test fun logoutRestoresOutstandingOrdersOnlyToTheirOriginalAccount() {
        onMain {
            PrefsHelper.init(FixtureContext())
            try {
                PrefsHelper.saveAuth("fixture", "1", "1", "1", "10000000001", null)
                PrefsHelper.saveActiveOrders(listOf(cached))
                PrefsHelper.setStartedAt(cached.snCode, 123L)
                PrefsHelper.clearLifeAccount()
                PrefsHelper.saveAuth("other", "2", "2", "1", "10000000002", null)
                assertTrue(PrefsHelper.getActiveOrders().isEmpty())
                assertEquals(0L, PrefsHelper.getStartedAt(cached.snCode))
                PrefsHelper.saveAuth("new-session", "1", "1", "1", "10000000001", null)
                assertEquals(listOf(cached), PrefsHelper.getActiveOrders())
                assertEquals(123L, PrefsHelper.getStartedAt(cached.snCode))
            } finally {
                PrefsHelper.clearActiveOrders()
                PrefsHelper.clearLifeAccount()
            }
        }
    }

    @Test fun coldProcessReadsOrderLeftByPreviousProcess() {
        val args = InstrumentationRegistry.getArguments()
        // Run twice with -e deviceOrderPhase seed/read for a real process boundary.
        val fixture = FixtureContext()
        onMain {
            PrefsHelper.init(fixture)
            if (args.getString("deviceOrderPhase") != "read") {
                assertTrue(PrefsHelper.saveActiveOrders(listOf(cached)))
            }
            if (args.getString("deviceOrderPhase") != "seed") {
                val model = MainViewModel { BaseResponse(false, null, 503) }
                model.initManagers()
                assertTrue(model.activeOrders.isEmpty())
                assertEquals(listOf(cached), model.unverifiedOrders.toList())
                assertEquals(listOf(cached), PrefsHelper.getActiveOrders())
                model.logout()
                PrefsHelper.clearActiveOrders()
            }

        }
    }

    @Test fun stoppedCacheNeverAppearsAsActiveAndIsRemovedFromDisk() {
        val response = CompletableDeferred<BaseResponse<OrderStatus>>()
        lateinit var model: MainViewModel
        onMain {
            PrefsHelper.init(FixtureContext())
            PrefsHelper.saveActiveOrders(listOf(cached))
            model = MainViewModel { response.await() }
            model.initManagers()
            assertTrue(model.activeOrders.isEmpty())
            assertEquals(listOf(cached), model.unverifiedOrders.toList())
            assertFalse(model.isDeviceActive(cached.snCode))
        }
        response.complete(BaseResponse(true, null))
        waitUntil { !model.isVerifyingOrders }
        onMain {
            assertTrue(model.activeOrders.isEmpty())
            assertTrue(model.unverifiedOrders.isEmpty())
            assertTrue(PrefsHelper.getActiveOrders().isEmpty())
            model.logout()
        }
    }

    @Test fun realActive307IsOnlyShownAfterConfirmationAndNotRestoredAgainOnTabReturn() {
        val response = CompletableDeferred<BaseResponse<OrderStatus>>()
        lateinit var model: MainViewModel
        onMain {
            PrefsHelper.init(FixtureContext())
            PrefsHelper.saveActiveOrders(listOf(cached))
            model = MainViewModel { response.await() }
            model.initManagers()
            assertTrue(model.activeOrders.isEmpty())
        }
        response.complete(BaseResponse(false, OrderStatus("fixture-order"), 307))
        waitUntil { !model.isVerifyingOrders }
        onMain {
            assertEquals(listOf(cached), model.activeOrders.toList())
            assertTrue(model.unverifiedOrders.isEmpty())
            assertTrue(model.isDeviceActive(cached.snCode))
            model.initManagers()
            assertEquals(listOf(cached), model.activeOrders.toList())
            assertTrue(model.unverifiedOrders.isEmpty())
            model.logout()
            PrefsHelper.clearActiveOrders()
        }
    }

    @Test fun failedQueryRetainsUnverifiedRecordAndCanRetry() {
        var queryCount = 0
        lateinit var model: MainViewModel
        onMain {
            PrefsHelper.init(FixtureContext())
            PrefsHelper.saveActiveOrders(listOf(cached))
            model = MainViewModel {
                queryCount++
                if (queryCount == 1) BaseResponse(false, null, 401) else BaseResponse(true, null)
            }
            model.initManagers()
        }
        waitUntil { !model.isVerifyingOrders }
        onMain {
            assertTrue(model.activeOrders.isEmpty())
            assertEquals(listOf(cached), model.unverifiedOrders.toList())
            assertEquals(listOf(cached), PrefsHelper.getActiveOrders())
            model.cleanInactiveOrders()
        }
        waitUntil { !model.isVerifyingOrders }
        onMain {
            assertEquals(3, queryCount)
            assertTrue(model.unverifiedOrders.isEmpty())
            assertTrue(PrefsHelper.getActiveOrders().isEmpty())
            model.logout()
        }
    }

    @Test fun deviceStopClearsPersistedOrderBeforeReceiptIsConfirmed() {
        onMain {
            PrefsHelper.init(FixtureContext())
            PrefsHelper.saveActiveOrders(listOf(cached))
            PrefsHelper.setStartedAt(cached.snCode, System.currentTimeMillis())
            val model = MainViewModel { BaseResponse(true, null) }
            model.activeOrders.add(cached)
            model.showerSnCode = cached.snCode
            model.isShowering = true
            // Settlement is deliberately cancelled; synchronous stop cleanup must still happen.
            model.viewModelScope.cancel()
            MainViewModel::class.java.getDeclaredMethod("onAutoClose", String::class.java).apply {
                isAccessible = true
            }.invoke(model, cached.snCode)
            assertTrue(model.showAutoCloseDialog)
            assertTrue(model.activeOrders.isEmpty())
            assertTrue(PrefsHelper.getActiveOrders().isEmpty())
            assertEquals(0L, PrefsHelper.getStartedAt(cached.snCode))
            model.logout()
        }
    }
}
