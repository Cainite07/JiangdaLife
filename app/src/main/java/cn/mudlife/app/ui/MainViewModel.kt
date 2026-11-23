package cn.mudlife.app.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.mudlife.app.api.NetworkModule
import cn.mudlife.app.api.closeOrderSafe
import cn.mudlife.app.api.closeOrderResultSafe
import cn.mudlife.app.api.downRateSafe
import cn.mudlife.app.api.downRateResultSafe
import cn.mudlife.app.api.getUserProjectsSafe
import cn.mudlife.app.api.getDeviceInfoSafe
import cn.mudlife.app.api.QzhqNetwork
import cn.mudlife.app.api.getUseCodeSafe
import cn.mudlife.app.api.setUseCodeSafe
import cn.mudlife.app.api.updateUseCodeStatusSafe
import cn.mudlife.app.api.generateUseCodeSafe
import cn.mudlife.app.api.getWalletSafe
import cn.mudlife.app.api.queryUsingSafe
import cn.mudlife.app.model.ActiveOrder
import cn.mudlife.app.model.BathCode
import cn.mudlife.app.model.QzhqProject
import cn.mudlife.app.model.QzhqUseCodeData
import cn.mudlife.app.model.UseCodeData
import cn.mudlife.app.model.DeviceInfo
import cn.mudlife.app.model.MqttOrderMsg
import cn.mudlife.app.model.WalletData
import cn.mudlife.app.model.OrderVerification
import cn.mudlife.app.data.monitorDeviceOrder
import cn.mudlife.app.data.queryDeviceSettlement
import cn.mudlife.app.model.verifyOrder
import cn.mudlife.app.utils.MqttManager
import cn.mudlife.app.utils.PrefsHelper
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import cn.mudlife.app.utils.AccountRequests
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

