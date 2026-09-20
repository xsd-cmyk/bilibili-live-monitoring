package com.example.bilimonitor.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通知合并阈值的**接线契约**：判定规则本身有 [com.example.bilimonitor.domain.policy.NotificationAggregationPolicyTest]，
 * 这里只保证"两个调用点真的按那条规则走"，并且没人把旧的"达到阈值就提前定案"写回来。
 *
 * ## 为什么用读源码的方式
 * 聚合链路（窗口绑定 / 冻结 / 批次 Outbox）的语义只能靠真库 + 事务验证（本仓库那部分在 instrumented
 * 测试里），纯 JVM 单测没有 SQLite 实现。而这次的缺陷**恰好是流程位置**问题：
 * 规则对、但调用点在窗口**中途**就冻结了窗口，于是 10 位主播被切成 3 条通知。
 * 这种情况单测规则本身是发现不了的 —— 所以退一步，把"调用点长什么样"钉死。
 *
 * ★ 它证明不了聚合行为正确（别把绿色当成"聚合没问题"）：行为仍要在真机上验证。
 */
class NotificationAggregationWiringContractTest {

    private val writer: String by lazy { readSource("data/repository/NotificationOutboxWriter.kt") }
    private val repository: String by lazy { readSource("data/repository/NotificationRepository.kt") }
    private val poster: String by lazy { readSource("notify/NotificationPoster.kt") }
    private val screen: String by lazy { readSource("ui/settings/SettingsScreen.kt") }

    @Test
    fun `窗口绑定阶段不得提前冻结窗口`() {
        val body = functionBody(writer, "private suspend fun bindOrCreateAggregate(")
        assertFalse(
            "绑定阶段一旦把窗口冻成 FROZEN，这一批就被定案了 —— " +
                "窗口内后来开播的主播会另开新窗口，用户收到多条通知（这正是被修掉的旧行为）",
            body.contains("NotificationAggregateStatus.FROZEN")
        )
        assertFalse(
            "绑定阶段也不该创建批次 Outbox（批次只能在窗口关闭时定案）",
            body.contains("createBatchOutbox")
        )
        assertTrue(
            "绑定阶段只更新仍在收集中的窗口的计数",
            body.contains("NotificationAggregateStatus.COLLECTING")
        )
    }

    @Test
    fun `窗口关闭时必须用策略判定_且是严格大于`() {
        val body = functionBody(repository, "suspend fun processWindowEnds(")
        assertTrue(
            "判定必须走 NotificationAggregationPolicy.shouldMergeAll（规则可单测）",
            body.contains("NotificationAggregationPolicy.shouldMergeAll(")
        )
        assertFalse(
            "不得回退成 `bindings.size >= fresh.threshold`：那会在正好等于阈值时也合并",
            body.contains("bindings.size >= fresh.threshold")
        )
        assertTrue(
            "超过阈值时走冻结 + 批次 Outbox（一条通知含本窗口全部主播）",
            body.contains("NotificationAggregateStatus.FROZEN") &&
                body.contains("createBatchOutbox")
        )
    }

    @Test
    fun `门控必须接受阈值为 1`() {
        val body = functionBody(writer, "suspend fun createForEvent(")
        assertTrue(
            "聚合门控必须用 usesAggregationWindow（它接受阈值 1）",
            body.contains("NotificationAggregationPolicy.usesAggregationWindow(")
        )
        assertFalse(
            "不得回退成 aggregationThreshold > 1：那会把阈值 1 静默当成「完全不聚合」",
            body.contains("aggregationThreshold > 1")
        )
    }

    @Test
    fun `合并通知要列出这一批的主播名_而不是硬取前五位`() {
        val body = functionBody(poster, "private fun buildBatch(")
        assertTrue(
            "正文必须用 joinStreamerNames（按显示上限尽量多列）",
            body.contains("joinStreamerNames(")
        )
        assertFalse("不得回退成 names.take(5)", body.contains("names.take(5)"))
    }

    @Test
    fun `设置页文案必须说清阈值是触发条件`() {
        assertTrue(
            "开关说明要写明「超过阈值 → 把这一批全部合并」",
            screen.contains("「超过」下面的阈值时") && screen.contains("「全部」合并成一条通知")
        )
        assertTrue(
            "步进器文案要写成「超过 N 位主播需要通知时合并」",
            screen.contains("位主播需要通知时合并")
        )
        assertFalse(
            "不得回退成「合并阈值：N 个开播事件」这种把阈值当成批大小的说法",
            screen.contains("合并阈值：")
        )
        assertFalse(
            // 只查**界面文案**里是否残留 Markdown 星号（Compose 的 Text 不解析 Markdown，
            // 会把星号原样画出来 —— 这条是用户截图实证后补的）。
            // 不能对整个文件查 `**`：源码注释/KDoc 里的加粗写法是合法的。
            "UI 文案里不得用 Markdown 星号强调（会被原样显示）",
            screen.contains("主播**超过**") || screen.contains("这一批**全部**")
        )
    }

    // ---- 源码定位与切片工具（与 MonitorDaoSqlContractTest / NotificationLeaseSqlContractTest 同一套做法）----

    /** 取某个函数体文本：从签名到下一个同缩进的 `}` 之前（够用，且不引入语法解析）。 */
    private fun functionBody(source: String, signature: String): String {
        val index = source.indexOf(signature)
        assertTrue("源码里找不到：$signature", index >= 0)
        val end = source.indexOf("\n    }", index)
        assertTrue("找不到函数结尾：$signature", end > index)
        return source.substring(index, end)
    }

    private fun readSource(relativeUnderJava: String): String {
        val relative = "app/src/main/java/com/example/bilimonitor/$relativeUnderJava"
        val starts = listOf(File("").absoluteFile) + codeSourceDirs()
        for (start in starts) {
            var dir: File? = start
            repeat(6) {
                val current = dir ?: return@repeat
                for (candidate in listOf(File(current, relative), File(current, "app/$relative"))) {
                    if (candidate.isFile) return candidate.readText()
                }
                dir = current.parentFile
            }
        }
        throw AssertionError(
            "找不到 $relative（工作目录 = ${File("").absolutePath}）；" +
                "本测试必须读到真实源文件，不能静默跳过"
        )
    }

    private fun codeSourceDirs(): List<File> = runCatching {
        val location = NotificationAggregationWiringContractTest::class.java
            .protectionDomain?.codeSource?.location ?: return emptyList()
        listOf(File(location.toURI())).filter { it.isDirectory }
    }.getOrDefault(emptyList())
}
