package cn.mudlife.app.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import cn.mudlife.app.model.ActiveOrder
import cn.mudlife.app.model.RecentDevice
import cn.mudlife.app.ui.theme.AppAppearance
import cn.mudlife.app.ui.theme.ThemeMode
import cn.mudlife.app.ui.theme.ThemePalette

object PrefsHelper {
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    var touchFeedback: Boolean
        get() = prefs.getBoolean("touchFeedback", true)
        set(value) = prefs.edit().putBoolean("touchFeedback", value).apply()

    internal val accountGuide get() = AccountGuidePreferences(prefs)

    var canStoreSessions = false
        private set

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (!::prefs.isInitialized) init(context)
    }

    @Synchronized
    fun init(context: Context) {
        val masterKey = runCatching {
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        }.getOrNull()
        val encrypted = masterKey?.let { openEncrypted(context, "mudlife_prefs", it) }
        canStoreSessions = encrypted != null
        prefs = encrypted ?: context.getSharedPreferences("jiangda_display_settings", Context.MODE_PRIVATE)
        if (encrypted != null) migrateLegacyPreferences(context, masterKey)
    }

    private fun openEncrypted(context: Context, name: String, key: MasterKey): SharedPreferences? =
        runCatching {
            EncryptedSharedPreferences.create(
                context, name, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { it.all }
        }.getOrNull()

    private fun migrateLegacyPreferences(context: Context, key: MasterKey) {
        val marker = "legacy_preferences_migrated"
        if (prefs.getBoolean(marker, false)) return
        val legacy = context.getSharedPreferences("linyu_prefs", Context.MODE_PRIVATE)
        val raw = legacy.all
        val encryptedLegacy = raw.keys.any { it.startsWith("__androidx_security_crypto_encrypted_prefs_") }
        val old = if (encryptedLegacy) openEncrypted(context, "linyu_prefs", key)?.all ?: return else raw
        val lifeKeys = setOf("loginCode", "userId", "accountId", "projectId", "telephone",
            "userName", "studentNo", "schoolName", "lastDeviceName", "lastDeviceMac",
            "lastDeviceSnCode", "lastDeviceEmoji", "recentDevices", "activeOrders", "projectAccounts")
        val bathKeys = setOf("qzhqLoginCode", "qzhqPhone", "qzhqProjectId", "qzhqCustomCode")
        val existing = prefs.all
        fun lifeKey(name: String) = name in lifeKeys || name.startsWith("startedAt_") || name.startsWith("autoDiscon_")
        // Each login has its own owner. Keep a newer group intact while recovering other groups.
        val importLife = existing.keys.none(::lifeKey)
        val legacyBathPhone = (old["qzhqPhone"] as? String)?.takeIf(String::isNotBlank)
            ?: (old["telephone"] as? String)?.takeIf(String::isNotBlank)
        val legacyBathLogin = !(old["qzhqLoginCode"] as? String).isNullOrBlank()
        val importBath = existing.keys.none { it in bathKeys } && (!legacyBathLogin || legacyBathPhone != null)
        val editor = prefs.edit().putBoolean(marker, true)
        for ((name, value) in old) {
            if (name in existing) continue
            if (name != "touchFeedback" && !(importLife && lifeKey(name)) && !(importBath && name in bathKeys)) continue
            when (value) {
                is String -> editor.putString(name, value)
                is Long -> editor.putLong(name, value)
                is Boolean -> editor.putBoolean(name, value)
            }
        }
        if (importBath && legacyBathLogin) editor.putString("qzhqPhone", legacyBathPhone)
        if (editor.commit() && old.isNotEmpty()) context.deleteSharedPreferences("linyu_prefs")
    }

    // ── Auth ──
    var loginCode: String get() = prefs.getString("loginCode", "") ?: ""; set(v) = prefs.edit().putString("loginCode", v).apply()
    var userId: String get() = prefs.getString("userId", "") ?: ""; set(v) = prefs.edit().putString("userId", v).apply()
    var accountId: String get() = prefs.getString("accountId", "") ?: ""; set(v) = prefs.edit().putString("accountId", v).apply()
    var projectId: String get() = prefs.getString("projectId", "") ?: ""; set(v) = prefs.edit().putString("projectId", v).apply()
    var telephone: String get() = prefs.getString("telephone", "") ?: ""; set(v) = prefs.edit().putString("telephone", v).apply()
    var userName: String get() = prefs.getString("userName", "") ?: ""; set(v) = prefs.edit().putString("userName", v).apply()
    var studentNo: String get() = prefs.getString("studentNo", "") ?: ""; set(v) = prefs.edit().putString("studentNo", v).apply()
    var schoolName: String get() = prefs.getString("schoolName", "江苏大学") ?: "江苏大学"; set(v) = prefs.edit().putString("schoolName", v).apply()
    val isLoggedIn: Boolean get() = loginCode.isNotEmpty()

    internal val waterWidgetOwner: String?
        get() = if (canStoreSessions && isLoggedIn && userId.isNotBlank() && telephone.isNotBlank())
            "$userId:$telephone" else null

    private data class WaterWidgetBinding(
        val owner: String,
        val device: RecentDevice? = null,
        val cold: RecentDevice? = null,
        val hot: RecentDevice? = null,
        val compactHot: Boolean = false
    )

    internal data class WaterWidgetDevices(
        val cold: RecentDevice? = null, val hot: RecentDevice? = null, val compactHot: Boolean = false
    ) {
        fun device(hotWater: Boolean): RecentDevice? = if (hotWater) hot else cold
    }

    internal fun bindWaterWidget(id: Int, owner: String, devices: WaterWidgetDevices): Boolean =
        owner == waterWidgetOwner && prefs.edit().putString("waterWidget_$id",
            gson.toJson(WaterWidgetBinding(owner, cold = devices.cold, hot = devices.hot, compactHot = devices.compactHot))).commit()

    internal fun waterWidgetDevices(id: Int): WaterWidgetDevices {
        val owner = waterWidgetOwner ?: return WaterWidgetDevices()
        val saved = runCatching {
            gson.fromJson(prefs.getString("waterWidget_$id", null), WaterWidgetBinding::class.java)
        }.getOrNull()?.takeIf { it.owner == owner } ?: return WaterWidgetDevices()
        return WaterWidgetDevices(
            (saved.cold ?: saved.device)?.takeIf { !it.isHot && it.snCode.isNotBlank() },
            (saved.hot ?: saved.device)?.takeIf { it.isHot && it.snCode.isNotBlank() },
            saved.compactHot
        )
    }

    internal fun removeWaterWidgets(ids: IntArray) {
        val edit = prefs.edit()
        ids.forEach { edit.remove("waterWidget_$it") }
        edit.apply()
    }

    // ── 后勤专区 (江大热水) ──
    var qzhqLoginCode: String get() = prefs.getString("qzhqLoginCode", "") ?: ""; set(v) = prefs.edit().putString("qzhqLoginCode", v).apply()
    var qzhqPhone: String get() = prefs.getString("qzhqPhone", "") ?: ""; set(v) = prefs.edit().putString("qzhqPhone", v).apply()
    var qzhqProjectId: String get() = prefs.getString("qzhqProjectId", "43") ?: "43"; set(v) = prefs.edit().putString("qzhqProjectId", v).apply()
    var qzhqCustomCode: String
        get() = bathPrefix(qzhqProjectId)
        set(value) { saveBathPrefix(qzhqProjectId, value) }

    fun bathPrefix(projectId: String): String {
        if (projectId == qzhqProjectId) migrateBathPrefix()
        val key = bathPrefixKey(projectId) ?: return ""
        return prefs.getString(key, "").orEmpty()
    }

    fun migrateBathPrefix() {
        val key = bathPrefixKey(qzhqProjectId) ?: return
        val legacy = prefs.getString("qzhqCustomCode", "").orEmpty()
        if (!cn.mudlife.app.model.BathCode.isPrefix(legacy)) return
        val edit = prefs.edit().remove("qzhqCustomCode")
        if (!prefs.contains(key)) edit.putString(key, legacy)
        edit.commit()
    }

    fun saveBathPrefix(projectId: String, value: String): Boolean {
        require(cn.mudlife.app.model.BathCode.isPrefix(value))
        val key = bathPrefixKey(projectId) ?: return false
        return prefs.edit().putString(key, value).commit()
    }

    fun forgetBathPrefix(projectId: String) {
        val key = bathPrefixKey(projectId) ?: return
        prefs.edit().remove(key).apply()
    }

    private fun bathPrefixKey(projectId: String): String? =
        cn.mudlife.app.model.BathCode.storageKey(qzhqPhone.ifEmpty { telephone }, projectId)

    var qzhqSessionExpired: Boolean get() = prefs.getBoolean("qzhqSessionExpired", false); set(v) = prefs.edit().putBoolean("qzhqSessionExpired", v).apply()
    val isQzhqLoggedIn: Boolean get() = qzhqLoginCode.isNotEmpty() && !qzhqSessionExpired

    fun saveAuth(lc: String, uid: String, aid: String, pid: String, phone: String, name: String?, sno: String? = null) {
        check(canStoreSessions) { "设备的安全存储不可用，请重启后重试" }
        if ((userId.isNotBlank() && userId != uid) || (telephone.isNotBlank() && telephone != phone)) {
            clearLifeAccount()
        }
        val edit = prefs.edit().putString("loginCode", lc).putString("userId", uid)
            .putString("accountId", aid).putString("projectId", pid).putString("telephone", phone)
            .putString("userName", name.orEmpty())
        if (!sno.isNullOrEmpty()) edit.putString("studentNo", sno)
        val prefix = orderArchivePrefix(uid, phone)
        if (prefix != null) {
            for ((key, value) in prefs.all) {
                if (!key.startsWith(prefix)) continue
                val field = key.removePrefix(prefix)
                when (value) {
                    is String -> edit.putString(field, value)
                    is Long -> edit.putLong(field, value)
                }
                edit.remove(key)
            }
        }
        check(edit.commit())
    }

    private fun orderArchivePrefix(uid: String, phone: String): String? =
        if (uid.isNotBlank() && phone.isNotBlank()) "savedDeviceOrders_${uid}_${phone}:" else null

    /** Keep only unresolved orders for re-verification when the same device account returns. */
    fun clearLifeAccount() {
        val edit = prefs.edit()
        val prefix = orderArchivePrefix(userId, telephone)
        val orders = getActiveOrders()
        if (prefix != null && orders.isNotEmpty()) {
            edit.putString(prefix + "activeOrders", gson.toJson(orders))
            for (order in orders) {
                for (field in listOf("startedAt_${order.snCode}", autoDisconKey(order.snCode))) {
                    if (prefs.contains(field)) edit.putLong(prefix + field, prefs.getLong(field, 0L))
                }
            }
        }
        listOf("loginCode", "userId", "accountId", "projectId", "telephone", "userName", "studentNo", "schoolName",
            "lastDeviceName", "lastDeviceMac", "lastDeviceSnCode", "lastDeviceEmoji", "boundRoom", "recentDevices",
            "activeOrders", "projectAccounts", "useCode", "useCodeStatus", "useCodeLastConfirmedAt").forEach { edit.remove(it) }
        prefs.all.keys.filter { it.startsWith("startedAt_") || it.startsWith("autoDiscon_") }.forEach { edit.remove(it) }
        edit.apply()
    }
    fun clear() {
        val editor = prefs.edit()
        // 清除所有已知固定 key
        listOf(
            "loginCode", "userId", "accountId", "projectId",
            "telephone", "userName", "studentNo", "schoolName",
            "lastDeviceName", "lastDeviceMac", "lastDeviceSnCode", "lastDeviceEmoji",
            "boundRoom", "recentDevices", "activeOrders", "projectAccounts",
            // 后勤专区凭证
            "qzhqLoginCode", "qzhqPhone", "qzhqProjectId",
            "qzhqRandomCode", "qzhqCustomCode", "qzhqStatus", "qzhqSessionExpired",
            // 校园卡与吹风机
            "campusCardBalance", "campusCardBalanceTime", "useCode", "useCodeStatus", "useCodeLastConfirmedAt"
        ).forEach { editor.remove(it) }
        // 动态前缀 key 使用 getAll() 遍历删除
        prefs.all.keys.filter {
            it.startsWith("startedAt_") || it.startsWith("autoDiscon_")
        }.forEach { editor.remove(it) }
        editor.apply()
    }

    // ── 多项目账户映射 (projectId -> accountId) ──
    fun saveProjectAccounts(map: Map<String, String>) {
        val json = gson.toJson(map)
        prefs.edit().putString("projectAccounts", json).apply()
    }

    fun getProjectAccounts(): Map<String, String> {
        val json = prefs.getString("projectAccounts", "{}") ?: "{}"
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ── Last device ──
    var lastDeviceName: String get() = prefs.getString("lastDeviceName", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceName", v).apply()
    var lastDeviceMac: String get() = prefs.getString("lastDeviceMac", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceMac", v).apply()
    var lastDeviceSnCode: String get() = prefs.getString("lastDeviceSnCode", "") ?: ""; set(v) = prefs.edit().putString("lastDeviceSnCode", v).apply()
    var lastDeviceEmoji: String get() = prefs.getString("lastDeviceEmoji", "🚰") ?: "🚰"; set(v) = prefs.edit().putString("lastDeviceEmoji", v).apply()

    // ── 常用/历史饮水机列表 ──
    fun getRecentDevices(): MutableList<cn.mudlife.app.model.RecentDevice> {
        val json = prefs.getString("recentDevices", "[]") ?: "[]"
        val result = try {
            val array = JsonParser().parse(json).asJsonArray
            val list = mutableListOf<cn.mudlife.app.model.RecentDevice>()
            for (item in array) {
                list.add(gson.fromJson(item, cn.mudlife.app.model.RecentDevice::class.java))
            }
            list
        } catch (_: Exception) { mutableListOf() }

        if (result.isEmpty() && lastDeviceSnCode.isNotEmpty()) {
            val wType = if (lastDeviceSnCode.contains(",G,") || lastDeviceName.contains("冷")) "冷水"
                        else if (lastDeviceSnCode.contains(",M,") || lastDeviceName.contains("热")) "热水"
                        else "直饮水"
            val dev = cn.mudlife.app.model.RecentDevice(
                name = lastDeviceName,
                mac = lastDeviceMac,
                snCode = lastDeviceSnCode,
                emoji = lastDeviceEmoji,
                waterType = wType
            )
            result.add(dev)
        }
        return result
    }

    fun addRecentDevice(device: cn.mudlife.app.model.RecentDevice) {
        val list = getRecentDevices()
        val existing = list.find { it.snCode == device.snCode }
        val finalDevice = if (device.projectId.isNullOrEmpty() && !existing?.projectId.isNullOrEmpty()) {
            device.copy(projectId = existing?.projectId)
        } else {
            device
        }
        val index = list.indexOfFirst { it.snCode == device.snCode }
        if (index >= 0) list[index] = finalDevice else list.add(0, finalDevice)
        val trimmed = list.take(4)
        prefs.edit().putString("recentDevices", gson.toJson(trimmed)).apply()
        lastDeviceName = finalDevice.name
        lastDeviceMac = finalDevice.mac
        lastDeviceSnCode = finalDevice.snCode
        lastDeviceEmoji = finalDevice.emoji
    }

    internal fun moveRecentDevice(snCode: String, targetSnCode: String): List<RecentDevice> {
        val list = getRecentDevices()
        val from = list.indexOfFirst { it.snCode == snCode }
        val to = list.indexOfFirst { it.snCode == targetSnCode }
        if (from >= 0 && to >= 0 && from != to) {
            list.add(to, list.removeAt(from))
            prefs.edit().putString("recentDevices", gson.toJson(list)).apply()
        }
        return list
    }

    fun deleteRecentDevice(snCode: String) {
        val list = getRecentDevices()
        list.removeAll { it.snCode == snCode }
        prefs.edit().putString("recentDevices", gson.toJson(list)).apply()
        if (lastDeviceSnCode == snCode) {
            lastDeviceName = ""
            lastDeviceMac = ""
            lastDeviceSnCode = ""
            lastDeviceEmoji = "🚰"
        }
    }

    // ── 绑定的寝室（设备筛选关键词） ──
    var boundRoom: String get() = prefs.getString("boundRoom", "") ?: ""; set(v) = prefs.edit().putString("boundRoom", v).apply()

    // ── Active orders list ──
    fun getActiveOrders(): MutableList<ActiveOrder> {
        val json = prefs.getString("activeOrders", "[]") ?: "[]"
        return try {
            val array = JsonParser().parse(json).asJsonArray
            val result = mutableListOf<ActiveOrder>()
            for (item in array) {
                result.add(gson.fromJson(item, ActiveOrder::class.java))
            }
            result
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveActiveOrders(orders: List<ActiveOrder>): Boolean {
        return prefs.edit().putString("activeOrders", gson.toJson(orders)).commit()
    }

    @Synchronized
    internal fun writeDeviceOrder(snCode: String, order: ActiveOrder?): Boolean {
        val orders = getActiveOrders().filterNot { it.snCode == snCode }
        return saveActiveOrders(if (order == null) orders else orders + order)
    }

    fun clearActiveOrders() = prefs.edit().remove("activeOrders").apply()

    fun appearance(context: Context): AppAppearance {
        if (!::prefs.isInitialized) init(context.applicationContext)
        return AppAppearance(
            ThemeMode.restore(prefs.getString("themeMode", "").orEmpty()),
            ThemePalette.restore(prefs.getString("themePalette", "").orEmpty())
        )
    }

    fun saveAppearance(appearance: AppAppearance) {
        prefs.edit().putString("themeMode", appearance.mode.name)
            .putString("themePalette", appearance.palette.name).apply()
    }

    var manualBalance: String get() = prefs.getString("manualBalance", "") ?: ""; set(v) = prefs.edit().putString("manualBalance", v).apply()
    var manualBalanceTime: Long get() = prefs.getLong("manualBalanceTime", 0L); set(v) = prefs.edit().putLong("manualBalanceTime", v).apply()

    // ── Per-device timer ──
    fun getStartedAt(snCode: String): Long = prefs.getLong("startedAt_$snCode", 0L)
    fun setStartedAt(snCode: String, v: Long) = prefs.edit().putLong("startedAt_$snCode", v).apply()

    // ── 自动关停倒计时（以毫秒时间戳持久化，App 重启后可恢复） ──
    private fun autoDisconKey(snCode: String) = "autoDiscon_$snCode"

    /** 剩余秒数，依据持久化的截止时间戳计算；无记录返回 0 */
    fun getAutoDisconRemain(snCode: String): Int {
        val deadline = prefs.getLong(autoDisconKey(snCode), 0L)
        if (deadline <= 0L) return 0
        val remain = ((deadline - System.currentTimeMillis()) / 1000).toInt()
        return if (remain > 0) remain else 0
    }

    fun deviceDeadline(snCode: String, seconds: Int): Long {
        val key = autoDisconKey(snCode)
        val stored = prefs.getLong(key, 0L)
        if (stored > 0L) return stored
        val deadline = System.currentTimeMillis() + seconds * 1000L
        prefs.edit().putLong(key, deadline).apply()
        return deadline
    }

    fun clearAutoDiscon(snCode: String) = prefs.edit().remove(autoDisconKey(snCode)).apply()

    // ── 校园卡余额（洗浴结算同步） ──
    var campusCardBalance: String
        get() = prefs.getString("campusCardBalance", "") ?: ""
        set(v) = prefs.edit().putString("campusCardBalance", v).apply()

    var campusCardBalanceTime: Long
        get() = prefs.getLong("campusCardBalanceTime", 0L)
        set(v) = prefs.edit().putLong("campusCardBalanceTime", v).apply()

    // ── 吹风机使用码（趣智校园原生） ──
    var useCode: String
        get() = prefs.getString("useCode", "") ?: ""
        set(v) = prefs.edit().putString("useCode", v).apply()

    var useCodeStatus: Boolean
        get() = prefs.getBoolean("useCodeStatus", true)
        set(v) = prefs.edit().putBoolean("useCodeStatus", v).apply()

    var useCodeLastConfirmedAt: Long
        get() = prefs.getLong("useCodeLastConfirmedAt", 0L)
        set(v) = prefs.edit().putLong("useCodeLastConfirmedAt", v).apply()
}
