package com.example.bilimonitor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台常驻通知的模板渲染（用户要求：通知内容可自定义）。
 *
 * 值得单测的原因：这段逻辑直接决定用户**每次下拉通知栏**看到什么，
 * 而其中两条规则很容易在"顺手优化"时被改坏：
 *  - 数字未知时必须显示 `—`，**不能谎报 0**（进程刚起来还没检查过主播）；
 *  - 认不出的占位符要**原样保留**，而不是静默吞掉用户写的内容。
 */
class NotificationTemplateTest {

    @Test
    fun `placeholders are replaced with counts`() {
        assertEquals(
            "在监控 12 位主播，3 位正在直播",
            NotificationTemplate.render("在监控 {count} 位主播，{live} 位正在直播", 12, 3)
        )
    }

    @Test
    fun `same placeholder can appear more than once`() {
        assertEquals(
            "12 位 / 12 位",
            NotificationTemplate.render("{count} 位 / {count} 位", 12, 0)
        )
    }

    @Test
    fun `unknown values render as dash instead of lying with zero`() {
        // 进程刚启动、还没跑过一轮 Tick 时是 null —— 这时说"0 位在直播"是错的
        assertEquals(
            "监控 — 位，— 位在直播",
            NotificationTemplate.render("监控 {count} 位，{live} 位在直播", null, null)
        )
    }

    @Test
    fun `unknown placeholder is kept as the user typed it`() {
        assertEquals(
            "你好 {name}，共 5 位",
            NotificationTemplate.render("你好 {name}，共 {count} 位", 5, 0)
        )
    }

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("我在盯直播", NotificationTemplate.render("我在盯直播", 3, 1))
    }

    @Test
    fun `blank template falls back to defaults`() {
        val normalized = ForegroundNotificationTemplate(title = "   ", text = "").normalized()
        assertEquals(NotificationTemplate.DEFAULT_TITLE, normalized.title)
        assertEquals(NotificationTemplate.DEFAULT_TEXT, normalized.text)
    }

    @Test
    fun `sanitize strips newlines and trims`() {
        assertEquals("第一行 第二行", NotificationTemplate.sanitizeText("  第一行\n第二行\r "))
    }

    @Test
    fun `sanitize enforces length limits`() {
        val long = "警".repeat(200)
        assertTrue(NotificationTemplate.sanitizeTitle(long).length <= NotificationTemplate.MAX_TITLE_LENGTH)
        assertTrue(NotificationTemplate.sanitizeText(long).length <= NotificationTemplate.MAX_TEXT_LENGTH)
    }

    // ---- 合并通知的正文（"合并所有主播"要能看出合并了谁）----

    @Test
    fun `joinStreamerNames 在长度允许时列出全部主播`() {
        val names = listOf("露蒂丝", "少年Pi", "白神遥Haruka")
        assertEquals("露蒂丝、少年Pi、白神遥Haruka", NotificationTemplate.joinStreamerNames(names))
    }

    @Test
    fun `joinStreamerNames 放不下时用等收尾且不超上限`() {
        // 20 位 10 字名字，正文上限 120 字符 → 必须截断并用「等」收尾
        val names = (1..20).map { "主播名字%02d号".format(it) }
        val text = NotificationTemplate.joinStreamerNames(names)
        assertTrue("不得超出正文上限", text.length <= NotificationTemplate.MAX_TEXT_LENGTH)
        assertTrue("截断时必须用「等」收尾，不能让用户以为只有这几位", text.endsWith(" 等"))
        assertTrue("至少要列出前几位", text.startsWith(names.first()))
    }

    @Test
    fun `joinStreamerNames 单个超长名字也不能变成空串`() {
        val text = NotificationTemplate.joinStreamerNames(listOf("名".repeat(300)))
        assertTrue("至少要显示这个名字（截断到上限），不能返回空串", text.isNotEmpty())
        assertTrue(text.length <= NotificationTemplate.MAX_TEXT_LENGTH)
    }

    @Test
    fun `joinStreamerNames 空列表返回空串`() {
        assertEquals("", NotificationTemplate.joinStreamerNames(emptyList()))
    }

    @Test
    fun `joinStreamerNames 不会因为数量多就丢掉等字以外的信息`() {
        // 恰好放得下时不加「等」（否则会让人以为还有更多）
        val two = listOf("甲", "乙")
        assertEquals("甲、乙", NotificationTemplate.joinStreamerNames(two))
    }
}
