package cn.mudlife.campuscard

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [36])
class SchoolSessionStorageTest {
    @Test fun unreadableStorageDisablesSchoolSessionWithoutWritingOrDeletingData() {
        val app = RuntimeEnvironment.getApplication()
        val existing = app.getSharedPreferences("school_session", 0)
        assertTrue(existing.edit().putString("fixture", "keep").commit())

        val store = SessionStore(app) { throw java.security.GeneralSecurityException("fixture") }
        assertFalse(store.storageAvailable)
        assertFalse(store.hasSession)
        assertFalse(store.ownsPendingPayment)
        assertFalse(store.beginPayment("fixture", "card", 100))
        assertNull(store.timetableOwner)
        assertThrows(IllegalStateException::class.java) { store.save("fixture") }
        store.clear()
        assertEquals("keep", existing.getString("fixture", null))
    }
}
