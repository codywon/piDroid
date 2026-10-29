package com.pi.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 设置页统一组件（对齐「分组胶囊 / Inset Grouped」方案）。
 *
 * 设计约束：
 *  - 一屏只用一种描边描线：分组卡 1dp outline，组内分隔线同一颜色，不再混用 0.5f / 1f alpha；
 *  - 一行最多一个主控件（Switch 或 chevron），次级动作走 trailing 里的轻量文字按钮；
 *  - 标题 13sp Medium、副标题 11sp muted、值 11sp secondary（可等宽），行高固定 44dp 触控高度。
 */

/** 分组小标题：11sp 弱色，只做分区提示，不抢标题层级 */
@Composable
fun SettingsGroupLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    val theme = LocalAppTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = theme.textMuted
        )
        if (!trailing.isNullOrBlank()) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = trailing,
                fontSize = 11.sp,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 分组卡容器：一张 Surface 承载多行，行间用 [SettingsRow] 的 topDivider 自然分隔 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = theme.surfaceContainerLow,
        border = BorderStroke(1.dp, theme.outline),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

/**
 * 通用设置行。
 *
 * @param value 右对齐的值文本（地址、令牌、状态），与 trailing 互不冲突
 * @param monoValue 值使用等宽字体（地址 / 令牌 / 版本号）
 * @param topDivider 非首行需置 true，用于绘制组内分隔线
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    monoValue: Boolean = false,
    monoSubtitle: Boolean = false,
    titleColor: Color? = null,
    topDivider: Boolean = false,
    showChevron: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    val theme = LocalAppTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (topDivider) {
            HorizontalDivider(color = theme.outline, thickness = 1.dp)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val lead = leading
            if (lead != null) {
                lead()
                Spacer(modifier = Modifier.width(9.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor ?: theme.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = theme.textMuted,
                        fontFamily = if (monoSubtitle) FontFamily.Monospace else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!value.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = value,
                    fontSize = 11.sp,
                    color = valueColor ?: theme.textSecondary,
                    fontFamily = if (monoValue) FontFamily.Monospace else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            trailing()
            if (showChevron) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = theme.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 统一开关：主色轨道，避免各页各写一套 SwitchDefaults.colors */
@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = theme.primary,
            uncheckedThumbColor = theme.textMuted,
            uncheckedTrackColor = theme.surfaceContainer
        )
    )
}

/** 胶囊筛选/分段标签：选中态主色描边 + 主色淡底，未选中描边中性 */
@Composable
fun SettingsChip(
    text: String,
    selected: Boolean = false,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (selected) theme.primary.copy(alpha = 0.14f) else theme.surfaceContainer,
        border = BorderStroke(1.dp, if (selected) theme.primary else theme.outline),
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = if (selected) theme.primary else theme.textSecondary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/** 分组卡内的操作行（两个胶囊按钮并排），用于替代正文里的等宽按钮堆叠 */
@Composable
fun SettingsGroupActionRow(
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** 组内次要动作的文字胶囊（试听 / 显示 / 复制），保持与 Switch 同一视觉权重 */
@Composable
fun SettingsInlineAction(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = color ?: theme.primary,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    )
}

/** 状态点：绿色=生效，灰色=关闭，用于紧凑行的前置指示 */
@Composable
fun SettingsStatusDot(active: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    Spacer(
        modifier = modifier
            .size(7.dp)
            .background(
                color = if (active) Color(0xFF2EA043) else theme.textMuted,
                shape = CircleShape
            )
    )
}

/** 轻量搜索框：分组卡内使用，避免 Material 默认 56dp 输入框撑高列表头 */
@Composable
fun SettingsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = theme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = theme.textMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 12.sp,
                        color = theme.textMuted,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = theme.textPrimary),
                    cursorBrush = SolidColor(theme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清空",
                        tint = theme.textMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}
