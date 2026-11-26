package cn.mudlife.app.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import cn.mudlife.app.R
import cn.mudlife.app.api.NetworkModule
import cn.mudlife.app.api.closeOrderSafe
import cn.mudlife.app.api.downRateSafe
import cn.mudlife.app.api.getDeviceInfoSafe
import cn.mudlife.app.api.getUserProjectsSafe
import cn.mudlife.app.api.queryUsingSafe
import cn.mudlife.app.data.DeviceCommands
import cn.mudlife.app.model.ActiveOrder
import cn.mudlife.app.model.OrderVerification
import cn.mudlife.app.model.verifyOrder
import cn.mudlife.app.utils.AppLogger
import cn.mudlife.app.utils.PrefsHelper
import cn.mudlife.app.utils.WidgetStopStage
import cn.mudlife.app.utils.HapticHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class WaterWidgetControlService : Service() {
    companion object {
        private const val CHANNEL = "water_widget_control"
        private const val NOTIFICATION = 171
        private const val START = "start"
        private const val STOP = "stop"
        private const val HOT = "hot"
        private const val OWNER = "owner"
        private const val DEVICE = "device"
        private const val ORDER = "order"
        private val revision = MutableStateFlow(0)
        internal val orderRevision = revision.asStateFlow()

        internal fun command(intent: Intent): Command? {
            val action = intent.action
            if (action != START && action != STOP) return null
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val owner = intent.getStringExtra(OWNER) ?: return null
            val sn = intent.getStringExtra(DEVICE) ?: return null
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID || owner.isBlank() || sn.isBlank()) return null
            return Command(id, intent.getBooleanExtra(HOT, false), owner, sn, action == STOP,
                intent.getStringExtra(ORDER)?.takeIf(String::isNotBlank))
        }

        internal fun intent(
            context: Context, id: Int, hot: Boolean, owner: String, sn: String, order: ActiveOrder?
        ): Intent {
            val action = if (order != null) STOP else START
            val orderNo = order?.orderNo?.takeIf(String::isNotBlank)
            val token = UUID.nameUUIDFromBytes("$owner/$sn/${orderNo.orEmpty()}".toByteArray())
            return Intent(context, WaterWidgetControlService::class.java).setAction(action)
                .setData(Uri.parse("jiangdalife://water-control/$id/$hot/$action/$token"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(HOT, hot)
                .putExtra(OWNER, owner)
                .putExtra(DEVICE, sn)
                .putExtra(ORDER, orderNo)
        }
    }

    internal data class Command(
        val id: Int, val hot: Boolean, val owner: String, val sn: String, val stopping: Boolean, val expectedOrder: String?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = 0
    private var latestStartId = 0
    private val service get() = NetworkModule.apiService

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (running == 0) {
            try {
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "桌面出水", NotificationManager.IMPORTANCE_LOW))
                val notification = Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_widget_water_drop)
                    .setContentTitle("正在处理饮水机操作")
                    .setOngoing(true)
                    .build()
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
                } else {
                    startForeground(NOTIFICATION, notification)
                }
            } catch (_: RuntimeException) {
                DrinkingWaterWidget.completed(this, intent, confirmed = false)
                Toast.makeText(this, "系统暂未允许桌面出水，请重试", Toast.LENGTH_SHORT).show()
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        running++
        scope.launch {
            var accepted = false
            var confirmed = false
            try {
                val message = withTimeoutOrNull(45_000) {
                    if (intent == null) "" else control(intent, onAccepted = { accepted = true }) { confirmed = true }
                } ?: "请求超时，请检查饮水机状态"
                if (confirmed) HapticHelper.doubleClick(applicationContext)
                if (message.isNotEmpty()) Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(applicationContext, "暂时无法确认饮水机状态，请检查网络或设备", Toast.LENGTH_SHORT).show()
            } finally {
                if (accepted) DrinkingWaterWidget.completed(this@WaterWidgetControlService, intent, confirmed)
                revision.value++
                if (--running == 0) stopSelfResult(latestStartId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun control(
        intent: Intent, onAccepted: () -> Unit, onConfirmed: () -> Unit
    ): String {
        val command = command(intent) ?: return ""
        val (id, hot, owner, sn, stopping) = command
        if (!DrinkingWaterWidget.ownsWidget(this, id)) return ""
        PrefsHelper.ensureInitialized(applicationContext)
        if (owner != PrefsHelper.waterWidgetOwner) return "账号已变更，请重新配置小部件"
        val login = PrefsHelper.loginCode
        val device = PrefsHelper.waterWidgetDevices(id).device(hot) ?: return "请先选择饮水机"
        if (device.snCode != sn) return "饮水机已变更，请重新点击"
        fun stillBound(): Boolean = login == PrefsHelper.loginCode && owner == PrefsHelper.waterWidgetOwner &&
            DrinkingWaterWidget.ownsWidget(this, id) &&
            PrefsHelper.waterWidgetDevices(id).device(hot)?.snCode == sn
        fun sameAccount(): Boolean = login == PrefsHelper.loginCode && owner == PrefsHelper.waterWidgetOwner

        if (!DeviceCommands.acquire(sn)) return ""
        val startedAt = SystemClock.elapsedRealtime()
        try {
            onAccepted()
            DrinkingWaterWidget.accepted(this, command)
            NetworkModule.restoreFromPrefs()
            val saved = PrefsHelper.getActiveOrders().firstOrNull { it.snCode == sn }
            if (stopping && command.expectedOrder != null && command.expectedOrder != saved?.orderNo) {
                return "用水订单已变化，请重新点击"
            }
            val savedProject = saved?.projectId?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
            // A tracked stop can use the order's project and verify ownership directly.
            // Starts and older records still validate the physical device before acting.
            val info = if (stopping && savedProject != null) null else {
                var lookup = service.getDeviceInfoSafe(sn)
                if ((!lookup.success || lookup.data?.snCode != sn) && device.mac.isNotBlank() && device.mac != sn) {
                    lookup = service.getDeviceInfoSafe(device.mac)
                }
                lookup.data?.takeIf { lookup.success && it.snCode == sn && it.isDrinkingWater }
                    ?: return "无法确认饮水机，请重新扫码"
            }
            val project = (savedProject ?: info?.projectId?.toString() ?: device.projectId)
                ?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
            if (!stillBound()) return "饮水机或账号已变更"
            if (NetworkModule.getAccountIdForProject(project).isNullOrBlank()) {
                val projects = service.getUserProjectsSafe().data.orEmpty().associate { it.projectId.toString() to it.accountId.toString() }
                if (!stillBound()) return "饮水机或账号已变更"
                if (projects.isNotEmpty()) {
                    val merged = PrefsHelper.getProjectAccounts() + projects
                    NetworkModule.setProjectAccounts(merged)
                    PrefsHelper.saveProjectAccounts(merged)
                }
            }
            if (NetworkModule.getAccountIdForProject(project).isNullOrBlank()) return "该设备的项目账户暂不可用"
            val auth = NetworkModule.authFields(overrideProjectId = project)
            if (auth["loginCode"] != login) return "账号已变更，请重新点击"
            // The widget carries the exact order shown at tap time, and local orders belong to the current account.
            // Keep a server lookup for legacy or not-yet-confirmed records with no known order number.
            if (stopping && savedProject != null && command.expectedOrder != null && stillBound()) {
                return stop(command, command.expectedOrder, auth, startedAt, ::sameAccount, onConfirmed)
            }
            val showSwitch = { DrinkingWaterWidget.switchingWater(this, command) }
            val response = DeviceCommands.afterWaterStop(owner, sn, { !stopping && stillBound() }, showSwitch) {
                service.queryUsingSafe(snCode = sn, auth = auth)
            }
            if (!stillBound()) return "饮水机或账号已变更"
            val state = verifyOrder(response)
            val orderNo = response.data?.orderNo.orEmpty()
            if (stopping) {
                if (state == OrderVerification.ENDED) {
                    return if (DeviceCommands.confirmedEnded(service, sn, saved?.orderNo.orEmpty(), auth) &&
                        clearRecord(sn, ::sameAccount)
                    ) "饮水机已停止".also { onConfirmed() } else "暂时无法确认饮水机已停止"
                }
                if (state != OrderVerification.ACTIVE || response.data?.isOwner != true || orderNo.isBlank() ||
                    (!saved?.orderNo.isNullOrBlank() && saved?.orderNo != orderNo)
                ) return "暂时无法确认当前订单，请检查设备"
                return stop(command, orderNo, auth, startedAt, ::sameAccount, onConfirmed)
            }
            val startingDevice = requireNotNull(info)
            val record = ActiveOrder(sn, orderNo, startingDevice.displayName, startingDevice.macAddress,
                startingDevice.typeEmoji, startingDevice.withholdMoney, projectId = project)
            if (state == OrderVerification.ACTIVE && response.data?.isOwner == true) {
                PrefsHelper.writeDeviceOrder(sn, record)
                return "该饮水机已在出水".also { onConfirmed() }
            }
            if (response.data?.isOwner == false) return "该饮水机正在被使用"
            if (state != OrderVerification.ENDED) return "暂时无法确认饮水机状态，请重试"
            if (saved != null) return "请先确认上一次用水已停止"
            // Record the attempt before issuing the command so a timeout leaves a stop action available.
            if (!PrefsHelper.writeDeviceOrder(sn, record)) return "无法保存用水记录"
            PrefsHelper.setStartedAt(sn, System.currentTimeMillis())
            PrefsHelper.clearAutoDiscon(sn)
            val start = DeviceCommands.afterWaterStop(owner, sn, ::stillBound, showSwitch) {
                service.downRateSafe(snCode = sn, auth = auth)
            }
            if (!start.success) {
                if (DeviceCommands.isUnfinishedWaterOrder(start)) clearRecord(sn, ::sameAccount)
                return start.displayMessage ?: "出水未确认，请检查饮水机"
            }
            val opened = DeviceCommands.confirmOpen(service, sn, auth, start.data)
                ?: return "出水未确认，请检查饮水机"
            if (!sameAccount()) return "账号已变更，请检查饮水机状态"
            PrefsHelper.writeDeviceOrder(sn, record.copy(orderNo = opened.orderNo.orEmpty()))
            if (opened.autoDisconnectSeconds > 0) PrefsHelper.deviceDeadline(sn, opened.autoDisconnectSeconds)
            return "已开始出水".also { onConfirmed() }
        } finally {
            DeviceCommands.release(sn)
        }
    }

    private suspend fun stop(
        command: Command, orderNo: String, auth: Map<String, String>, startedAt: Long,
        sameAccount: () -> Boolean, onConfirmed: () -> Unit
    ): String {
        val sn = command.sn
        var rejected: String? = null
        val confirmed = DeviceCommands.confirmWidgetClose(service, sn, orderNo, auth) {
            AppLogger.waterWidgetStop(WidgetStopStage.SENT, SystemClock.elapsedRealtime() - startedAt)
            try {
                val close = service.closeOrderSafe(snCode = sn, orderNo = orderNo, auth = auth)
                val accepted = close.success || close.errorCode == 307 || close.displayMessage?.contains("已在") == true
                if (accepted) {
                    AppLogger.waterWidgetStop(WidgetStopStage.ACCEPTED, SystemClock.elapsedRealtime() - startedAt)
                    DrinkingWaterWidget.stopSubmitted(this, command)
                } else {
                    rejected = close.displayMessage ?: "关水请求失败，请重试"
                }
                accepted
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
        if (!confirmed || !clearRecord(sn, sameAccount)) {
            AppLogger.waterWidgetStop(WidgetStopStage.UNCONFIRMED, SystemClock.elapsedRealtime() - startedAt)
            return rejected ?: "尚未确认关水，请检查饮水机"
        }
        DeviceCommands.recordWaterStop(command.owner, sn)
        AppLogger.waterWidgetStop(WidgetStopStage.CONFIRMED, SystemClock.elapsedRealtime() - startedAt)
        onConfirmed()
        return "已停止出水"
    }

    private fun clearRecord(sn: String, sameAccount: () -> Boolean): Boolean {
        if (!sameAccount() || !PrefsHelper.writeDeviceOrder(sn, null)) return false
        PrefsHelper.setStartedAt(sn, 0L)
        PrefsHelper.clearAutoDiscon(sn)
        return true
    }
}
