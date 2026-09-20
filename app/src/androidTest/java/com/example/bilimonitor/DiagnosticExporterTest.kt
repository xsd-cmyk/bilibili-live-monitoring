package com.example.bilimonitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.NetworkTimeSource
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.db.AppDatabaseFactory
import com.example.bilimonitor.data.repository.DiagnosticExporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 诊断包导出 instrumented 测试（原规范 219）。
 *
 * 为什么必须是 instrumented：诊断包依赖真实 Room 库、MediaStore 与包管理器，
 * JVM 单测无法覆盖。本测试在**独立的测试数据库**上运行，不触碰应用正式数据。
 *
 * 除功能验证外，重点断言规格的硬性边界：**包内不得出现任何凭证/账号信息**。
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticExporterTest {

    private lateinit var db: AppDatabase
    private lateinit var exporter: DiagnosticExporter
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val dbName = "diagnostic_test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        db = AppDatabaseFactory.createForTest(context, dbName)
        val clock = object : AppClock {
            override fun nowWall(): Long = System.currentTimeMillis()
            override fun nowElapsed(): Long = android.os.SystemClock.elapsedRealtime()
            override fun bootId(): String = "test-boot"
            override fun currentTimeZone(): String = "Asia/Shanghai"
        }
        exporter = DiagnosticExporter(
            context,
            db,
            clock,
            // 新增的三个依赖：只读探测提权/电源环境（不会触发任何授权弹窗）
            com.example.bilimonitor.data.repository.AdvancedKeepAliveRepository(
                context,
                com.example.bilimonitor.data.privilege.RootShell(),
                com.example.bilimonitor.data.privilege.ShizukuShell(context),
                db.logDao(),
                clock
            ),
            com.example.bilimonitor.data.privilege.RootShell(),
            com.example.bilimonitor.data.privilege.ShizukuShell(context),
            // 心跳排程状态（诊断包会如实报"心跳有没有排上"）
            com.example.bilimonitor.background.KeepAliveHeartbeat(
                context,
                clock,
                db.logDao(),
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun 诊断包包含规格要求的全部文件() = runBlocking {
        val result = exporter.export().getOrThrow()

        assertTrue("文件名应带日期戳", result.fileName.startsWith("BiliMonitor_Diagnostics_"))
        assertTrue("应为 zip", result.fileName.endsWith(".zip"))
        // 文件名必须精确到秒：原实现只到"日"，同一天导出第二次会被 MediaStore 落成
        // `... (1).zip`，而提示里报的是不带 (1) 的名字。导出返回的必须是磁盘上的真实名字。
        assertTrue(
            "文件名应精确到秒：${result.fileName}",
            Regex("""BiliMonitor_Diagnostics_\d{8}_\d{6}.*\.zip""").matches(result.fileName)
        )
        assertTrue("应为 zip", result.fileName.endsWith(".zip"))

        val entries = readEntries(expectedName = result.fileName)
        val names = entries.keys
        // 分片数不再手抄数字（此前写死 6，加了 notification_pipeline.json 后必红灯）：
        // 先按"规格要求的 7 个分片一个都不能少"校验，再要求回报的 entryCount 与包内
        // **实际文件数**一致。为什么不直接断言 == 7：每加一个分片都要回来改数字，
        // 而"新增分片"是加法、不该判失败；必需清单 + 一致性校验更能挡住真实回归。
        val requiredShards = listOf(
            "app_info.json",
            "monitoring_logs.json",
            "application_errors.json",
            "health_snapshot.json",
            "settings_sanitized.json",
            "migration_info.json",
            // 通知流水线快照：漏了它就等于回到"没收到通知查不出原因"的状态。
            "notification_pipeline.json"
        )
        requiredShards.forEach { assertTrue("缺少 $it", names.contains(it)) }
        assertTrue(
            "回报的分片数 ${result.entryCount} 不应少于必需分片数 ${requiredShards.size}",
            result.entryCount >= requiredShards.size
        )
        assertEquals("回报的分片数应与包内实际文件数一致", entries.size, result.entryCount)
        assertTrue("包体不应为空", result.bytes > 0)
    }

    @Test
    fun 各分片是合法且结构正确的JSON() = runBlocking {
        val result = exporter.export().getOrThrow()
        val entries = readEntries(expectedName = result.fileName)

        // 用真实 JSON 解析器校验合法性（而非字符串包含）
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        entries.forEach { (name, content) ->
            runCatching { json.parseToJsonElement(content) }
                .onFailure { fail("$name 不是合法 JSON：${it.message}") }
        }

        val appInfo = json.parseToJsonElement(entries.getValue("app_info.json"))
            .jsonObject
        // 应用名与 R.string.app_name 同源比较：以前这里手抄"主播监控"，
        // 应用改名为 Kaoru 后测试反而在给旧名字背书（诊断包里的 appName 也跟着撒谎）。
        assertEquals(
            "appName 应与应用资源名一致",
            context.getString(R.string.app_name),
            appInfo.getValue("appName").jsonPrimitive.content
        )
        assertEquals(context.packageName, appInfo.getValue("packageName").jsonPrimitive.content)

        // 脱敏配置必须覆盖 monitoring_config 的诊断相关列：此前漏了
        // noGuaranteeAcknowledged / noGuaranteeAcknowledgedAt / placeholderText，
        // 用户报"我确认过免责声明""界面上的占位文案不对"时诊断包里查无实据。
        val settings = json.parseToJsonElement(entries.getValue("settings_sanitized.json")).jsonObject
        listOf("noGuaranteeAcknowledged", "noGuaranteeAcknowledgedAt", "placeholderText").forEach {
            assertTrue("settings_sanitized 缺少 ${it}", settings.containsKey(it))
        }
        val migration = json.parseToJsonElement(entries.getValue("migration_info.json"))
            .jsonObject
        // 版本号引用 AppDatabase.DB_VERSION（Room schema 版本的唯一来源），不再手抄数字：
        // 以前这里写死 6，库升到 10 之后这两条断言必然红灯（原注释写着"升版时一并更新"——
        // 靠人记住的同步约定就是脱节的来源）。
        assertEquals(
            "编译版本应与 AppDatabase 声明一致",
            AppDatabase.DB_VERSION,
            migration.getValue("compiledDatabaseVersion").jsonPrimitive.int
        )
        assertEquals(
            "新建测试库的 user_version 应为 AppDatabase.DB_VERSION",
            AppDatabase.DB_VERSION,
            migration.getValue("currentDatabaseVersion").jsonPrimitive.int
        )
        assertFalse(
            "未开启破坏性迁移，诊断包必须如实反映",
            migration.getValue("destructiveMigrationAllowed").jsonPrimitive.boolean
        )
    }

    /** 规格硬性边界：严禁包含登录凭证。 */
    @Test
    fun 诊断包不包含任何凭证或账号信息() = runBlocking {
        val result = exporter.export().getOrThrow()
        val all = readEntries(expectedName = result.fileName).values.joinToString("\n")

        val forbidden = listOf(
            "SESSDATA", "bili_jct", "DedeUserID", "buvid", "cookie", "Cookie",
            "token", "Token", "password", "credential", "bilibili.com/x/passport"
        )
        forbidden.forEach {
            assertFalse("诊断包不得包含凭证相关字符串：$it", all.contains(it))
        }
    }

    /** 规格要求：默认不导出完整主播历史。 */
    @Test
    fun 诊断包不包含主播历史明细() = runBlocking {
        val result = exporter.export().getOrThrow()
        val entries = readEntries(expectedName = result.fileName)
        val settings = entries.getValue("settings_sanitized.json")
        val health = entries.getValue("health_snapshot.json")

        // 只允许出现主播数量这类聚合值
        assertTrue(health.contains("monitoredStreamerCount"))
        assertFalse("不应导出主播昵称列表", settings.contains("\"streamers\""))
        assertFalse("不应导出场次明细", health.contains("liveSession"))
    }

    /** 无参数版本：回退取最近一个诊断包。 */
    private fun readEntries(): Map<String, String> = readEntries(expectedName = null)

    // ---- 工具 ----

    private fun readEntries(expectedName: String?): Map<String, String> {
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            android.provider.MediaStore.Downloads._ID,
            android.provider.MediaStore.Downloads.DISPLAY_NAME
        )

        // 先按导出返回的文件名精确查，查不到再退化为按前缀模糊查（MediaStore 索引可能延迟）。
        val candidates = buildList {
            if (expectedName != null) add(
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?" to arrayOf(expectedName)
            )
            add("${android.provider.MediaStore.Downloads.DISPLAY_NAME} LIKE ?" to arrayOf("BiliMonitor_Diagnostics_%"))
        }

        val seen = StringBuilder()
        for ((selection, args) in candidates) {
            resolver.query(collection, projection, selection, args, "date_added DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1)
                    seen.append(name).append("; ")
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    resolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        val out = linkedMapOf<String, String>()
                        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                        // 清理测试产物，不留在用户 Download 目录
                        runCatching { resolver.delete(uri, null, null) }
                        return out
                    }
                }
            }
        }
        throw AssertionError(
            "未在 Download 中找到诊断包；期望文件名=$expectedName；MediaStore 实际可见=${seen.ifEmpty { "(无)" }}"
        )
    }
}
