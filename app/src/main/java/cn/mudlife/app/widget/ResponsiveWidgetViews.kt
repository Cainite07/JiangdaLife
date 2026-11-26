package cn.mudlife.app.widget

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

// Hosts provide actual dp sizes, which vary with their grid, orientation and margins.
internal const val WIDGET_NARROW_WIDTH_DP = 104f
internal const val WIDGET_SHORT_HEIGHT_DP = 128f
internal const val WIDGET_MIN_DETAIL_HEIGHT_DP = 60f

internal fun responsiveWidgetViews(
    options: Bundle, defaults: SizeF, build: (width: Float, height: Float) -> RemoteViews
): RemoteViews {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        @Suppress("DEPRECATION")
        val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            .orEmpty().filter { it.width > 0f && it.height > 0f }.distinct().take(16)
        if (sizes.isNotEmpty()) return RemoteViews(sizes.associateWith { build(it.width, it.height) })
    }
    fun dimension(key: String, fallback: Float) = options.getInt(key).takeIf { it > 0 }?.toFloat() ?: fallback
    val width = dimension(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defaults.width)
    val height = dimension(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, defaults.height)
    val landscapeWidth = dimension(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
    val landscapeHeight = dimension(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
    val portrait = build(width, height)
    return if (width == landscapeWidth && height == landscapeHeight) portrait
        else RemoteViews(build(landscapeWidth, landscapeHeight), portrait)
}
