package cn.mudlife.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry

/** Separate preference files under the instrumented process UID, including encrypted stores. */
internal class FixtureContext(private val prefix: String = "audit_fixture_") : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    override fun deleteSharedPreferences(name: String) = super.deleteSharedPreferences(prefix + name)
}
