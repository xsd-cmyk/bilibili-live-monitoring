package com.example.bilimonitor.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 浮动搜索卡片（用户要求：各页面的搜索栏统一成卡片形式，与 Dock / 标题栏同一套语言）。
 *
 * 抽成共用组件而不是在每个页面就地包一层 `Surface`：
 * 就地包装要在既有代码里插开括号、再去文件尾部补闭括号，本项目的 H36/H39 两次
 * 都因此编不过（靠肉眼数括号）。共用组件把"卡片 + 内嵌输入框"的结构固定下来，
 * 各页面只留一行调用，**结构错误不可能再发生**，顺带保证三处搜索栏长得完全一样。
 *
 * 颜色取 `surfaceContainer`：自动继承外观设置里的卡片透明度/明暗与"玻璃同步到卡片"。
 *
 * @param modifier 由调用方决定宽度与位置（例如在 Row 里传 `Modifier.weight(1f)`）
 */
@Composable
fun FloatingSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    // ★ 外观规则统一收在 ui/common/FloatingCard.kt，与标题栏 / 筛选卡 / Dock 完全同源：
    //   · 容器色走 colorScheme 的 surface 系列 ⇒ 外观设置里的卡片透明度/明暗/玻璃自动生效；
    //   · tonalElevation 恒为 0（原来写的 3.dp 是空操作 —— M3 只在容器色 == surface 时才混
    //     surfaceTint，见该文件"规则一"）；
    //   · **半透明时不给阴影** —— 用户报的"搜索框下面有一个淡淡的白色方框"就是半透明卡片
    //     下沿那圈黑色平台阴影（黑剪影不跟着卡片一起变淡），见该文件"规则二"。
    FloatingCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            // 必须给 label：读屏（TalkBack）靠 label 播报"这个输入框搜的是什么"，
            // 只有 placeholder 时焦点落上来只报"编辑框"，用户不知道能搜什么。
            // label 在未聚焦时就画在框内（观感与原来的 placeholder 相同），聚焦后上浮。
            label = { Text(placeholder) },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp)
        )
    }
}
