package cn.mudlife.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import cn.mudlife.app.R
import cn.mudlife.app.data.DeviceCommands
import cn.mudlife.app.model.RecentDevice
import cn.mudlife.app.ui.theme.ThemeMode
import cn.mudlife.app.utils.HapticHelper
import cn.mudlife.app.utils.PrefsHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DrinkingWaterWidget : AppWidgetProvider() {
    companion object {
        private const val REFRESH = "cn.mudlife.app.widget.REFRESH_WATER"
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private val worker = Executors.newSingleThreadExecutor()
        private val handler = Handler(Looper.getMainLooper())
        private val waterNameSuffix = Regex("\\s*直饮(?:冷水|热水|开水)\\s*$")
        private val feedback = ConcurrentHashMap<DeviceKey, FeedbackEntry>()

        private fun component(context: Context) = ComponentName(context, DrinkingWaterWidget::class.java)

        internal fun ownsWidget(context: Context, id: Int): Boolean =
            id != AppWidgetManager.INVALID_APPWIDGET_ID &&
                AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider == component(context)

        internal fun refresh(context: Context) {
            context.sendBroadcast(Intent(REFRESH).setComponent(component(context))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
        }

        internal fun accepted(context: Context, command: WaterWidgetControlService.Command) {
            val key = DeviceKey(command.owner, command.sn)
            val label = if (command.stopping) "关水中" else "开启中"
            postFeedback(context, key, Feedback(label, true), 50_000)
            HapticHelper.tick(context)
            refresh(context)
        }

        internal fun switchingWater(context: Context, command: WaterWidgetControlService.Command) {
            postFeedback(context, DeviceKey(command.owner, command.sn), Feedback("切换中", true), 12_000)
            refresh(context)
        }

        internal fun stopSubmitted(context: Context, command: WaterWidgetControlService.Command) {
            postFeedback(context, DeviceKey(command.owner, command.sn), Feedback("已提交", true), 12_000)
            refresh(context)
        }

        internal fun completed(context: Context, intent: Intent?, confirmed: Boolean) {
            val command = intent?.let { WaterWidgetControlService.command(it) } ?: return
            val key = DeviceKey(command.owner, command.sn)
            if (confirmed) feedback.remove(key)
            else postFeedback(context, key, Feedback("未确认", false), 6500)
            refresh(context)
        }

        private fun postFeedback(context: Context, key: DeviceKey, state: Feedback, duration: Long) {
            val entry = FeedbackEntry(state, SystemClock.elapsedRealtime() + duration)
            feedback[key] = entry
            val app = context.applicationContext
            handler.postDelayed({ if (feedback.remove(key, entry)) refresh(app) }, duration)
        }
    }

    private data class DeviceKey(val owner: String, val sn: String)
    private data class Feedback(val label: String, val pending: Boolean)
    private data class FeedbackEntry(val state: Feedback, val expiresAt: Long)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            REFRESH, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val manager = AppWidgetManager.getInstance(context)
                onUpdate(context, manager, manager.getAppWidgetIds(component(context)))
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isNotEmpty()) runInWorker(context) { ids.forEach { update(context, manager, it) } }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        runInWorker(context) { update(context, manager, id, options) }

    override fun onDeleted(context: Context, ids: IntArray) = runInWorker(context) {
        PrefsHelper.removeWaterWidgets(ids)
    }

    private fun runInWorker(context: Context, action: () -> Unit) {
        val pending = goAsync()
        worker.execute {
            try {
                PrefsHelper.ensureInitialized(context)
                action()
            } finally {
                pending.finish()
            }
        }
    }

    private fun feedbackFor(key: DeviceKey): Feedback? {
        val current = feedback[key] ?: return null
        if (current.expiresAt > SystemClock.elapsedRealtime()) return current.state
        feedback.remove(key, current)
        return null
    }

    private fun update(
        context: Context, manager: AppWidgetManager, id: Int, options: Bundle = manager.getAppWidgetOptions(id)
    ) {
        val owner = PrefsHelper.waterWidgetOwner
        val devices = PrefsHelper.waterWidgetDevices(id)
        val orders = PrefsHelper.getActiveOrders().associateBy { it.snCode }
        val appearance = PrefsHelper.appearance(context)
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val light = appearance.palette.colors(appearance.mode.resolve(false) == ThemeMode.DARK)
        val dark = appearance.palette.colors(appearance.mode.resolve(true) == ThemeMode.DARK)
        fun RemoteViews.ink(id: Int, day: Color, night: Color) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setColorInt(id, "setTextColor", day.toArgb(), night.toArgb())
            } else setTextColor(id, (if (systemDark) night else day).toArgb())
        }
        fun RemoteViews.fill(id: Int, day: Color, night: Color) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setColorStateList(id, "setBackgroundTintList",
                    ColorStateList.valueOf(day.toArgb()), ColorStateList.valueOf(night.toArgb()))
            }
        }


        fun state(device: RecentDevice?) = if (device != null && owner != null)
            feedbackFor(DeviceKey(owner, device.snCode)) else null
        fun working(device: RecentDevice?) =
            state(device)?.pending == true || device?.snCode?.let(DeviceCommands::isBusy) == true
        val coldPriority = working(devices.cold) || orders.containsKey(devices.cold?.snCode)
        val hotPriority = working(devices.hot) || orders.containsKey(devices.hot?.snCode)
        val compactHot = when {
            coldPriority != hotPriority -> hotPriority
            devices.cold == null -> devices.hot != null
            devices.hot == null -> false
            else -> devices.compactHot
        }

        fun tile(hot: Boolean, device: RecentDevice?, compact: Boolean, detail: Boolean, rows: Boolean): RemoteViews {
            val order = orders[device?.snCode]
            val current = state(device)
            val busy = working(device)
            val type = if (hot) "热水" else "冷水"
            val action = when {
                busy -> current?.takeIf { it.pending }?.label ?: "处理中"
                order != null -> "关水"
                device != null -> "出水"
                else -> "未绑定"
            }
            val subtitle = when {
                current?.pending == false -> current.label
                order != null && order.orderNo.isBlank() && !busy -> "待确认"
                order != null && !busy -> "出水中"
                owner == null -> "请先登录"
                else -> null
            }
            val tap = when {
                busy -> null
                owner == null || device == null -> null
                else -> PendingIntent.getForegroundService(context, id,
                    WaterWidgetControlService.intent(context, id, hot, owner, device.snCode, order), FLAGS)
            }
            val layout = when {
                detail -> R.layout.drinking_water_detail
                rows -> R.layout.drinking_water_strip
                compact -> R.layout.drinking_water_compact
                else -> R.layout.drinking_water_tile
            }
            val panel = RemoteViews(context.packageName, layout)
            val title = when {
                detail && !busy && device != null -> "$type · ${subtitle ?: "待机"}"
                compact && subtitle != null -> subtitle
                else -> type
            }
            fun accent(dark: Boolean) = if (hot) {
                if (dark) Color(0xFFD0A88A) else Color(0xFF92532E)
            } else {
                if (dark) Color(0xFF9FBAC5) else Color(0xFF426A77)
            }
            val dayAccent = accent(light.isDark)
            val nightAccent = accent(dark.isDark)
            panel.setImageViewResource(R.id.water_tile_icon,
                if (hot) R.drawable.ic_widget_water_hot else R.drawable.ic_widget_water_cold)
            panel.setTextViewText(R.id.water_tile_type, title)
            panel.setTextViewText(R.id.water_tile_action, action)
            panel.ink(R.id.water_tile_type, light.TextPrimary, dark.TextPrimary)
            panel.ink(R.id.water_tile_action, dayAccent, nightAccent)
            if (rows || detail) {
                val weight = if (busy || order != null) .20f else .11f
                panel.fill(R.id.water_tile_action, lerp(light.Card, dayAccent, weight), lerp(dark.Card, nightAccent, weight))
            }
            if (detail) {
                panel.setTextViewText(R.id.water_tile_device, device?.cleanName?.let { fullName ->
                    fullName.substringAfter("江苏大学").replace(waterNameSuffix, "").trim().ifBlank { fullName }
                }.orEmpty())
                panel.setViewVisibility(R.id.water_tile_device, if (device == null) View.GONE else View.VISIBLE)
                panel.ink(R.id.water_tile_device, light.TextSecondary, dark.TextSecondary)
            }
            panel.setContentDescription(R.id.water_tile, listOfNotNull(type, subtitle, action, device?.cleanName).joinToString("，"))
            panel.setOnClickPendingIntent(R.id.water_tile, tap)
            val weight = if (busy || order != null) .09f else 0f
            panel.fill(R.id.water_tile, lerp(light.Card, dayAccent, weight), lerp(dark.Card, nightAccent, weight))
            return panel
        }

        val views = responsiveWidgetViews(options, SizeF(150f, 78f)) { width, height ->
            val narrow = width < WIDGET_NARROW_WIDTH_DP
            val tall = height >= WIDGET_SHORT_HEIGHT_DP
            val compact = narrow && !tall
            val rows = !narrow && !tall && width < 230f && height >= 68f
            val stacked = (narrow && tall) || rows
            val dashboard = !narrow && tall
            val layout = when {
                dashboard -> R.layout.drinking_water_dashboard
                stacked -> R.layout.drinking_water_stacked
                else -> R.layout.drinking_water_widget
            }
            RemoteViews(context.packageName, layout).apply {
                fill(R.id.water_widget_root, light.Card, dark.Card)
                fun area(hot: Boolean, id: Int) {
                    val visible = !compact || hot == compactHot
                    setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
                    removeAllViews(id)
                    if (visible) addView(id, tile(hot, devices.device(hot), narrow && height >= 55f, dashboard, rows))
                }
                area(false, R.id.water_widget_cold)
                area(true, R.id.water_widget_hot)
            }
        }
        manager.updateAppWidget(id, views)
    }
}
