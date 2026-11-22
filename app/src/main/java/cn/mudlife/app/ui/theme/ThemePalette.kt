package cn.mudlife.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color

data class AppAppearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.MOSS
) {
    fun resolve(systemDark: Boolean) = copy(mode = mode.resolve(systemDark))

    fun colors(context: Context): AppColorSet {
        val systemDark = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return palette.colors(mode.resolve(systemDark) == ThemeMode.DARK)
    }
}

enum class ThemePalette(val label: String) {
    MOSS("苔庭"),
    WHEAT("麦笺"),
    ISLE("雾屿"),
    CHERRY("晚樱"),
    CHESTNUT("栗墨");

    val light: AppColorSet
        get() = when (this) {
            MOSS -> LightColors
            WHEAT -> wheatLight
            ISLE -> isleLight
            CHERRY -> cherryLight
            CHESTNUT -> chestnutLight
        }

    val dark: AppColorSet
        get() = when (this) {
            MOSS -> DarkColors
            WHEAT -> wheatDark
            ISLE -> isleDark
            CHERRY -> cherryDark
            CHESTNUT -> chestnutDark
        }

    fun colors(dark: Boolean): AppColorSet = if (dark) this.dark else light

    companion object {
        fun restore(saved: String) = entries.firstOrNull { it.name == saved } ?: MOSS
    }
}

private fun lightColors(
    background: Long, surface: Long, active: Long, border: Long,
    accent: Long, ink: Long, muted: Long
): AppColorSet = LightColors.copy(
    Background = Color(background), Card = Color(surface), SolidSurface = Color(surface),
    Primary = Color(ink), TextPrimary = Color(ink), TextSecondary = Color(muted),
    Accent = Color(accent), OnAccent = Color(surface), ActiveBg = Color(active),
    Border = Color(border), SurfaceVariant = Color(active)
)

private fun darkColors(
    background: Long, card: Long, surface: Long, active: Long, variant: Long, border: Long,
    accent: Long, onAccent: Long, ink: Long, muted: Long
): AppColorSet = DarkColors.copy(
    Background = Color(background), Card = Color(card), SolidSurface = Color(surface),
    Primary = Color(ink), TextPrimary = Color(ink), TextSecondary = Color(muted),
    Accent = Color(accent), OnAccent = Color(onAccent), ActiveBg = Color(active),
    Border = Color(border), SurfaceVariant = Color(variant)
)

private val wheatLight = lightColors(
    background = 0xFFF7F2E5, surface = 0xFFFFFAF0, active = 0xFFEEE4C5, border = 0xFFE7DDC3,
    accent = 0xFF6B5329, ink = 0xFF30291D, muted = 0xFF6C604D
)
private val wheatDark = darkColors(
    background = 0xFF1E1A13, card = 0xFF2B2519, surface = 0xFF362E20,
    active = 0xFF493A21, variant = 0xFF413625, border = 0xFF4E4230,
    accent = 0xFFE2C68B, onAccent = 0xFF282011, ink = 0xFFF1E7D3, muted = 0xFFBAAC8F
)
private val isleLight = lightColors(
    background = 0xFFF0F4F7, surface = 0xFFF8FCFF, active = 0xFFE0EBF3, border = 0xFFD8E4EC,
    accent = 0xFF36556B, ink = 0xFF24313B, muted = 0xFF586975
)
private val isleDark = darkColors(
    background = 0xFF161C22, card = 0xFF222C35, surface = 0xFF2C3742,
    active = 0xFF2C4355, variant = 0xFF34414E, border = 0xFF414E5A,
    accent = 0xFFB2D1E8, onAccent = 0xFF182935, ink = 0xFFDDE6EF, muted = 0xFFA6B8C8
)
private val cherryLight = lightColors(
    background = 0xFFF8F1F3, surface = 0xFFFFF8FA, active = 0xFFF3E3EA, border = 0xFFEADDE2,
    accent = 0xFF794A60, ink = 0xFF372733, muted = 0xFF725C68
)
private val cherryDark = darkColors(
    background = 0xFF21191E, card = 0xFF2F242C, surface = 0xFF392C36,
    active = 0xFF4B3140, variant = 0xFF433440, border = 0xFF51434C,
    accent = 0xFFE4B5CD, onAccent = 0xFF352032, ink = 0xFFEDE1EA, muted = 0xFFC4AEBB
)
private val chestnutLight = lightColors(
    background = 0xFFF5EFE9, surface = 0xFFFDF8F3, active = 0xFFEDE2D8, border = 0xFFE5D9CE,
    accent = 0xFF674B38, ink = 0xFF362B25, muted = 0xFF6D5D52
)
private val chestnutDark = darkColors(
    background = 0xFF1F1916, card = 0xFF2E241E, surface = 0xFF392E27,
    active = 0xFF49382C, variant = 0xFF43372E, border = 0xFF504239,
    accent = 0xFFDBBA9F, onAccent = 0xFF302219, ink = 0xFFEEE3DA, muted = 0xFFBFAEA2
)
