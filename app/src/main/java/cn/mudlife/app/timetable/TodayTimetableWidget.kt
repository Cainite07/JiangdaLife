package cn.mudlife.app.timetable

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import cn.mudlife.app.ui.theme.AppColorSet
import cn.mudlife.app.ui.theme.ThemeMode
import cn.mudlife.app.utils.PrefsHelper
import cn.mudlife.app.widget.WIDGET_MIN_DETAIL_HEIGHT_DP
import cn.mudlife.app.widget.WIDGET_NARROW_WIDTH_DP
import cn.mudlife.app.widget.WIDGET_SHORT_HEIGHT_DP
import cn.mudlife.app.widget.responsiveWidgetViews
import cn.mudlife.app.R
import cn.mudlife.campuscard.SessionStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

internal data class AgendaItem(
    val name: String,
    val periods: String,
    val detail: String,
    val room: String = "",
    val start: LocalTime? = null,
    val end: LocalTime? = null
) {
    val timeLabel: String get() = start?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: periods
}

internal data class TodayAgenda(val date: String, val message: String, val items: List<AgendaItem> = emptyList()) {
    fun currentOrNext(now: LocalTime): AgendaItem? =
        items.firstOrNull { it.start != null && it.end != null && now >= it.start && now < it.end }
            ?: items.filter { it.start != null && it.start >= now }.minByOrNull { it.start!! }
            ?: items.firstOrNull { it.start == null || it.end == null }

    fun remaining(now: LocalTime): TodayAgenda {
        val visible = items.filter { it.end == null || it.end > now }
        return if (items.isNotEmpty() && visible.isEmpty()) copy(message = "今日课程已结束", items = visible)
            else copy(items = visible)
    }

    companion object {
        private val shortDate = DateTimeFormatter.ofPattern("M月d日 E", Locale.SIMPLIFIED_CHINESE)
        private val clock = DateTimeFormatter.ofPattern("HH:mm")

        fun read(context: Context, date: LocalDate = LocalDate.now(campusZone)): TodayAgenda {
            val label = date.format(shortDate)
            return try {
                val session = SessionStore(context)
                val owner = session.timetableOwner ?: return TodayAgenda(label,
                    if (session.hasSession) "打开校园卡，确认当前校园账号" else "登录校园账号后查看今日课程")
                TimetableStore(context, owner).use { store ->
                    val term = store.terms().firstOrNull() ?: return TodayAgenda(label, "打开课表，导入或添加课程")
                    val settings = TimetableSettings(context, owner)
                    val courses = store.courses(term)
                    if (courses.isEmpty()) return TodayAgenda(label, "本学期还没有课程")
                    val first = settings.firstMonday(term, store.schoolTerm(term))
                        ?: return TodayAgenda(label, "请在课表中设置学期第一周的周一")
                    forDay(date, TeachingCalendar(first, settings.displayOptions(term).termWeeks), courses) { place -> settings.periods(date, place) }
                }
            } catch (_: Exception) {
                TodayAgenda(label, "暂时无法读取，点此打开课表")
            }
        }

        fun forDay(date: LocalDate, calendar: TeachingCalendar, rows: List<SavedCourse>, bells: (String) -> List<BellPeriod>): TodayAgenda {
            val week = calendar.weekOf(date)
            val label = date.format(shortDate)
            if (week !in 1..calendar.lastWeek) return TodayAgenda(label, if (week < 1) "所选学期尚未开始" else "所选学期已结束")
            val header = "$label · 第${week}周"
            val dayCourses = rows.map { it.course }.filter { it.weekday == date.dayOfWeek.value }
            val items = dayCourses.filter { TeachingWeeks.parse(it.weeks)?.contains(week) == true }
                .sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }, { it.name })).map { course ->
                    val periods = if (course.startPeriod == course.endPeriod) "${course.startPeriod}节" else "${course.startPeriod}–${course.endPeriod}节"
                    val times = bells(course.place)
                    val start = times.find { it.number == course.startPeriod }?.start
                    val end = times.find { it.number == course.endPeriod }?.end
                    val time = when {
                        start != null && end != null -> "${start.format(clock)}–${end.format(clock)}"
                        start != null -> "${start.format(clock)} 上课"
                        end != null -> "${end.format(clock)} 下课"
                        else -> null
                    }
                    AgendaItem(course.name, periods, listOfNotNull(time, course.place.takeIf(String::isNotBlank))
                        .joinToString(" · "), course.place, start, end)
                }
            val message = if (items.isEmpty() && dayCourses.any { TeachingWeeks.parse(it.weeks) == null })
                "有课程周次待确认，请打开课表" else "今天没有安排课程"
            return TodayAgenda(header, message, items)
        }
    }
}

