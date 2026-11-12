package com.audiostats.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 应用的固定品牌色；其余应用按时长排名依次取 [PALETTE] */
private val BRAND_COLORS = mapOf(
    "tv.danmaku.bili" to Color(0xFFFB7299), // 哔哩哔哩粉
    "com.netease.cloudmusic" to Color(0xFFC20C0C), // 网易云红（App 图标的红）
)

internal val QQ_BROWSER_BLUE = Color(0xFF3B82F6)

/** 区分度高、明暗主题下都看得清的分类色，不含粉色和红色（留给哔哩哔哩、网易云） */
private val PALETTE = listOf(
    QQ_BROWSER_BLUE,
    Color(0xFFF59E0B), // 琥珀
    Color(0xFF10B981), // 绿
    Color(0xFF8B5CF6), // 紫
    Color(0xFF06B6D4), // 青
    Color(0xFF84CC16), // 黄绿
    Color(0xFFF97316), // 橙
)

/** 超出调色板的分组合并为"其他" */
val OTHER_COLOR = Color(0xFF9CA3AF)

/**
 * 按时长降序的分组键 → 颜色。应用模式下品牌色优先；
 * 排名超过调色板数量的分组都用 [OTHER_COLOR]。
 */
fun assignColors(keys: List<String>, useBrand: Boolean): Map<String, Color> {
    val result = LinkedHashMap<String, Color>()
    var next = 0
    for (k in keys) {
        val brand = if (useBrand) BRAND_COLORS[k] else null
        result[k] = when {
            brand != null -> brand
            next < PALETTE.size -> PALETTE[next++]
            else -> OTHER_COLOR
        }
    }
    return result
}

/** 无描边的文字切换：选中项变色加粗 */
@Composable
fun <T> TextToggle(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 4.dp,
    fontSize: TextUnit = 14.sp,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (label, value) ->
            val isSelected = value == selected
            Text(
                label,
                fontSize = fontSize,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
