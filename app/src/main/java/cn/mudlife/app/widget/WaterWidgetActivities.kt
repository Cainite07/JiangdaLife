package cn.mudlife.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import cn.mudlife.app.data.DeviceCommands
import cn.mudlife.app.model.RecentDevice
import cn.mudlife.app.ui.deviceSymbol
import cn.mudlife.app.ui.theme.AppColors
import cn.mudlife.app.ui.theme.MudLifeThemeSpecific
import cn.mudlife.app.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WaterWidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        if (!DrinkingWaterWidget.ownsWidget(this, id)) {
            finish()
            return
        }
        lifecycleScope.launch {
            val (owner, available, saved) = withContext(Dispatchers.IO) {
                PrefsHelper.ensureInitialized(applicationContext)
                Triple(PrefsHelper.waterWidgetOwner, PrefsHelper.getRecentDevices(), PrefsHelper.waterWidgetDevices(id))
            }
            val devices = (listOfNotNull(saved.cold, saved.hot) + available).distinctBy { it.snCode }
            setContent {
                MudLifeThemeSpecific(PrefsHelper.appearance(this@WaterWidgetConfigurationActivity)) {
                    WaterWidgetEditor(owner != null, devices, saved, ::finish) { next ->
                        val active = PrefsHelper.getActiveOrders().map { it.snCode }.toSet()
                        val removing = listOfNotNull(saved.cold, saved.hot)
                            .filter { it.snCode != next.device(it.isHot)?.snCode }
                        when {
                            removing.any { it.snCode in active || DeviceCommands.isBusy(it.snCode) } ->
                                Toast.makeText(this@WaterWidgetConfigurationActivity, "请先确认原饮水机已停止", Toast.LENGTH_SHORT).show()
                            owner != null && DrinkingWaterWidget.ownsWidget(this@WaterWidgetConfigurationActivity, id) &&
                                PrefsHelper.bindWaterWidget(id, owner, next) -> {
                                DrinkingWaterWidget.refresh(this@WaterWidgetConfigurationActivity)
                                setResult(RESULT_OK, result)
                                finish()
                            }
                            else -> Toast.makeText(this@WaterWidgetConfigurationActivity, "无法保存饮水机设置", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaterWidgetEditor(
    signedIn: Boolean, devices: List<RecentDevice>, saved: PrefsHelper.WaterWidgetDevices,
    back: () -> Unit, save: (PrefsHelper.WaterWidgetDevices) -> Unit
) {
    var cold by rememberSaveable { mutableStateOf(saved.cold?.snCode) }
    var hot by rememberSaveable { mutableStateOf(saved.hot?.snCode) }
    var compactHot by rememberSaveable { mutableStateOf(saved.compactHot) }
    val available = signedIn && devices.isNotEmpty()
    val shape = RoundedCornerShape(20.dp)
    Surface(color = AppColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = AppColors.TextPrimary)
                }
                Text("饮水机设置", color = AppColors.TextPrimary, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (!available) {
                    Surface(color = AppColors.Card, shape = shape, modifier = Modifier.fillMaxWidth()) {
                        Text(if (signedIn) "扫码使用一次饮水机，就能在这里绑定。" else "请先在江大生活登录趣智校园。",
                            Modifier.padding(18.dp), color = AppColors.TextSecondary)
                    }
                } else {
                    DeviceChoices(false, devices.filterNot { it.isHot }, cold) { cold = it }
                    DeviceChoices(true, devices.filter { it.isHot }, hot) { hot = it }
                    if (cold != null && hot != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("1×1 默认水位", color = AppColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(false, true).forEach { useHot ->
                                    val selected = compactHot == useHot
                                    val tint = if (useHot) AppColors.Hot else AppColors.Cold
                                    Box(Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
                                        .background(if (selected) tint.copy(alpha = .15f) else AppColors.Card)
                                        .selectable(selected, role = Role.RadioButton) { compactHot = useHot }
                                        .heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                                        Text(if (useHot) "热水" else "冷水", color = if (selected) tint else AppColors.TextSecondary,
                                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (available) {
                Button(
                    onClick = {
                        save(PrefsHelper.WaterWidgetDevices(devices.firstOrNull { it.snCode == cold },
                            devices.firstOrNull { it.snCode == hot }, compactHot))
                    },
                    enabled = cold != null || hot != null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp).heightIn(min = 49.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent)
                ) { Text("保存设置") }
            }
        }
    }
}

@Composable
private fun DeviceChoices(hot: Boolean, devices: List<RecentDevice>, selected: String?, select: (String?) -> Unit) {
    val tint = if (hot) AppColors.Hot else AppColors.Cold
    val title = if (hot) "热水" else "冷水"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = .12f)),
                contentAlignment = Alignment.Center) {
                Icon(deviceSymbol(if (hot) "♨️" else "❄️"), null, Modifier.size(16.dp), tint)
            }
            Text(title, color = AppColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
        }
        Surface(color = AppColors.Card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            if (devices.isEmpty()) {
                Text("暂无常用${title}饮水机", Modifier.padding(16.dp), color = AppColors.TextSecondary)
            } else {
                Column(Modifier.selectableGroup()) {
                    (devices + listOf(null)).forEachIndexed { index, device ->
                        val chosen = selected == device?.snCode
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = AppColors.Border.copy(alpha = .5f))
                        Row(Modifier.fillMaxWidth().background(if (chosen) tint.copy(alpha = .08f) else AppColors.Card)
                            .selectable(chosen, role = Role.RadioButton) { select(device?.snCode) }
                            .heightIn(min = 50.dp).padding(horizontal = 15.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(device?.cleanName ?: "不绑定", color = if (device == null) AppColors.TextSecondary else AppColors.TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium)
                                if (device != null) Text("编号 ${device.snCode.substringAfterLast(",").takeLast(8)}",
                                    color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            RadioButton(chosen, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = tint))
                        }
                    }
                }
            }
        }
    }
}
