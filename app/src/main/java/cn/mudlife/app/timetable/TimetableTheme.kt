package cn.mudlife.app.timetable

import android.content.Context
import androidx.core.graphics.ColorUtils
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cn.mudlife.app.R
import cn.mudlife.app.ui.theme.AppAppearance
import cn.mudlife.app.ui.theme.AppColors
import cn.mudlife.app.ui.theme.DarkColors
import cn.mudlife.app.ui.theme.MudLifeThemeSpecific
import cn.mudlife.app.utils.PrefsHelper

internal fun darkCourseFill(swatch: Int, paper: Int = DarkColors.Background.toArgb()) =
    ColorUtils.blendARGB(paper, swatch, .50f)

internal val timetableSheetSurface
    @Composable get() = if (AppColors.isDark) AppColors.SolidSurface else AppColors.Background

internal object TimetableType {
    private val displayFamily = FontFamily(Font(R.font.timetable_heading, weight = FontWeight.Medium))
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp)
    val detail = body.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val title = TextStyle(fontFamily = displayFamily, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val dataTitle = title.copy(fontFamily = FontFamily.SansSerif)
    val courseName = body.copy(fontWeight = FontWeight.Normal)
    val courseTitle = dataTitle.copy(fontWeight = FontWeight.Normal)
    val week = title.copy(fontSize = 25.sp, lineHeight = 31.sp)
    val navigation = title.copy(fontSize = 15.sp, lineHeight = 20.sp)
    val section = title.copy(fontSize = 14.sp, lineHeight = 19.sp)
    val number = body.copy(fontSize = 17.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
}

internal fun timetableThemeIsDark(context: Context): Boolean =
    PrefsHelper.appearance(context).colors(context).isDark

@Composable
internal fun TimetableTheme(appearance: AppAppearance? = null, content: @Composable () -> Unit) {
    val timetableContent: @Composable () -> Unit = {
        val hostPress = LocalIndication.current
        val hostType = MaterialTheme.typography
        val type = remember(hostType) {
            hostType.copy(
                headlineSmall = TimetableType.title,
                titleLarge = TimetableType.title,
                titleMedium = TimetableType.body.copy(fontWeight = FontWeight.Medium),
                bodyLarge = TimetableType.body, bodyMedium = TimetableType.body,
                bodySmall = TimetableType.detail, labelMedium = TimetableType.detail,
                labelLarge = TimetableType.body.copy(fontWeight = FontWeight.Medium)
            )
        }
        MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = type, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(LocalIndication provides hostPress, content = content)
        }
    }
    if (appearance == null) timetableContent()
    else MudLifeThemeSpecific(appearance, timetableContent)
}