private fun itemView(context: Context, item: AgendaItem, open: PendingIntent, light: AppColorSet, dark: AppColorSet) =
    RemoteViews(context.packageName, R.layout.today_timetable_row).apply {
    setTextViewText(R.id.tt_widget_course, item.name)
    setTextViewText(R.id.tt_widget_room, item.room)
    setViewVisibility(R.id.tt_widget_room, if (item.room.isBlank()) View.GONE else View.VISIBLE)
    val time = when {
        item.start != null && item.end != null -> "${item.timeLabel}–${item.end.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
        item.start != null -> item.timeLabel
        item.end != null -> item.end.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + " 下课"
        else -> item.periods
    }
    setTextViewText(R.id.tt_widget_time, time)
    setOnClickPendingIntent(R.id.tt_widget_row, open)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setColorInt(R.id.tt_widget_course, "setTextColor", light.TextPrimary.toArgb(), dark.TextPrimary.toArgb())
        setColorInt(R.id.tt_widget_room, "setTextColor", light.TextSecondary.toArgb(), dark.TextSecondary.toArgb())
        setColorInt(R.id.tt_widget_time, "setTextColor", light.Accent.toArgb(), dark.Accent.toArgb())
    }
}

internal data class AgendaCardSize(val minHeightDp: Float, val visibleRows: Int, val remainingRows: Int)

internal fun agendaCardSizes(total: Int, fixedHeightPx: Int, rowHeightPx: Int, density: Float): List<AgendaCardSize> {
    // Android accepts up to 16 responsive layouts. Reserve one for a header-only card.
    return (0..minOf(total, 15)).map { count ->
        val height = if (count == 0) 1f else (fixedHeightPx + count * rowHeightPx) / density + 1f
        AgendaCardSize(height, count, total - count)
    }
}

private fun agendaCard(context: Context, agenda: TodayAgenda, size: AgendaCardSize, open: PendingIntent,
                       light: AppColorSet, dark: AppColorSet) =
    RemoteViews(context.packageName, R.layout.today_timetable_widget).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setColorStateList(R.id.tt_widget_root, "setBackgroundTintList", ColorStateList.valueOf(light.Card.toArgb()),
                ColorStateList.valueOf(dark.Card.toArgb()))
            setColorStateList(R.id.tt_widget_more, "setBackgroundTintList", ColorStateList.valueOf(light.ActiveBg.toArgb()),
                ColorStateList.valueOf(dark.ActiveBg.toArgb()))
            setColorInt(R.id.tt_widget_more, "setTextColor", light.Accent.toArgb(), dark.Accent.toArgb())
            setColorInt(R.id.tt_widget_date, "setTextColor", light.TextSecondary.toArgb(), dark.TextSecondary.toArgb())
            setColorInt(R.id.tt_widget_empty, "setTextColor", light.TextSecondary.toArgb(), dark.TextSecondary.toArgb())
        }
        setTextViewText(R.id.tt_widget_date, agenda.date.substringBefore(" · "))
        setTextViewText(R.id.tt_widget_empty, agenda.message)
        setViewVisibility(R.id.tt_widget_empty, if (agenda.items.isEmpty()) View.VISIBLE else View.GONE)
        setTextViewText(R.id.tt_widget_more, context.getString(R.string.timetable_widget_more, size.remainingRows))
        setViewVisibility(R.id.tt_widget_more, if (size.remainingRows > 0) View.VISIBLE else View.GONE)
        removeAllViews(R.id.tt_widget_rows)
        for (index in 0 until size.visibleRows) addView(R.id.tt_widget_rows, itemView(context, agenda.items[index], open, light, dark))
        setOnClickPendingIntent(R.id.tt_widget_root, open)
        setOnClickPendingIntent(R.id.tt_widget_header, open)
        setOnClickPendingIntent(R.id.tt_widget_empty, open)
    }