class MainViewModel(
    private val queryOrderStatus: suspend (ActiveOrder) -> cn.mudlife.app.model.BaseResponse<cn.mudlife.app.model.OrderStatus> = { order ->
        NetworkModule.apiService.queryUsingSafe(snCode = order.snCode,
            auth = NetworkModule.authFields(overrideProjectId = order.projectId))
    }
) : ViewModel() {

    var walletInfo by mutableStateOf<WalletData?>(null)
    private var lastWalletOwner: String? = null
    private var lastWalletRequestAt = 0L

    var isShowering by mutableStateOf(false)
    var isStartingShower by mutableStateOf(false)
    var isStopping by mutableStateOf(false)
    var showerSnCode by mutableStateOf<String?>(null)
    var currentOrderNo by mutableStateOf<String?>(null)
    var showerConsumed by mutableStateOf(0.0)
    var showerPreDeduct by mutableStateOf(0.0)
    var showerRemaining by mutableStateOf("0.00")
    var showerElapsedSec by mutableStateOf(0)
    var autoDisConSec by mutableStateOf(0)
    var showerError by mutableStateOf<String?>(null)
    var isOwner by mutableStateOf(true)

    // 自动关停确认弹窗
    var showAutoCloseDialog by mutableStateOf(false)
    var autoCloseDeviceName by mutableStateOf("")
    var autoCloseElapsed by mutableStateOf(0)
    var autoCloseConsumed by mutableStateOf<Double?>(null)
    var autoCloseLoading by mutableStateOf(false)

    var toastMessage by mutableStateOf<String?>(null)
    var kickedOut by mutableStateOf(false)

    var selectedDevice by mutableStateOf<DeviceInfo?>(null)
    var showDeviceDetail by mutableStateOf(false)

    var lastDeviceName by mutableStateOf("")
    var lastDeviceMac by mutableStateOf("")
    var lastDeviceSnCode by mutableStateOf("")
    var lastDeviceEmoji by mutableStateOf("🚰")

    // 常用饮水机列表
    var recentDevices by mutableStateOf<List<cn.mudlife.app.model.RecentDevice>>(emptyList())

    val activeOrders = mutableStateListOf<ActiveOrder>()
    val unverifiedOrders = mutableStateListOf<ActiveOrder>()
    var isVerifyingOrders by mutableStateOf(false)
        private set
    private var managersInitialized = false
    private var orderVerificationJob: Job? = null
    private var accountGeneration = 0L
    private val lifeRequests = AccountRequests(viewModelScope) { listOf(PrefsHelper.loginCode, PrefsHelper.userId) }
    internal val deviceBills = DeviceBillStore(viewModelScope)
    private val bathRequests = AccountRequests(viewModelScope) { listOf(PrefsHelper.qzhqLoginCode, PrefsHelper.qzhqPhone.ifEmpty { PrefsHelper.telephone }) }
    private var bathLoginJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    var isRefreshing by mutableStateOf(false); private set
    private var activeDeviceSnCodes = mutableSetOf<String>()
    private val gson = Gson()
    private var mqttManager: MqttManager? = null
    private var timerJob: Job? = null
    private var startDeviceJob: Job? = null
    private var stopDeviceJob: Job? = null
    private var settlementJob: Job? = null
    private var deviceSessionGeneration = 0
    private var deviceForeground = true
    private var sessionDevice: DeviceInfo? = null

    fun setDeviceForeground(foreground: Boolean) {
        if (deviceForeground == foreground) return
        deviceForeground = foreground
        val snCode = showerSnCode ?: return
        val device = sessionDevice ?: return
        if (isShowering && !isStopping && !showAutoCloseDialog) {
            monitorDevice(snCode, device, immediate = foreground)
        }
    }
    private var probeJob: Job? = null

    var useCodeData by mutableStateOf<UseCodeData?>(
        if (PrefsHelper.useCode.isNotEmpty()) {
            UseCodeData(
                useCode = PrefsHelper.useCode,
                useCodeStatus = if (PrefsHelper.useCodeStatus) 1 else 0
            )
        } else null
    )
    // ── 吹风机使用码 20 次抽选架构 ──
    var candidateUseCode by mutableStateOf("")
    var remainUseCodeTimes by mutableStateOf(20)
    var isRollingUseCode by mutableStateOf(false)
    var showDryerCodeModal by mutableStateOf(false)

    // ── 国内免翻墙检查更新架构 ──
    var isCheckingUpdate by mutableStateOf(false)
    var updateInfo by mutableStateOf<cn.mudlife.app.utils.UpdateInfo?>(null)
    var showUpdateDialog by mutableStateOf(false)
    var isDownloadingUpdate by mutableStateOf(false)
    var updateDownload by mutableStateOf(cn.mudlife.app.utils.UpdateDownloadProgress())
        private set
    var updateError by mutableStateOf<String?>(null); private set

    var qzhqUseCodeData by mutableStateOf<QzhqUseCodeData?>(null)
        private set
    var qzhqSessionExpired by mutableStateOf(PrefsHelper.qzhqSessionExpired)
        private set
    var isQzhqLoggedIn by mutableStateOf(PrefsHelper.isQzhqLoggedIn)
        private set
    var qzhqLoading by mutableStateOf(false)
        private set
    var qzhqCodeError by mutableStateOf<String?>(null)
        private set
    var qzhqProjectId by mutableStateOf(PrefsHelper.qzhqProjectId)
        private set
    var qzhqProjects by mutableStateOf<List<QzhqProject>>(emptyList())
        private set
    var qzhqProjectsError by mutableStateOf(false)
        private set
    var qzhqPrefix by mutableStateOf("")
        private set

    fun rememberQzhqPrefix(value: String) {
        if (!isQzhqLoggedIn || qzhqUseCodeData?.isSet != true || !BathCode.isPrefix(value)) return
        if (PrefsHelper.saveBathPrefix(qzhqProjectId, value)) qzhqPrefix = value
        else toastMessage = "前五位保存失败，请重试"
    }

    fun initManagers(context: Context) {
        if (managersInitialized) return
        managersInitialized = true
        syncProjectAccounts()
        if (mqttManager == null) mqttManager = MqttManager(context, { handleMqttMessage(it) })

        lastDeviceName = PrefsHelper.lastDeviceName
        lastDeviceMac = PrefsHelper.lastDeviceMac
        lastDeviceSnCode = PrefsHelper.lastDeviceSnCode
        lastDeviceEmoji = PrefsHelper.lastDeviceEmoji

        // Cached orders stay unverified until the server confirms their current state.
        val saved = PrefsHelper.getActiveOrders()
        activeOrders.clear()
        unverifiedOrders.clear()
        unverifiedOrders.addAll(saved)
        recentDevices = PrefsHelper.getRecentDevices()

        if (saved.isNotEmpty()) {
            cleanInactiveOrders()
        }
    }

    fun cleanInactiveOrders() {
        if (orderVerificationJob?.isActive == true) return
        val generation = accountGeneration
        orderVerificationJob = viewModelScope.launch {
            isVerifyingOrders = true
            val current = (activeOrders.toList() + unverifiedOrders.toList()).distinctBy { it.snCode }
            try {
                current.forEach { order ->
                try {
                    val q = queryOrderStatus(order)
                    // Ignore results from a previous login or an order replaced while awaiting HTTP.
                    if (generation != accountGeneration) return@launch
                    if (order !in activeOrders && order !in unverifiedOrders) return@forEach
                    if (isStartingShower && showerSnCode == order.snCode) return@forEach
                    when (cn.mudlife.app.model.verifyOrder(q)) {
                        OrderVerification.ACTIVE -> {
                            unverifiedOrders.remove(order)
                            if (activeOrders.none { it.snCode == order.snCode }) {
                                activeOrders.add(order.copy(orderNo = q.data?.orderNo ?: order.orderNo))
                            }
                            activeDeviceSnCodes.add(order.snCode)
                            saveOrders()
                        }
                        OrderVerification.ENDED -> clearActiveRecord(order.snCode)
                        OrderVerification.UNKNOWN -> Unit
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
                }
            } finally {
                if (generation == accountGeneration) isVerifyingOrders = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        orderVerificationJob?.cancel()
        timerJob?.cancel(); probeJob?.cancel()
        mqttManager?.disconnect()
    }

    // ── 扫码绑定 ──
    fun scanBind(snCode: String) {
        viewModelScope.launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(snCode)
                if (resp.success && resp.data != null) {
                    val info = resp.data
                    // 保存为常用设备
                    val waterType = if (info.snCode.contains(",G,") || info.displayName.contains("冷")) "冷水"
                                    else if (info.snCode.contains(",M,") || info.displayName.contains("热")) "热水"
                                    else "直饮水"
                    val recentDev = cn.mudlife.app.model.RecentDevice(
                        name = info.displayName,
                        mac = info.macAddress,
                        snCode = info.snCode,
                        emoji = info.typeEmoji,
                        waterType = waterType,
                        projectId = info.projectId?.toString()
                    )
                    PrefsHelper.addRecentDevice(recentDev)
                    recentDevices = PrefsHelper.getRecentDevices()
                    // 弹出设备详情
                    selectedDevice = info; showDeviceDetail = true
                    refreshDeviceStatus(info.snCode, info.projectId?.toString())
                } else {
                    toastMessage = resp.displayMessage ?: "未找到该设备"
                    checkKick(resp.displayMessage)
                }
            } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: ""
                toastMessage = if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    "网络连接失败，请检查网络设置"
                } else {
                    "查询设备失败"
                }
            }
        }
    }

    // ── 点击设备 ──
    fun fetchDeviceInfo(mac: String) {
        viewModelScope.launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(mac)
                if (resp.success && resp.data != null) {
                    selectedDevice = resp.data; showDeviceDetail = true
                    refreshDeviceStatus(resp.data.snCode, resp.data.projectId?.toString())
                } else checkKick(resp.displayMessage)
            } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: ""
                if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    toastMessage = "网络连接失败，请检查网络设置"
                }
            }
        }
    }

    private suspend fun refreshDeviceStatus(snCode: String, overrideProjectId: String? = null) {
        val owner = PrefsHelper.loginCode
        val generation = accountGeneration
        try {
            val pid = overrideProjectId ?: selectedDevice?.projectId?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
            val q = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = NetworkModule.authFields(overrideProjectId = pid))
            if (owner != PrefsHelper.loginCode || generation != accountGeneration) return
            if (q.errorCode == 307 || (q.success && q.data?.orderNo != null)) {
                activeDeviceSnCodes.add(snCode)
                isOwner = q.data?.isOwner ?: true
                // 只有自己的订单才加入 activeOrders
                if (isOwner && activeOrders.none { it.snCode == snCode }) {
                    val orderNo = q.data?.orderNo ?: ""
                    val deviceInfo = selectedDevice
                    if (deviceInfo != null) {
                        activeOrders.add(ActiveOrder(snCode, orderNo, deviceInfo.displayName, deviceInfo.macAddress, deviceInfo.typeEmoji, deviceInfo.withholdMoney, projectId = pid))
                        saveOrders()
                    }
                }
            } else isOwner = true
        } catch (_: Exception) {}
    }

    fun startLastDevice(phone: String) {
        if (isStartingShower || isShowering || isStopping) return
        val sn = lastDeviceSnCode.ifEmpty { return }
        // 找对应的活跃订单
        val order = activeOrders.find { it.snCode == sn }
        if (order != null) {
            viewModelScope.launch {
                try {
                    val resp = NetworkModule.apiService.getDeviceInfoSafe(order.deviceMac)
                    if (resp.success && resp.data != null) {
                        selectedDevice = resp.data; startShower(phone)
                    } else {
                        toastMessage = "获取设备信息失败"
                    }
                } catch (e: Exception) {
                    toastMessage = "获取设备信息失败"
                }
            }
            return
        }
        val mac = lastDeviceMac.ifEmpty { return }
        viewModelScope.launch {
            try {
                val resp = NetworkModule.apiService.getDeviceInfoSafe(mac)
                if (resp.success && resp.data != null) {
                    selectedDevice = resp.data; startShower(phone)
                } else {
                    toastMessage = "获取设备信息失败"
                }
            } catch (e: Exception) {
                toastMessage = "获取设备信息失败"
            }
        }
    }

    fun startRecentDevice(dev: cn.mudlife.app.model.RecentDevice, phone: String) {
        if (isStartingShower || isShowering || isStopping) return
        lastDeviceSnCode = dev.snCode
        lastDeviceMac = dev.mac
        lastDeviceName = dev.name
        lastDeviceEmoji = dev.emoji
        viewModelScope.launch {
            try {
                val queryKey = if (dev.mac.isNotEmpty()) dev.mac else dev.snCode
                val resp = NetworkModule.apiService.getDeviceInfoSafe(queryKey)
                if (resp.success && resp.data != null) {
                    val info = resp.data
                    selectedDevice = if (info.projectId == null && dev.projectId != null) {
                        info.copy(projectId = dev.projectId.toIntOrNull())
                    } else info
                    startShower(phone)
                } else {
                    toastMessage = resp.displayMessage ?: "获取饮水机信息失败"
                }
            } catch (e: Exception) {
                toastMessage = "网络连接失败，请重试"
            }
        }
    }

    fun pinRecentDrinkingDevice(snCode: String) {
        PrefsHelper.pinRecentDevice(snCode)
        recentDevices = PrefsHelper.getRecentDevices()
    }

    fun deleteRecentDrinkingDevice(snCode: String) {
        PrefsHelper.deleteRecentDevice(snCode)
        recentDevices = PrefsHelper.getRecentDevices()
    }

    // ════════════════════════════════════════════
    fun startShower(phone: String) {
        if (kickedOut || isStartingShower || isShowering || isStopping) return
        val device = selectedDevice ?: return
        val snCode = device.snCode
        if (snCode.isNullOrBlank()) {
            showerError = "设备信息不完整"
            toastMessage = "设备信息不完整"
            return
        }

        isStartingShower = true
        val session = ++deviceSessionGeneration
        settlementJob?.cancel()
        startDeviceJob = viewModelScope.launch {
            try {
                showerError = null
                showerSnCode = snCode
                val devPid = device.projectId?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
                // 若该项目尚未同步过 accountId，主动拉取一次多项目列表（如直饮水 3255 匹配 38184）
                if (devPid != null && NetworkModule.getAccountIdForProject(devPid) == null) {
                    try {
                        val pResp = NetworkModule.apiService.getUserProjectsSafe()
                        if (pResp.success && !pResp.data.isNullOrEmpty()) {
                            val map = pResp.data.associate { it.projectId.toString() to it.accountId.toString() }
                            NetworkModule.setProjectAccounts(map)
                            PrefsHelper.saveProjectAccounts(map)
                            cn.mudlife.app.utils.AppLogger.i("Shower", "开阀前补全多项目账户映射")
                        }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
                }
                val devAuth = NetworkModule.authFields(overrideProjectId = devPid)

                val existing = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = devAuth)
                if (existing.data?.isOwner == false) {
                    toastMessage = "该设备正在使用中"
                    return@launch
                }
                if (verifyOrder(existing) == OrderVerification.UNKNOWN) {
                    toastMessage = existing.displayMessage ?: "暂时无法确认设备状态，请重试"
                    checkKick(existing.displayMessage)
                    return@launch
                }
                if (verifyOrder(existing) == OrderVerification.ACTIVE) {
                    val oNo = existing.data?.orderNo?.takeIf(String::isNotBlank)
                    unverifiedOrders.removeAll { it.snCode == snCode }
                    if (activeOrders.none { it.snCode == snCode }) {
                        activeOrders.add(ActiveOrder(snCode, oNo.orEmpty(), device.displayName, device.macAddress, device.typeEmoji, device.withholdMoney, projectId = devPid))
                    }
                    saveOrders()
                    // 恢复订单：从持久化恢复自动关停剩余时间
                    val remain = PrefsHelper.getAutoDisconRemain(snCode)
                    enterShowerState(oNo, snCode, device, remain)
                    return@launch
                }

                val pending = ActiveOrder(snCode, "", device.displayName, device.macAddress,
                    device.typeEmoji, device.withholdMoney, projectId = devPid)
                PrefsHelper.setStartedAt(snCode, System.currentTimeMillis())
                PrefsHelper.clearAutoDiscon(snCode)
                unverifiedOrders.removeAll { it.snCode == snCode }
                unverifiedOrders.add(pending)
                saveOrders()
                mqttManager?.connect(phone)
                val resp = NetworkModule.apiService.downRateSafe(snCode = snCode, auth = devAuth)
                if (!resp.success) {
                    if (resp.errorCode > 0 && resp.errorCode != 307 && unverifiedOrders.remove(pending)) {
                        PrefsHelper.setStartedAt(snCode, 0L)
                        saveOrders()
                    }
                    val msg = resp.displayMessage ?: "开始失败"
                    showerError = msg
                    toastMessage = "出水失败: $msg"
                    cn.mudlife.app.utils.AppLogger.e("Shower", "服务未接受开阀请求")
                    checkKick(resp.displayMessage); mqttManager?.disconnect(); return@launch
                }

                // 开阀确认：轮询 downRateResult，确认开阀成功
                var opened = false
                var autoDiscon = resp.data?.autoDisConTime ?: 0
                for (i in 0..8) {
                    delay(700)
                    try {
                        val r = NetworkModule.apiService.downRateResultSafe(snCode = snCode, auth = devAuth)
                        val d = r.data
                        if (r.success && (d?.state == 0 || d?.result == 0 || d?.orderNo != null)) {
                            opened = true
                            val autoTime = d?.autoDisConTime
                            if (autoTime != null && autoTime > 0) {
                                autoDiscon = autoTime
                            }
                            break
                        }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
                }

                if (!opened) {
                    // 开阀确认失败，退出并提示
                    showerError = "开阀未确认成功，请确认设备是否已开启"
                    toastMessage = "开阀未确认，请检查水机或网络重试"
                    cn.mudlife.app.utils.AppLogger.e("Shower", "开阀确认失败")
                    mqttManager?.disconnect()
                    return@launch
                }

                unverifiedOrders.remove(pending)
                activeOrders.removeAll { it.snCode == snCode }
                activeOrders.add(pending)
                saveOrders()
                enterShowerState(null, snCode, device, autoDiscon)

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: "网络错误"
                if (!isShowering) {
                    showerError = msg
                    toastMessage = "出水异常: $msg"
                }
            } finally { if (deviceSessionGeneration == session) isStartingShower = false }
        }
    }

    private fun updateOrderNo(snCode: String, orderNo: String) {
        val i = activeOrders.indexOfFirst { it.snCode == snCode }
        if (i >= 0 && orderNo.isNotEmpty()) {
            activeOrders[i] = activeOrders[i].copy(orderNo = orderNo)
            saveOrders()
        }
    }

    private fun enterShowerState(orderNo: String?, snCode: String, device: DeviceInfo, autoDiscon: Int = 0) {
        sessionDevice = device
        currentOrderNo = orderNo; isShowering = true
        showerPreDeduct = device.withholdMoney; showerConsumed = 0.0
        showerRemaining = "%.2f".format(device.withholdMoney)
        activeDeviceSnCodes.add(snCode)

        val restoredRemaining = PrefsHelper.getAutoDisconRemain(snCode)
        autoDisConSec = restoredRemaining.takeIf { it > 0 } ?: autoDiscon
        val deadline = if (autoDisConSec > 0) {
            PrefsHelper.deviceDeadline(snCode, autoDisConSec)
        } else 0L

        val now = System.currentTimeMillis()
        val st = PrefsHelper.getStartedAt(snCode)
        val isValidSt = st > 0L && st <= now && (now - st) < 4 * 3600 * 1000L
        if (!isValidSt) {
            PrefsHelper.setStartedAt(snCode, now)
            showerElapsedSec = 0
        } else {
            showerElapsedSec = ((now - st) / 1000).toInt()
        }

        lastDeviceName = device.displayName; lastDeviceMac = device.macAddress; lastDeviceSnCode = snCode; lastDeviceEmoji = device.typeEmoji
        val waterType = if (snCode.contains(",G,") || device.displayName.contains("冷")) "冷水"
                        else if (snCode.contains(",M,") || device.displayName.contains("热")) "热水"
                        else "直饮水"
        val recentDev = cn.mudlife.app.model.RecentDevice(
            name = device.displayName,
            mac = device.macAddress,
            snCode = snCode,
            emoji = device.typeEmoji,
            waterType = waterType,
            projectId = device.projectId?.toString()
        )
        PrefsHelper.addRecentDevice(recentDev)
        recentDevices = PrefsHelper.getRecentDevices()

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val elapsedStart = SystemClock.elapsedRealtime() - showerElapsedSec * 1000L
            while (isShowering) {
                showerElapsedSec = ((SystemClock.elapsedRealtime() - elapsedStart) / 1000L).toInt()
                autoDisConSec = if (deadline > 0) {
                    ((deadline - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(0L).toInt()
                } else 0
                delay(1000)
            }
        }
        monitorDevice(snCode, device)
    }

    private fun monitorDevice(snCode: String, device: DeviceInfo, immediate: Boolean = false) {
        probeJob?.cancel()
        val session = deviceSessionGeneration
        val account = accountGeneration
        val projectId = activeOrders.find { it.snCode == snCode }?.projectId
            ?: device.projectId?.toString()?.takeIf { it != "0" }
        probeJob = viewModelScope.launch {
            monitorDeviceOrder(
                drinkingWater = device.isDrinkingWater,
                foreground = deviceForeground,
                immediate = immediate,
                current = {
                    session == deviceSessionGeneration && account == accountGeneration &&
                        isShowering && showerSnCode == snCode && !isStopping && !showAutoCloseDialog
                },
                query = {
                    NetworkModule.apiService.queryUsingSafe(
                        snCode = snCode, auth = NetworkModule.authFields(overrideProjectId = projectId)
                    )
                },
                onOrder = { orderNo ->
                    if (currentOrderNo != orderNo) {
                        currentOrderNo = orderNo
                        updateOrderNo(snCode, orderNo)
                    }
                },
                onEnded = { onAutoClose(snCode) }
            )
        }
    }

    private fun handleMqttMessage(message: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                val msg = gson.fromJson(message, MqttOrderMsg::class.java)
                if (!isShowering || isStopping || showAutoCloseDialog ||
                    currentOrderNo.isNullOrBlank() || msg.orderNo != currentOrderNo) return@launch
                msg.consumeMoney?.takeIf { it.isFinite() && it >= 0 }?.let {
                    showerConsumed = it
                    showerRemaining = "%.2f".format(if (showerPreDeduct - it < 0) 0.0 else showerPreDeduct - it)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 设备自动关闭：停止计时，查询消费金额，弹出确认框。
     * 用户点确认后才真正退出（finishShower）。
     */
    private fun onAutoClose(snCode: String) {
        if (!isShowering || isStopping || showAutoCloseDialog || showerSnCode != snCode) return
        // 停止计时（弹窗期间洗澡界面不再走秒）
        timerJob?.cancel(); probeJob?.cancel()

        autoCloseDeviceName = sessionDevice?.displayName ?: lastDeviceName.ifEmpty { "热水器" }
        autoCloseElapsed = showerElapsedSec
        autoCloseConsumed = null
        autoCloseLoading = true
        showAutoCloseDialog = true

        val startTime = PrefsHelper.getStartedAt(snCode)
        val order = activeOrders.find { it.snCode == snCode }
        val closingOrderNo = currentOrderNo ?: order?.orderNo.orEmpty()
        val project = order?.projectId ?: sessionDevice?.projectId?.toString()?.takeIf { it != "0" }
        val generation = deviceSessionGeneration
        // The device is already stopped. Dismissing the receipt must not own cleanup.
        clearActiveRecord(snCode)
        // 立即清除持久化的开阀时间戳与倒计时，防止用户不点弹窗确认直接退出时残留脏时间戳
        PrefsHelper.setStartedAt(snCode, 0L)
        PrefsHelper.clearAutoDiscon(snCode)

        settlementJob = viewModelScope.launch {
            val amount = queryDeviceSettlement(closingOrderNo, project, startTime)
            if (generation == deviceSessionGeneration) {
                autoCloseConsumed = amount
                autoCloseLoading = false
            }
        }
    }

    /** 用户点确认：退出洗澡界面并清理 */
    fun confirmAutoClose() {
        showAutoCloseDialog = false
        val snCode = showerSnCode ?: ""
        finishShower(snCode, null)
    }

    // ════════════════════════════════════════════
    fun stopShower() {
        if (!isShowering || isStopping) return
        val snCode = showerSnCode ?: ""
        val oNo = currentOrderNo ?: activeOrders.find { it.snCode == snCode }?.orderNo ?: ""

        isStopping = true
        probeJob?.cancel()
        val session = deviceSessionGeneration
        stopDeviceJob = viewModelScope.launch {
            try {
                val activeOrder = activeOrders.find { it.snCode == snCode }
                val devPid = activeOrder?.projectId ?: sessionDevice
                    ?.projectId?.toString()?.takeIf { it != "0" }
                val devAuth = NetworkModule.authFields(overrideProjectId = devPid)

                var orderNo = oNo
                if (orderNo.isBlank()) {
                    val p = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = devAuth)
                    if (verifyOrder(p) == OrderVerification.ENDED) {
                        isStopping = false
                        onAutoClose(snCode)
                        return@launch
                    }
                    orderNo = p.data?.orderNo.orEmpty()
                    if (orderNo.isBlank()) {
                        toastMessage = "暂时无法确认订单，请稍后重试"
                        return@launch
                    }
                }

                // 1. 发送关阀指令
                val close = NetworkModule.apiService.closeOrderSafe(snCode = snCode, orderNo = orderNo, auth = devAuth)
                if (!close.success) {
                    checkKick(close.displayMessage)
                    // A request already in progress still requires confirmation.
                    if (close.errorCode != 307 && close.displayMessage?.contains("已在") != true) {
                        toastMessage = close.displayMessage ?: "关闭失败，请重试"
                        cn.mudlife.app.utils.AppLogger.e("Shower", "关阀请求失败")
                        return@launch
                    }
                }

                // 2. 轮询确认关阀结果（最多 5 次，间隔 1 秒）
                var closedOk = false
                for (i in 0 until 5) {
                    delay(1000)
                    try {
                        val r = NetworkModule.apiService.closeOrderResultSafe(snCode = snCode, orderNo = orderNo, auth = devAuth)
                        if (r.success) {
                            val d = r.data
                            val closed = d != null && (d.state == 0 || d.status == 0)
                            if (closed) { closedOk = true; break }
                        } else {
                            checkKick(r.displayMessage)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
                }

                // 兜底探活：若 5 次轮询仍未返回关阀状态，调用 queryUsingSafe 核验水机是否已停
                if (!closedOk) {
                    try {
                        val q = NetworkModule.apiService.queryUsingSafe(snCode = snCode, auth = devAuth)
                        if (verifyOrder(q) == OrderVerification.ENDED) {
                            closedOk = true
                        }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
                }

                // 3. 关阀确认成功后：先立即退出（不阻塞），后台异步等账单结算后弹金额
                if (closedOk) {
                    val startTime = PrefsHelper.getStartedAt(snCode)  // 开阀时间戳，未开始时为 0
                    finishShower(snCode, null)
                    settlementJob = viewModelScope.launch {
                        val amount = queryDeviceSettlement(orderNo, devPid, startTime)
                        if (session == deviceSessionGeneration) {
                            toastMessage = amount?.let { "已停止，本次消费 ¥%.2f".format(it) }
                                ?: "设备已停止，本次费用待结算"
                        }
                    }
                } else {
                    showerError = "关水未确认，请检查水机或重试"
                    toastMessage = "关水未确认成功，请再次点击或检查水机"
                    cn.mudlife.app.utils.AppLogger.e("Shower", "关水未确认")
                    isStopping = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                checkKickEx(e)
                toastMessage = "停止用水失败，请检查网络后重试"
            } finally {
                if (session == deviceSessionGeneration && isShowering && !showAutoCloseDialog && showerSnCode == snCode) {
                    isStopping = false
                    sessionDevice?.let { monitorDevice(snCode, it) }
                }
            }
        }
    }

    /** 完成停止流程：退出洗澡界面，清理状态，显示结算结果 */
    private fun finishShower(snCode: String, consumed: Double?) {
        isShowering = false
        isStopping = false

        // 从活跃列表移除
        clearActiveRecord(snCode)

        if (consumed != null) {
            toastMessage = "已停止，本次消费 ¥%.2f".format(consumed)
        } else {
            toastMessage = "设备已确认停止，正在核对结算"
        }

        currentOrderNo = null; showerConsumed = 0.0; showerPreDeduct = 0.0
        showerRemaining = "0.00"; showerElapsedSec = 0
        autoDisConSec = 0
        PrefsHelper.setStartedAt(snCode, 0L) // 重置该设备计时器
        PrefsHelper.clearAutoDiscon(snCode)  // 清除自动关停倒计时
        showerSnCode = null; sessionDevice = null; timerJob?.cancel(); probeJob?.cancel()
        try { mqttManager?.disconnect() } catch (_: Exception) {}
    }

    fun logout() {
        deviceBills.clear()
        lastWalletOwner = null
        accountGeneration++
        deviceSessionGeneration++
        startDeviceJob?.cancel()
        stopDeviceJob?.cancel()
        settlementJob?.cancel()
        isStartingShower = false
        isStopping = false
        showAutoCloseDialog = false
        lifeRequests.invalidate()
        refreshGeneration++; refreshJob?.cancel(); isRefreshing = false
        probeJob?.cancel()
        candidateUseCode = ""; showDryerCodeModal = false; isRollingUseCode = false; remainUseCodeTimes = 20
        orderVerificationJob?.cancel()
        isVerifyingOrders = false
        managersInitialized = false
        unverifiedOrders.clear()
        // 停止所有后台任务
        timerJob?.cancel()

        // 断开 MQTT 连接
        try { mqttManager?.disconnect() } catch (_: Exception) {}

        NetworkModule.clearAuth()

        // 重置所有状态
        isShowering = false
        showerSnCode = null
        currentOrderNo = null
        showerConsumed = 0.0
        showerPreDeduct = 0.0
        showerRemaining = "0.00"
        showerElapsedSec = 0
        showerError = null
        toastMessage = null
        kickedOut = false
        selectedDevice = null
        sessionDevice = null
        showDeviceDetail = false
        recentDevices = emptyList()
        activeOrders.clear()
        activeDeviceSnCodes.clear()
        useCodeData = null
        walletInfo = null
    }

    private fun clearActiveRecord(snCode: String) {
        activeOrders.removeAll { it.snCode == snCode }
        unverifiedOrders.removeAll { it.snCode == snCode }
        activeDeviceSnCodes.remove(snCode)
        PrefsHelper.setStartedAt(snCode, 0L)
        PrefsHelper.clearAutoDiscon(snCode)
        saveOrders()
    }

    private fun saveOrders() {
        val confirmedDevices = activeOrders.map { it.snCode }.toSet()
        unverifiedOrders.removeAll { it.snCode in confirmedDevices }
        PrefsHelper.saveActiveOrders((activeOrders.toList() + unverifiedOrders.toList()).distinctBy { it.snCode })
    }

    fun isDeviceActive(snCode: String) = snCode in activeDeviceSnCodes



    // ── 挤号 ──
    private fun checkKick(msg: String?) {
        if (msg.isNullOrEmpty()) return
        val isRealKick = msg.contains("被挤下线") ||
                msg.contains("账号已在其他设备") ||
                msg.contains("在其他设备登录") ||
                msg.contains("在另一台设备登录") ||
                msg.contains("token失效") ||
                msg.contains("token过期")
        if (isRealKick) {
            cn.mudlife.app.utils.AppLogger.e("Auth", "触发账号强制下线")
            kickedOut = true
            deviceBills.clear()
            PrefsHelper.clearLifeAccount()
        }
    }
    private fun checkKickEx(e: Exception) {
        val m = e.message ?: return
        if (m.contains("401") || m.contains("403") || m.contains("Unauthorized") || m.contains("Forbidden")) {
            cn.mudlife.app.utils.AppLogger.e("Auth", "触发网络鉴权失效下线")
            kickedOut = true
            deviceBills.clear()
            PrefsHelper.clearLifeAccount()
        }
    }

    fun lifeAccountAuthenticated() {
        deviceBills.clear()
        lifeRequests.invalidate()
        walletInfo = null; useCodeData = null
        candidateUseCode = ""; showDryerCodeModal = false; isRollingUseCode = false
        refreshWallet(); loadUseCode()
    }

    fun refreshWalletOnHomeEntry() {
        if (lastWalletOwner == PrefsHelper.userId && SystemClock.elapsedRealtime() - lastWalletRequestAt < 60_000L) return
        refreshWallet()
    }

    fun refreshWallet(): Job {
        lastWalletOwner = PrefsHelper.userId
        lastWalletRequestAt = SystemClock.elapsedRealtime()
        return lifeRequests.launch {
            try {
                val resp = request { NetworkModule.apiService.getWalletSafe() }
                if (resp.success) walletInfo = resp.data else checkKick(resp.displayMessage)
            } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
                checkKickEx(e)
                val msg = e.message ?: ""
                if (msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("No address associated", ignoreCase = true) ||
                    msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)) {
                    toastMessage = "网络连接失败，请检查网络设置"
                }
            }
        }
    }

    fun loadUseCode(): Job {
        return lifeRequests.launch {
            try {
                val resp = request { NetworkModule.apiService.getUseCodeSafe() }
                if (resp.success && resp.data != null) {
                    val remoteCode = resp.data.useCode
                    // 服务端是使用码的唯一真实来源。旧版本曾用本地保护标记
                    // 覆盖服务端返回值，会把“看起来成功”的旧码一直留在界面上。
                    useCodeData = resp.data
                    if (remoteCode.isNotEmpty()) {
                        PrefsHelper.useCode = remoteCode
                        PrefsHelper.useCodeStatus = resp.data.useCodeStatus == 1
                    }
                    // 清理旧版本用于掩盖同步失败的本地保护标记。
                    if (PrefsHelper.useCodeLastConfirmedAt != 0L) {
                        PrefsHelper.useCodeLastConfirmedAt = 0L
                    }
                    if (resp.data.remainTimes in 0..20) {
                        remainUseCodeTimes = resp.data.remainTimes
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
        }
    }

    fun toggleUseCodeStatus(enabled: Boolean) {
        lifeRequests.launch {
            try {
                val newStatus = if (enabled) 1 else 0
                val resp = request { NetworkModule.apiService.updateUseCodeStatusSafe(newStatus, NetworkModule.authFields()) }
                if (resp.success) {
                    useCodeData = useCodeData?.copy(useCodeStatus = newStatus)
                        ?: UseCodeData(useCode = PrefsHelper.useCode, useCodeStatus = newStatus)
                    PrefsHelper.useCodeStatus = enabled
                    toastMessage = if (enabled) "吹风机使用码已开启" else "吹风机使用码已关闭"
                } else {
                    toastMessage = resp.displayMessage.orEmpty().ifEmpty { "操作失败" }
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                toastMessage = "网络请求失败，请检查网络"
            }
        }
    }

    fun openDryerCodeModal() {
        showDryerCodeModal = true
        if (candidateUseCode.isEmpty()) {
            if (remainUseCodeTimes > 0) {
                rollNewUseCode()
            } else {
                candidateUseCode = PrefsHelper.useCode
            }
        }
    }

    fun closeDryerCodeModal() {
        showDryerCodeModal = false
        candidateUseCode = ""
    }

    fun rollNewUseCode() {
        if (isRollingUseCode) return
        if (remainUseCodeTimes <= 0) {
            toastMessage = "今日抽选机会已用尽"
            return
        }
        lifeRequests.launch {
            isRollingUseCode = true
            try {
                val resp = request { NetworkModule.apiService.generateUseCodeSafe(NetworkModule.authFields()) }
                if (resp.success && resp.data != null) {
                    if (resp.data.useCode.isNotEmpty()) {
                        candidateUseCode = resp.data.useCode
                    }
                    if (resp.data.remainTimes in 0..20) {
                        remainUseCodeTimes = resp.data.remainTimes
                    } else if (remainUseCodeTimes > 0) {
                        remainUseCodeTimes--
                    }
                } else {
                    toastMessage = resp.displayMessage.orEmpty().ifEmpty { "抽选新码失败" }
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                toastMessage = "网络请求失败，请检查网络"
            } finally {
                if (isCurrent) isRollingUseCode = false
            }
        }
    }

    fun confirmUseCandidateCode() {
        val codeToUse = candidateUseCode.trim().ifEmpty { return }
        lifeRequests.launch {
            try {
                // generate 只生成候选码；必须调用 set 才会把候选码写入当前账户。
                val auth = NetworkModule.authFields()
                val setResp = request { NetworkModule.apiService.setUseCodeSafe(
                    useCode = codeToUse,
                    auth = auth
                ) }
                if (!setResp.success) {
                    toastMessage = setResp.displayMessage.orEmpty().ifEmpty { "设置新码失败" }
                    return@launch
                }

                // “确认使用”同时保证使用码处于开启状态。
                val statusResp = request { NetworkModule.apiService.updateUseCodeStatusSafe(1, auth) }
                if (!statusResp.success) {
                    toastMessage = statusResp.displayMessage.orEmpty().ifEmpty { "新码已设置，但启用失败" }
                    return@launch
                }

                // 回读服务端，只有当前生效码与候选码一致时才更新本地界面。
                var confirmedData: UseCodeData? = null
                for (attempt in 0 until 3) {
                    val latestResp = request { NetworkModule.apiService.getUseCodeSafe() }
                    if (latestResp.success && latestResp.data?.useCode == codeToUse) {
                        confirmedData = latestResp.data
                        break
                    }
                    if (attempt < 2) delay(400L * (attempt + 1))
                }
                val serverData = confirmedData
                if (serverData == null) {
                    cn.mudlife.app.utils.AppLogger.w(
                        "UseCode",
                        "服务端未回读到新使用码，保留候选码等待重试"
                    )
                    toastMessage = "服务器尚未同步新码，请稍后重试"
                    return@launch
                }

                // 只有服务端回读到候选码后，才更新本地显示并关闭弹窗。
                useCodeData = serverData
                PrefsHelper.useCode = codeToUse
                PrefsHelper.useCodeStatus = serverData.useCodeStatus == 1
                PrefsHelper.useCodeLastConfirmedAt = 0L
                if (serverData.remainTimes in 0..20) {
                    remainUseCodeTimes = serverData.remainTimes
                }
                showDryerCodeModal = false
                candidateUseCode = ""
                toastMessage = "已成功更换并激活吹风机码"
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                toastMessage = "激活新码网络异常，请检查网络"
            }
        }
    }

    private var checkedAtStartup = false
    private var manualUpdateRequested = false

    fun checkUpdateOnStartup() {
        if (checkedAtStartup) return
        checkedAtStartup = true
        checkUpdate()
    }

    fun checkUpdate(isManual: Boolean = false) {
        if (isManual && updateInfo != null) {
            showUpdateDialog = true
            return
        }
        if (isManual) manualUpdateRequested = true
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        viewModelScope.launch {
            try {
                val result = cn.mudlife.app.utils.UpdateManager.checkUpdate()
                val info = result.getOrNull()
                if (info?.hasUpdate == true) {
                    updateInfo = info
                    if (manualUpdateRequested) showUpdateDialog = true
                } else if (manualUpdateRequested && info != null) {
                    toastMessage = if (info.versionCode < cn.mudlife.app.BuildConfig.VERSION_CODE)
                        "当前安装版高于已发布版本" else "当前已是最新版本"
                } else if (manualUpdateRequested) {
                    toastMessage = "检测更新失败，请检查网络后重试"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (manualUpdateRequested) toastMessage = "检测更新失败，请稍后重试"
            } finally {
                isCheckingUpdate = false
                manualUpdateRequested = false
            }
        }
    }

    fun closeUpdateDialog() {
        if (!isDownloadingUpdate) {
            showUpdateDialog = false
        }
    }

    fun startDownloadUpdate(context: android.content.Context) {
        val info = updateInfo ?: return
        if (isDownloadingUpdate) return
        viewModelScope.launch {
            isDownloadingUpdate = true
            updateDownload = cn.mudlife.app.utils.UpdateDownloadProgress()
            updateError = null
            try {
                val result = cn.mudlife.app.utils.UpdateManager.downloadApk(context, info) { progress ->
                    updateDownload = progress
                }
                if (result.isSuccess) {
                    val file = result.getOrNull()
                    if (file != null) {
                        cn.mudlife.app.utils.UpdateManager.installApk(context, file).fold(
                            onSuccess = { showUpdateDialog = false },
                            onFailure = { updateError = it.message ?: "无法打开安装器，请稍后重试" }
                        )
                    } else {
                        updateError = "安装包解析异常"
                    }
                } else {
                    updateError = result.exceptionOrNull()?.message ?: "安装包下载失败，请稍后重试"
                }
            } catch (_: Exception) {
                updateError = "下载更新异常，请稍后重试"
            } finally {
                isDownloadingUpdate = false
            }
        }
    }

    // ── 后勤洗浴使用码控制 (江大专区) ──
    private var bathCodeJob: Job? = null
    private var bathProjectsJob: Job? = null
    private var bathCheckedAt = 0L

    init {
        viewModelScope.launch {
            QzhqNetwork.invalidSessions.collect { token ->
                if (token.isNotBlank() && token == PrefsHelper.qzhqLoginCode && PrefsHelper.qzhqSessionExpired) {
                    qzhqSessionExpired = true
                    isQzhqLoggedIn = false
                    qzhqUseCodeData = null
                }
            }
        }
    }

    fun validateQzhqSession() {
        if (!PrefsHelper.isQzhqLoggedIn || bathCodeJob?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (bathCheckedAt != 0L && now - bathCheckedAt < 60_000L) return
        bathCheckedAt = now
        loadQzhqUseCode()
    }

    fun logoutQzhq() {
        bathLoginJob?.cancel()
        PrefsHelper.qzhqLoginCode = ""
        PrefsHelper.qzhqSessionExpired = false
        isQzhqLoggedIn = false
        qzhqSessionExpired = false
        resetBathSelection()
    }

    private fun resetBathSelection() {
        bathRequests.invalidate()
        bathCheckedAt = 0L
        qzhqProjectId = PrefsHelper.qzhqProjectId
        qzhqProjects = emptyList()
        qzhqProjectsError = false
        qzhqLoading = false
        qzhqUseCodeData = null
        qzhqPrefix = ""
        qzhqCodeError = null
    }

    fun loadQzhqProjects() {
        if (!isQzhqLoggedIn || qzhqProjects.isNotEmpty() || bathProjectsJob?.isActive == true) return
        val homeProject = PrefsHelper.qzhqProjectId
        qzhqProjectsError = false
        bathProjectsJob = bathRequests.launch {
            try {
                val response = request { QzhqNetwork.getProjectGroup(homeProject) }
                qzhqProjects = if (response.isSuccess) response.data.orEmpty()
                    .filter { it.id > 0 && it.label.isNotBlank() }.distinctBy { it.id } else emptyList()
                qzhqProjectsError = qzhqProjects.isEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                qzhqProjectsError = true
            }
        }
    }

    fun selectQzhqProject(projectId: String) {
        if (!isQzhqLoggedIn || qzhqLoading || projectId == qzhqProjectId ||
            qzhqProjects.none { it.id.toString() == projectId }) return
        qzhqProjectId = projectId
        qzhqUseCodeData = null
        qzhqPrefix = ""
        loadQzhqUseCode()
    }

    fun loadQzhqUseCode(): Job {
        if (!isQzhqLoggedIn) return Job().apply { complete() }
        return runBathCodeRequest { phone, loginCode, projectId ->
            refreshBathCode(phone, loginCode, projectId)
        }
    }

    fun toggleQzhqStatus(newStatus: Int) {
        if (!isQzhqLoggedIn || qzhqLoading || qzhqUseCodeData?.isSet != true) return
        runBathCodeRequest { phone, loginCode, projectId ->
            val response = request { QzhqNetwork.updateUseCodeStatus(newStatus, phone, loginCode, projectId) }
            if (response.isSuccess) {
                qzhqUseCodeData = qzhqUseCodeData?.copy(useCodeStatus = newStatus)
                toastMessage = if (newStatus == 1) "洗澡码已开启" else "洗澡码已关闭"
            } else {
                toastMessage = response.message.ifEmpty { "操作失败" }
            }
        }
    }

    fun setQzhqUseCode(newCode5: String, onComplete: (Boolean, String) -> Unit) {
        if (!isQzhqLoggedIn || qzhqLoading || qzhqUseCodeData == null || qzhqCodeError != null) return
        runBathCodeRequest { phone, loginCode, projectId ->
            val response = try {
                request { QzhqNetwork.setUseCode(newCode5, phone, loginCode, projectId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PrefsHelper.forgetBathPrefix(projectId)
                qzhqUseCodeData = null
                qzhqPrefix = ""
                refreshBathCode(phone, loginCode, projectId)
                onComplete(false, "暂未确认提交结果，请核对该区使用码")
                return@runBathCodeRequest
            }
            if (!response.isSuccess) {
                onComplete(false, response.message.ifEmpty { "设置失败" })
                return@runBathCodeRequest
            }
            PrefsHelper.saveBathPrefix(projectId, newCode5)
            qzhqUseCodeData = null
            qzhqPrefix = ""
            val synced = refreshBathCode(phone, loginCode, projectId, newCode5)
            val message = if (synced) "使用码已保存" else "提交成功，暂未确认使用码"
            toastMessage = message
            onComplete(true, message)
        }
    }

    private fun runBathCodeRequest(
        block: suspend AccountRequests.Request.(String, String, String) -> Unit
    ): Job {
        bathCodeJob?.takeIf { it.isActive }?.let { return it }
        val phone = PrefsHelper.qzhqPhone.ifEmpty { PrefsHelper.telephone }
        val loginCode = PrefsHelper.qzhqLoginCode
        val projectId = qzhqProjectId
        qzhqLoading = true
        return bathRequests.launch {
            try {
                block(phone, loginCode, projectId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toastMessage = "网络请求失败，请重试"
            } finally {
                if (isCurrent) qzhqLoading = false
            }
        }.also { bathCodeJob = it }
    }

    private suspend fun AccountRequests.Request.refreshBathCode(
        phone: String, loginCode: String, projectId: String, submittedPrefix: String? = null
    ): Boolean {
        qzhqCodeError = null
        val response = try {
            request { QzhqNetwork.getUseCode(phone, loginCode, projectId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            qzhqUseCodeData = null
            qzhqCodeError = "使用码暂时无法加载"
            return false
        }
        val data = response.data
        if (!response.isSuccess || data == null) {
            qzhqUseCodeData = null
            qzhqCodeError = response.message.ifBlank { "使用码暂时无法加载" }
            return false
        }
        if (submittedPrefix != null && BathCode.complete(data.randomCode, submittedPrefix)
                ?.startsWith(submittedPrefix) != true) {
            qzhqCodeError = "暂未确认新使用码"
            return false
        }
        data.randomCode?.takeIf { it.matches(Regex("[0-9]{8}")) }
            ?.let { PrefsHelper.saveBathPrefix(projectId, it.take(5)) }
        qzhqPrefix = if (data.isSet) submittedPrefix ?: PrefsHelper.bathPrefix(projectId) else ""
        qzhqUseCodeData = data
        return true
    }

    fun loginQzhq(phone: String, passwordRaw: String, onComplete: (Boolean, String) -> Unit) {
        bathLoginJob?.cancel()
        bathLoginJob = viewModelScope.launch {
            try {
                val resp = cn.mudlife.app.api.QzhqNetwork.login(phone, passwordRaw)
                if (resp.isSuccess && resp.data != null) {
                    resetBathSelection()
                    isQzhqLoggedIn = true
                    qzhqSessionExpired = false
                    loadQzhqUseCode()
                    loadQzhqProjects()
                    toastMessage = "趣智后勤热水已登录"
                    onComplete(true, "登录成功")
                } else {
                    val msg = resp.message.ifEmpty { "登录失败，请核对手机号或密码" }
                    toastMessage = msg
                    onComplete(false, msg)
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
                val msg = "网络异常：${e.message ?: "连接失败"}"
                toastMessage = msg
                onComplete(false, msg)
            }
        }
    }

    fun smsLoginQzhq(phone: String, smsCode: String, onComplete: (Boolean, String) -> Unit) {
        bathLoginJob?.cancel()
        bathLoginJob = viewModelScope.launch {
            try {
                val resp = cn.mudlife.app.api.QzhqNetwork.smsLogin(phone, smsCode)
                if (resp.isSuccess && resp.data != null) {
                    resetBathSelection()
                    isQzhqLoggedIn = true
                    qzhqSessionExpired = false
                    loadQzhqUseCode()
                    loadQzhqProjects()
                    toastMessage = "趣智后勤热水已登录"
                    onComplete(true, "登录成功")
                } else {
                    val msg = resp.message.ifEmpty { "登录失败，请核对手机号或验证码" }
                    toastMessage = msg
                    onComplete(false, msg)
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
                val msg = "网络异常：${e.message ?: "连接失败"}"
                toastMessage = msg
                onComplete(false, msg)
            }
        }
    }

    fun sendQzhqSmsCode(phone: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val resp = cn.mudlife.app.api.QzhqNetwork.sendVerifyCode(phone)
                if (resp.isSuccess) {
                    toastMessage = "验证码已发送"
                    onComplete(true, "验证码已发送")
                } else {
                    val msg = resp.message.ifEmpty { "发送失败，请稍后重试" }
                    toastMessage = msg
                    onComplete(false, msg)
                }
            } catch (e: Exception) {
                val msg = "网络异常：${e.message ?: "连接失败"}"
                toastMessage = msg
                onComplete(false, msg)
            }
        }
    }

    fun pullRefresh() {
        if (refreshJob?.isActive == true) return
        val generation = ++refreshGeneration
        isRefreshing = true
        refreshJob = viewModelScope.launch {
            try {
                val work = mutableListOf(syncProjectAccounts(), refreshWallet(), loadUseCode(), loadQzhqUseCode())
                cleanInactiveOrders()
                orderVerificationJob?.let(work::add)
                activeOrders.toList().forEach { order ->
                    work += launch { refreshDeviceStatus(order.snCode) }
                }
                work.joinAll()
            } finally { if (generation == refreshGeneration) isRefreshing = false }
        }
    }

    private fun syncProjectAccounts(): Job = lifeRequests.launch {
        try {
            val response = request { NetworkModule.apiService.getUserProjectsSafe() }
            if (response.success && !response.data.isNullOrEmpty()) {
                val accounts = response.data.associate { it.projectId.toString() to it.accountId.toString() }
                NetworkModule.setProjectAccounts(accounts)
                PrefsHelper.saveProjectAccounts(accounts)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { /* Keep the existing account mapping on a transient error. */ }
    }
}
