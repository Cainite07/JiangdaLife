package cn.mudlife.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.mudlife.app.utils.PrefsHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run seed and restore in separate instrumentation processes, without an Activity or API calls. */
@RunWith(AndroidJUnit4::class)
class BathPrefixPersistenceTest {
    private val context = FixtureContext("bath_fixture_")

    @Test fun cleanupFixtureDataOnly() {
        context.deleteSharedPreferences("mudlife_prefs")
    }

    @Test fun seedBeforeProcessExit() {
        context.deleteSharedPreferences("mudlife_prefs")
        PrefsHelper.init(context)
        PrefsHelper.qzhqPhone = "13000000000"
        PrefsHelper.qzhqProjectId = "43"
        PrefsHelper.qzhqCustomCode = "13579"
        assertEquals("13579", PrefsHelper.qzhqCustomCode)
    }

    @Test fun restoreInNewProcessAndSeparateAccounts() {
        if (InstrumentationRegistry.getArguments().getString("bathPhase") != "read") seedBeforeProcessExit()
        PrefsHelper.init(context)
        assertEquals("13579", PrefsHelper.qzhqCustomCode)
        PrefsHelper.qzhqPhone = "13000000001"
        assertEquals("", PrefsHelper.qzhqCustomCode)
        PrefsHelper.qzhqCustomCode = "00246"
        PrefsHelper.qzhqPhone = "13000000000"
        assertEquals("13579", PrefsHelper.qzhqCustomCode)
        PrefsHelper.qzhqProjectId = "44"
        assertEquals("", PrefsHelper.qzhqCustomCode)
        PrefsHelper.qzhqProjectId = "43"
        assertEquals("13579", PrefsHelper.qzhqCustomCode)
        cleanupFixtureDataOnly()
    }
}