private fun compactAgendaCard(
    context: Context, agenda: TodayAgenda, now: LocalTime, narrow: Boolean, short: Boolean,
    open: PendingIntent, light: AppColorSet, dark: AppColorSet
): RemoteViews = RemoteViews(context.packageName,
    when {
        narrow && !short -> R.layout.today_timetable_narrow
        short && !narrow -> R.layout.today_timetable_wide
        short -> R.layout.today_timetable_compact
        else -> R.layout.today_timetable_focus
    }
).apply {
    val item = agenda.currentOrNext(now)
    val inProgress = item?.let { it.start != null && it.end != null && now >= it.start && now < it.end } == true
    val status = when {
        inProgress -> "上课中"
        item?.start?.let { it >= now } == true -> "下一节"
        else -> "今日课程"
    }
    val title = item?.name ?: if (agenda.items.isEmpty()) agenda.message else "今日课程已结束"
    val caption = when {
        item == null -> "今日课表"
        narrow && !short -> status
        narrow -> item.timeLabel
        else -> "$status · ${item.timeLabel}"
    }
    val wide = short && !narrow
    setTextViewText(R.id.tt_compact_caption, caption)
    if (wide) setViewVisibility(R.id.tt_compact_caption, View.GONE)
    setTextViewText(R.id.tt_compact_course, title)
    setTextViewText(R.id.tt_compact_room, item?.room.orEmpty())
    setViewVisibility(R.id.tt_compact_room, if ((!short || wide) && !item?.room.isNullOrBlank()) View.VISIBLE else View.GONE)
    if (narrow && !short || wide) {
        val ending = item?.end?.format(DateTimeFormatter.ofPattern("HH:mm"))
        val separator = if (wide) "–" else "–\n"
        val times = when {
            item == null -> ""
            item.start != null && ending != null -> "${item.timeLabel}$separator$ending"
            item.start != null -> "${item.timeLabel} 上课"
            ending != null -> "$ending 下课"
            else -> item.periods
        }
        setTextViewText(R.id.tt_widget_time, times)
        setViewVisibility(R.id.tt_widget_time, if (item == null) View.GONE else View.VISIBLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setColorInt(R.id.tt_widget_time, "setTextColor", light.Accent.toArgb(), dark.Accent.toArgb())
        }
    }
    setOnClickPendingIntent(R.id.tt_widget_root, open)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setColorStateList(R.id.tt_widget_root, "setBackgroundTintList", ColorStateList.valueOf(light.Card.toArgb()),
            ColorStateList.valueOf(dark.Card.toArgb()))
        setColorInt(R.id.tt_compact_caption, "setTextColor", light.Accent.toArgb(), dark.Accent.toArgb())
        setColorInt(R.id.tt_compact_course, "setTextColor", light.TextPrimary.toArgb(), dark.TextPrimary.toArgb())
        setColorInt(R.id.tt_compact_room, "setTextColor", light.TextSecondary.toArgb(), dark.TextSecondary.toArgb())
    }
}

