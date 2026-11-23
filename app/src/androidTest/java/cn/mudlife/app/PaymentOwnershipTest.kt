package cn.mudlife.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.mudlife.campuscard.CampusCard
import cn.mudlife.campuscard.SessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentOwnershipTest {
    @Test fun unreadableOldEncryptedPreferencesSurviveAndPlainPreferencesCanRetry() {
        val context = FixtureContext()
        val name = "linyu_prefs"
        try {
            context.deleteSharedPreferences("mudlife_prefs")
            val legacy = context.getSharedPreferences(name, 0)
            val keyset = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
            assertTrue(legacy.edit().clear().putString(keyset, "invalid-fixture").commit())
            cn.mudlife.app.utils.PrefsHelper.init(context)
            assertTrue(context.getSharedPreferences(name, 0).contains(keyset))
            cn.mudlife.app.utils.PrefsHelper.qzhqPhone = "10000000002"
            // Simulate the original preferences becoming readable at the next startup.
            assertTrue(legacy.edit().clear().putString("lastDeviceName", "fixture device")
                .putString("qzhqPhone", "10000000001").commit())
            cn.mudlife.app.utils.PrefsHelper.init(context)
            assertEquals("fixture device", cn.mudlife.app.utils.PrefsHelper.lastDeviceName)
            assertEquals("10000000002", cn.mudlife.app.utils.PrefsHelper.qzhqPhone)
            assertFalse(context.getSharedPreferences(name, 0).contains("lastDeviceName"))
        } finally {
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences("mudlife_prefs")
            cn.mudlife.app.utils.PrefsHelper.init(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    @Test fun pendingOrderSurvivesRestartAndOnlyItsCardCanResume() {
        val context = FixtureContext()
        val store = SessionStore(context)
        fun select(token: String, id: String) {
            val card = CampusCard(id, "", "", id, null, null, emptyList(), false)
            assertTrue(store.selectAccount(token, id, card))
        }
        store.clear()
        try {
            store.save("fixture-a")
            select("fixture-a", "card-a")
            assertTrue(store.beginPayment("card-a", "electric", 123))
            assertFalse(store.beginPayment("card-a", "card", 123))
            assertTrue(SessionStore(context).ownsPendingPayment)
            select("fixture-a", "card-b")
            assertFalse(store.ownsPendingPayment)
            store.recordPaymentRoute("wrong", "wrong")
            assertEquals("creating", store.pendingCheckout)
            store.save("fixture-b")
            assertFalse(store.ownsPendingPayment)
            select("fixture-b", "card-a")
            assertTrue(store.ownsPendingPayment)
            assertEquals(123L, store.pendingFen)
        } finally {
            store.clear()
        }
    }
}
