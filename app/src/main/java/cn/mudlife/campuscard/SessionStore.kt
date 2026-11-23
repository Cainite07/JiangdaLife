package cn.mudlife.campuscard

import android.content.Context
import android.content.Intent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.mudlife.app.BuildConfig

/** Passwords and payment codes are never persisted. Network errors never erase login. */
class SessionStore(context: Context) {
    private val app = context.applicationContext
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext, "school_session",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    val token: String get() = preferences.getString("access_token", "").orEmpty()
    val hasSession: Boolean get() = token.isNotBlank()
    fun unifiedPeriod(owner: String): String = preferences.getString("unified_period_$owner", "").orEmpty()
    fun saveUnifiedPeriod(owner: String, value: String) { check(preferences.edit().putString("unified_period_$owner", value).commit()) }
    fun save(token: String) {
        require(token.isNotBlank() && token.length <= 16384 && !token.contains('\n'))
        val next = token.removePrefix("bearer ")
        synchronized(accountLock) {
            val edit = preferences.edit().putString("access_token", next)
            val changed = this.token != next
            if (changed) edit.remove("timetable_owner").putBoolean("payment_card_unverified", true)
            check(edit.commit())
            if (changed) notifyTimetableWidget()
        }
    }
    val selectedAccount: String
        get() = preferences.getString("selected_account", "").orEmpty()
    val timetableOwner: String?
        get() = synchronized(accountLock) {
            if (hasSession && selectedAccount.isNotBlank())
                preferences.getString("timetable_owner", null)
            else null
        }

    /** Only the selected card returned by the current authenticated request can establish an owner. */
    fun selectAccount(expectedToken: String, account: String, verifiedCard: CampusCard?): Boolean {
        synchronized(accountLock) {
            if (expectedToken.isBlank() || expectedToken != token) return false
            val owner = verifiedCard?.takeIf { account.isNotBlank() && it.account == account }
                ?.number?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            val previous = timetableOwner
            if (selectedAccount != account || previous != owner || preferences.getBoolean("payment_card_unverified", false)) {
                check(preferences.edit().putString("selected_account", account)
                    .putString("timetable_owner", owner).putBoolean("payment_card_unverified", verifiedCard?.account != account).commit())
                if (owner != previous) notifyTimetableWidget()
            }
            return true
        }
    }

    private fun notifyTimetableWidget() {
        app.sendBroadcast(Intent("cn.mudlife.app.TIMETABLE_ACCOUNT_CHANGED")
            .setClassName(app, "cn.mudlife.app.timetable.TodayTimetableWidget"))
    }
    val paymentAccount: String get() = selectedAccount.takeIf {
        hasSession && !preferences.getBoolean("payment_card_unverified", false)
    }.orEmpty()
    val ownsPendingPayment: Boolean get() = paymentAccount.isNotBlank() &&
        preferences.getString("pending_account", null) == paymentAccount

    fun beginPayment(account: String, kind: String, fen: Long): Boolean {
        if (account.isBlank() || account != paymentAccount || pendingCheckout.isNotBlank()) return false
        return preferences.edit().putString("pending_account", account).putString("pending_kind", kind)
            .putLong("pending_fen", fen).putString("pending_order_id", "")
            .putString("pending_checkout", "creating").commit()
    }

    fun recordPaymentRoute(checkout: String, orderId: String) {
        if (ownsPendingPayment) check(preferences.edit().putString("pending_checkout", checkout)
            .putString("pending_order_id", orderId).commit())
    }
    val pendingCheckout: String
        get() = preferences.getString("pending_checkout", "").orEmpty()
    val pendingFen: Long
        get() = preferences.getLong("pending_fen", 0)
    var startupCode: Boolean
        get() = preferences.getBoolean("startup_code", false)
        set(value) { preferences.edit().putBoolean("startup_code", value).apply() }
    var brightCode: Boolean
        get() = preferences.getBoolean("bright_code", false)
        set(value) { preferences.edit().putBoolean("bright_code", value).apply() }
    var billPeriod: String
        get() = preferences.getString("bill_period_" + selectedAccount, "").orEmpty()
        set(value) { check(preferences.edit().putString("bill_period_" + selectedAccount, value).commit()) }
    var electricFavorites: String
        get() = preferences.getString("electric_favorites_" + selectedAccount, "[]").orEmpty()
        set(value) { preferences.edit().putString("electric_favorites_" + selectedAccount, value).apply() }
    val pendingKind: String
        get() = preferences.getString("pending_kind", "card").orEmpty()
    val pendingOrderId: String
        get() = preferences.getString("pending_order_id", "").orEmpty()
    fun clearPending() { check(preferences.edit().remove("pending_account").remove("pending_checkout").remove("pending_fen").remove("pending_kind").remove("pending_order_id").commit()) }
    fun clearSchoolAuthentication() {
        synchronized(accountLock) {
            check(BuildConfig.DEBUG && pendingCheckout.isBlank())
            check(preferences.edit().remove("access_token").remove("selected_account").remove("timetable_owner").commit())
            notifyTimetableWidget()
        }
    }
    fun clear() {
        synchronized(accountLock) {
            check(preferences.edit().clear().commit())
            notifyTimetableWidget()
        }
    }

    private companion object {
        val accountLock = Any()
    }
}

object Money {
    fun parseYuan(text: String): Long? = runCatching {
        if (!text.matches(Regex("[0-9]{1,5}(\\.[0-9]{1,2})?"))) return null
        java.math.BigDecimal(text).movePointRight(2).longValueExact().takeIf { it > 0 }
    }.getOrNull()
    fun yuan(fen: Long): String = java.math.BigDecimal.valueOf(fen, 2).toPlainString()
}

object LoginPolicy {
    // Only an explicit authentication failure warrants official reauthentication.
    fun needsReauthentication(httpStatus: Int): Boolean = httpStatus == 401
}

object BillPeriodPolicy {
    fun restore(saved: String, today: java.time.YearMonth = java.time.YearMonth.now()): java.time.YearMonth? =
        if (saved == "ALL") null else runCatching { java.time.YearMonth.parse(saved) }.getOrDefault(today)
    fun save(month: java.time.YearMonth?): String = month?.toString() ?: "ALL"
}

object RechargePolicy {
    val presets = listOf(1000L, 5000L, 10000L, 20000L)
    fun allowed(fen: Long?, rules: RechargeRules?): Boolean = fen != null && rules != null &&
        fen in rules.minFen..rules.maxFen && (!rules.wholeYuan || fen % 100L == 0L) && (rules.custom || fen in rules.amounts)
}