private fun sizedAgendaCard(
    context: Context, agenda: TodayAgenda, now: LocalTime, open: PendingIntent, options: Bundle
): RemoteViews {
    val resources = context.resources
    val fixedHeight = 2 * resources.getDimensionPixelSize(R.dimen.tt_widget_vertical_padding) +
        resources.getDimensionPixelSize(R.dimen.tt_widget_header_height)
    val rowHeight = resources.getDimensionPixelSize(R.dimen.tt_widget_row_height)
    val sizes = agendaCardSizes(agenda.items.size, fixedHeight, rowHeight, resources.displayMetrics.density)
    val appearance = PrefsHelper.appearance(context)
    val light = appearance.palette.colors(appearance.mode == ThemeMode.DARK)
    val dark = appearance.palette.colors(appearance.mode != ThemeMode.LIGHT)
    fun card(width: Float, height: Float): RemoteViews {
        val narrow = width < WIDGET_NARROW_WIDTH_DP || height < WIDGET_MIN_DETAIL_HEIGHT_DP
        val short = height < WIDGET_SHORT_HEIGHT_DP
        val size = sizes.lastOrNull { it.minHeightDp <= height - 8f } ?: sizes.first()
        if (narrow || short || (size.visibleRows == 0 && agenda.items.isNotEmpty())) {
            return compactAgendaCard(context, agenda, now, narrow, short, open, light, dark)
        }
        return agendaCard(context, agenda, size, open, light, dark)
    }
    return responsiveWidgetViews(options, SizeF(150f, 180f), ::card)
}

class TodayTimetableWidget : AppWidgetProvider() {
    companion object {
        const val OPEN_TODAY = "cn.mudlife.app.timetable.OPEN_TODAY"
        private const val REFRESH = "cn.mudlife.app.timetable.REFRESH_WIDGET"
        private const val ACCOUNT_CHANGED = "cn.mudlife.app.TIMETABLE_ACCOUNT_CHANGED"
        private val worker = Executors.newSingleThreadExecutor()

        private fun component(context: Context) = ComponentName(context, TodayTimetableWidget::class.java)
        private fun refreshIntent(context: Context) = Intent(REFRESH).setComponent(component(context))

        fun refresh(context: Context) = context.sendBroadcast(refreshIntent(context))

        fun supportsPin(context: Context): Boolean = runCatching {
            AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
        }.getOrDefault(false)

        fun requestPin(context: Context): Boolean = runCatching {
            AppWidgetManager.getInstance(context).requestPinAppWidget(component(context), null, null)
        }.getOrDefault(false)

        private fun boundaryAlarm(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, refreshIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            REFRESH, ACCOUNT_CHANGED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                update(context, AppWidgetManager.getInstance(context).getAppWidgetIds(component(context)))
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, intArrayOf(id), newOptions)
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(boundaryAlarm(context))
    }

    private fun update(context: Context, ids: IntArray, newOptions: Bundle? = null) {
        if (ids.isEmpty()) return
        val result = goAsync()
        worker.execute {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val now = ZonedDateTime.now(campusZone)
                val today = now.toLocalDate()
                val model = TodayAgenda.read(context, today)
                val visible = model.remaining(now.toLocalTime())
                val open = Intent(context, TimetableActivity::class.java).setAction(OPEN_TODAY)
                val click = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                for (id in ids) {
                    manager.updateAppWidget(id, sizedAgendaCard(context, visible, now.toLocalTime(), click,
                        newOptions ?: manager.getAppWidgetOptions(id)))
                }
                val nextBoundary = model.items.asSequence().flatMap { sequenceOf(it.start, it.end) }
                    .filterNotNull().filter { it > now.toLocalTime() }.minOrNull()
                val nextUpdate = nextBoundary?.let { today.atTime(it).atZone(campusZone) }
                    ?: today.plusDays(1).atStartOfDay(campusZone)
                val alarms = context.getSystemService(AlarmManager::class.java)
                val at = nextUpdate.toInstant().toEpochMilli()
                val alarm = boundaryAlarm(context)
                val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
                if (exact) {
                    try {
                        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, at, alarm)
                    } catch (_: SecurityException) {
                        alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, alarm)
                    }
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, alarm)
                }
            } finally {
                result.finish()
            }
        }
    }
}
