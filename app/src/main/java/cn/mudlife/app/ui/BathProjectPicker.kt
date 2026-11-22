package cn.mudlife.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.mudlife.app.ui.theme.AppColors
import cn.mudlife.app.utils.HapticHelper

@Composable
internal fun BathProjectPicker(
    life: MainViewModel, projectId: String, enabled: Boolean = true, onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentProject = life.qzhqProjects.firstOrNull { it.id.toString() == projectId }
    Box {
        TextButton(
            enabled = enabled,
            onClick = {
                HapticHelper.tick(context)
                open = true
                life.loadQzhqProjects()
            },
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text(currentProject?.label ?: "洗浴分区", Modifier.widthIn(max = 159.dp),
                color = if (enabled) AppColors.Accent else AppColors.TextSecondary,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, "选择洗浴分区", Modifier.size(16.dp))
        }
        MaterialTheme(
            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(14.dp)),
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = AppColors.SolidSurface, surfaceContainer = AppColors.SolidSurface
            )
        ) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                life.qzhqProjects.forEach { project ->
                    val id = project.id.toString()
                    val current = id == projectId
                    DropdownMenuItem(
                        modifier = Modifier.padding(horizontal = 5.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (current) AppColors.ActiveBg else Color.Transparent)
                            .semantics { selected = current },
                        text = {
                            Text(project.label, fontSize = 13.sp,
                                color = if (current) AppColors.Accent else AppColors.TextPrimary,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                        },
                        trailingIcon = {
                            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                if (current) Icon(Icons.Outlined.Check, null, tint = AppColors.Accent)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        onClick = {
                            HapticHelper.tick(context)
                            open = false
                            onSelect(id)
                        }
                    )
                }
                if (life.qzhqProjects.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(if (life.qzhqProjectsError) "重试加载分区" else "正在加载分区…", fontSize = 13.sp) },
                        enabled = life.qzhqProjectsError,
                        onClick = life::loadQzhqProjects
                    )
                }
            }
        }
    }
}
