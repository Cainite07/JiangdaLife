package cn.mudlife.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cn.mudlife.app.model.RecentDevice
import cn.mudlife.app.ui.theme.AppColors
import cn.mudlife.app.utils.HapticHelper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private data class DeviceDrag(val sn: String, val target: String, val offset: Float, val settling: Boolean = false)

@Composable
internal fun RecentDrinkingDevices(viewModel: MainViewModel, listState: LazyListState) {
    val devices = viewModel.recentDevices
    val keys = devices.map { it.snCode }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rowHeights = remember(keys) { mutableStateMapOf<String, Int>() }
    var drag by remember(keys) { mutableStateOf<DeviceDrag?>(null) }
    var revealed by remember(keys) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<RecentDevice?>(null) }

    BackHandler(enabled = revealed != null && deleting == null) { revealed = null }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) revealed = null
        }
    }

    fun positions(order: List<String>): Map<String, Int> {
        var y = 0
        return order.associateWith { sn -> y.also { y += rowHeights[sn] ?: 0 } }
    }

    fun reordered(sn: String, target: String): List<String> = keys.toMutableList().apply {
        add(keys.indexOf(target), removeAt(keys.indexOf(sn)))
    }

    val base = positions(keys)
    val preview = drag?.let { positions(reordered(it.sn, it.target)) } ?: base
    fun finishDrag(commit: Boolean) {
        val ended = drag?.takeUnless { it.settling } ?: return
        val target = if (commit) ended.target else ended.sn
        val goal = positions(reordered(ended.sn, target)).getValue(ended.sn) - base.getValue(ended.sn)
        drag = ended.copy(target = target, settling = true)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                Animatable(ended.offset).animateTo(goal.toFloat(), spring(stiffness = Spring.StiffnessMediumLow)) {
                    drag = ended.copy(target = target, offset = value, settling = true)
                }
            } finally {
                if (ended.sn != target) viewModel.moveRecentDrinkingDevice(ended.sn, target)
                drag = null
            }
        }
    }

    Surface(color = AppColors.Card, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            devices.forEachIndexed { index, device ->
                val sn = device.snCode
                key(keys, sn) {
                    val floating = drag?.takeIf { it.sn == sn }
                    val neighbourOffset by animateFloatAsState(
                        (preview.getValue(sn) - base.getValue(sn)).toFloat(),
                        spring(stiffness = Spring.StiffnessMediumLow), label = "device position"
                    )
                    val trackDrag by rememberUpdatedState<(Float) -> Unit> { delta ->
                        val current = drag
                        if (current?.sn == sn && !current.settling) {
                            val height = rowHeights[sn] ?: 0
                            val start = base.getValue(sn)
                            val limit = (rowHeights.values.sum() - height - start).coerceAtLeast(0)
                            val offset = (current.offset + delta).coerceIn(-start.toFloat(), limit.toFloat())
                            val center = start + height / 2f + offset
                            val target = keys.minByOrNull { candidate ->
                                abs(base.getValue(candidate) + (rowHeights[candidate] ?: 0) / 2f - center)
                            } ?: sn
                            if (target != current.target) HapticHelper.tick(context)
                            drag = current.copy(target = target, offset = offset)
                        }
                    }
                    val endDrag by rememberUpdatedState<(Boolean) -> Unit> { commit ->
                        if (drag?.sn == sn) finishDrag(commit)
                    }
                    val reorderGesture = if (keys.size < 2) Modifier else Modifier.pointerInput(sn) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (drag == null && rowHeights.size == keys.size) {
                                    revealed = null
                                    drag = DeviceDrag(sn, sn, 0f)
                                    HapticHelper.tick(context)
                                }
                            },
                            onDragEnd = { endDrag(true) },
                            onDragCancel = { endDrag(false) },
                            onDrag = { _, amount -> trackDrag(amount.y) }
                        )
                    }
                    val askDelete = {
                        revealed = null
                        deleting = device
                    }
                    val accessibilityActions = buildList {
                        keys.getOrNull(index - 1)?.let { target ->
                            add(CustomAccessibilityAction("上移") { viewModel.moveRecentDrinkingDevice(sn, target); true })
                        }
                        keys.getOrNull(index + 1)?.let { target ->
                            add(CustomAccessibilityAction("下移") { viewModel.moveRecentDrinkingDevice(sn, target); true })
                        }
                        add(CustomAccessibilityAction("删除常用饮水机") { askDelete(); true })
                    }
                    Column(
                        Modifier.fillMaxWidth().zIndex(if (floating != null) 1f else 0f)
                            .offset { IntOffset(0, (floating?.offset ?: neighbourOffset).roundToInt()) }
                            .onSizeChanged { rowHeights[sn] = it.height }
                    ) {
                        RecentDrinkingDeviceRow(
                            device, reorderGesture, floating != null, revealed == sn,
                            accessibilityActions, canSwipe = { drag == null },
                            onSwipeStart = { if (revealed != sn) revealed = null },
                            onReveal = { open ->
                                if (open) {
                                    if (revealed != sn) HapticHelper.tick(context)
                                    revealed = sn
                                } else if (revealed == sn) revealed = null
                            },
                            onTap = { revealed = null },
                            onDelete = askDelete,
                            onWater = {
                                if (revealed != null) revealed = null
                                else if (drag == null) viewModel.startRecentDevice(device)
                            }
                        )
                        // Keep the measured row heights stable when a separator changes position.
                        HorizontalDivider(
                            color = if (index < devices.lastIndex) AppColors.Border.copy(alpha = .4f)
                            else AppColors.Card
                        )
                    }
                }
            }
        }
    }

    deleting?.let { device ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            shape = RoundedCornerShape(22.dp),
            containerColor = AppColors.SolidSurface,
            title = { Text("删除这条常用饮水机？") },
            text = { Text(device.cleanName, color = AppColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteRecentDrinkingDevice(device.snCode); deleting = null }) {
                    Text("删除", color = AppColors.Danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun RecentDrinkingDeviceRow(
    device: RecentDevice,
    reorderGesture: Modifier,
    lifted: Boolean,
    revealed: Boolean,
    accessibilityActions: List<CustomAccessibilityAction>,
    canSwipe: () -> Boolean,
    onSwipeStart: () -> Unit,
    onReveal: (Boolean) -> Unit,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    onWater: () -> Unit
) {
    val actionWidth = 78.dp
    val density = LocalDensity.current
    val widthPx = with(density) { actionWidth.toPx() }
    val flingSpeed = with(density) { 650.dp.toPx() }
    var fingerOffset by remember { mutableStateOf<Float?>(null) }
    val animatedOffset by animateFloatAsState(
        fingerOffset ?: if (revealed) -widthPx else 0f,
        if (fingerOffset != null) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "delete reveal"
    )
    val swipeAllowed by rememberUpdatedState(canSwipe)
    val swipeStart by rememberUpdatedState(onSwipeStart)
    val reveal by rememberUpdatedState(onReveal)
    val tap by rememberUpdatedState(onTap)
    val shape = RoundedCornerShape(14.dp)
    val tint = if (device.isHot) AppColors.Hot else AppColors.Accent
    val lift by animateFloatAsState(if (lifted) 1f else 0f, label = "device lift")
    val foreground = lerp(AppColors.Card, AppColors.SolidSurface, lift)

    Box(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .shadow(if (lifted) 5.dp else 0.dp, shape, clip = false)
            .clip(shape).clipToBounds()
            .pointerInput(widthPx, flingSpeed) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val velocity = VelocityTracker()
                    velocity.addPosition(down.uptimeMillis, down.position)
                    var horizontal = false
                    var origin = 0f
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed || !swipeAllowed()) break
                            val delta = change.position - down.position
                            if (!change.pressed) {
                                if (horizontal) {
                                    change.consume()
                                    velocity.addPosition(change.uptimeMillis, change.position)
                                    val speed = velocity.calculateVelocity().x
                                    val position = fingerOffset ?: 0f
                                    reveal(when {
                                        speed < -flingSpeed -> true
                                        speed > flingSpeed -> false
                                        else -> position < -widthPx * .45f
                                    })
                                } else tap()
                                break
                            }
                            if (!horizontal) {
                                if (abs(delta.y) >= viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) break
                                if (abs(delta.x) < viewConfiguration.touchSlop) continue
                                origin = animatedOffset
                                if (delta.x > 0 && origin >= -.5f) break
                                horizontal = true
                                swipeStart()
                            }
                            velocity.addPosition(change.uptimeMillis, change.position)
                            fingerOffset = (origin + delta.x).coerceIn(-widthPx, 0f)
                            // Consume only horizontal swipes once their direction is known.
                            if (change.positionChange().x != 0f) change.consume()
                        }
                    } finally {
                        fingerOffset = null
                    }
                }
            }
    ) {
        if (animatedOffset < -.5f) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    onClick = onDelete, enabled = revealed,
                    modifier = Modifier.width(actionWidth).fillMaxHeight(),
                    color = AppColors.Danger.copy(alpha = .12f), contentColor = AppColors.Danger
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, Modifier.size(20.dp))
                        Text("删除", fontSize = 12.sp)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().absoluteOffset { IntOffset(animatedOffset.roundToInt(), 0) }
                .background(foreground, shape)
                .semantics { customActions = accessibilityActions }
                .then(reorderGesture)
                .heightIn(min = 58.dp).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .09f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(deviceSymbol(if (device.isHot) "♨️" else "❄️"), null, Modifier.size(20.dp), tint = tint)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(device.cleanName, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary, maxLines = 2)
                Text("编号 ${device.snCode.substringAfterLast(",").takeLast(8)}", fontSize = 12.sp,
                    color = AppColors.TextSecondary, modifier = Modifier.padding(top = 3.dp))
            }
            OutlinedButton(
                onClick = onWater, enabled = !lifted && fingerOffset == null,
                shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, AppColors.Border),
                contentPadding = PaddingValues(horizontal = 15.dp)
            ) { Text("出水", fontSize = 13.sp) }
        }
    }
}
