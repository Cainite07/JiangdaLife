package cn.mudlife.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.mudlife.app.api.NetworkModule
import cn.mudlife.app.timetable.TodayTimetableWidget
import cn.mudlife.app.ui.MainViewModel
import cn.mudlife.app.ui.UnifiedApp
import cn.mudlife.app.ui.theme.*
import cn.mudlife.app.utils.AppLogger
import cn.mudlife.app.utils.PrefsHelper
import cn.mudlife.app.utils.UpdateManager
import cn.mudlife.app.widget.DrinkingWaterWidget
import cn.mudlife.campuscard.CampusViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        lifecycleScope.launch(Dispatchers.IO) { UpdateManager.prepareUpdateCache(this@MainActivity) }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppLogger.init(this@MainActivity)
                PrefsHelper.init(this@MainActivity)
                NetworkModule.restoreFromPrefs()
            }
            setContent {
                val appearance = remember { mutableStateOf(PrefsHelper.appearance(this@MainActivity)) }
                val life: MainViewModel = viewModel()
                val campus: CampusViewModel = viewModel()
                CircularRevealThemeHost(appearance, onAppearanceChanged = {
                    PrefsHelper.saveAppearance(it)
                    TodayTimetableWidget.refresh(this@MainActivity)
                    DrinkingWaterWidget.refresh(this@MainActivity)
                }) {
                    val windowDark = LocalThemeWindowDark.current
                    val background = AppColors.Background
                    SideEffect {
                        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !windowDark
                            isAppearanceLightNavigationBars = !windowDark
                        }
                    }
                    UnifiedApp(life, campus)
                }
            }
        }
    }
}
