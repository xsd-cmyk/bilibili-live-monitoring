package com.example.bilimonitor.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.db.AppMigrations
import com.example.bilimonitor.data.local.entity.MonitoringConfigEntity
import com.example.bilimonitor.data.local.entity.MonitoringConfigRevisionEntity
import com.example.bilimonitor.domain.model.MonitoringConfigSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * v10「熔断开关」+「熔断后重试间隔开放」的配置链路单测（纯 JVM，无 Android/Room 运行时）。
 *
 * 覆盖四件事（都是"改错了就会静默出错"的地方）：
 *  1. `merge()` 对新字段 `circuitBreakerEnabled` 的**回写**与 `null` → 保持原值的**回落**；
 *  2. 既有字段 `circuitBreakerRecoverySeconds` 的**校验范围**（这次只是把它开放到设置页，
 *     范围不动：1–3600，与设置页 `NumberInputSetting` 的 validRange 必须同步）；
 *  3. `MonitoringConfigSnapshot.circuitBreakerEnabled` 的默认值必须是 `true`；
 *  4. **旧备份文件（缺 `circuitBreakerEnabled` 键）必须能正常反序列化并落到默认 `true`** ——
 *     没有默认值的话用户会看到"备份文件损坏"这种完全误导的结论（本项目已踩过两次）。
 *  5. 迁移 `V9_10` 的 DDL、`AppMigrations.ALL` 注册与 `DB_VERSION`。
 *
 * 说明：`ConfigRepository.merge` 是 **private**，这里用 `Unsafe.allocateInstance`
 * 绕过构造（它的构造参数全是 Android/Room 依赖，本来就无法在纯 JVM 里装配），
 * 再用反射调用 `merge` —— 测的是**真实实现**，不是复制一份规则出来测。
 */
class CircuitBreakerConfigTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---------------- 1/2. merge() 回写、回落与校验范围 ----------------

    private val repo: ConfigRepository = allocateInstance(ConfigRepository::class.java)

    private val mergeMethod = ConfigRepository::class.java
        .getDeclaredMethod(
            "merge",
            MonitoringConfigEntity::class.java,
            com.example.bilimonitor.data.repository.ConfigUpdate::class.java
        )
        .apply { isAccessible = true }

    private fun merge(config: MonitoringConfigEntity, update: ConfigUpdate): MonitoringConfigEntity? =
        mergeMethod.invoke(repo, config, update) as MonitoringConfigEntity?

    private fun config(
        circuitBreakerEnabled: Boolean = true,
        circuitBreakerRecoverySeconds: Int = 60
    ) = MonitoringConfigEntity(
        singletonId = 1,
        configVersion = 7L,
        monitoringEnabled = true,
        mode = MonitoringMode.POWER_SAVING,
        intervalSeconds = 60,
        batchSize = 50,
        maxConcurrency = 4,
        timeoutSeconds = 8,
        maxRetries = 2,
        retryBaseSeconds = 5,
        maxRetryDelaySeconds = 60,
        circuitBreakerThreshold = 3,
        circuitBreakerRecoverySeconds = circuitBreakerRecoverySeconds,
        aggregationEnabled = true,
        aggregationThreshold = 4,
        aggregationWindowSeconds = 5,
        batchCooldownSeconds = 5,
        freshnessStaleSeconds = 300,
        backgroundKeepAliveChoice = BackgroundKeepAliveChoice.UNSET,
        noGuaranteeAcknowledged = false,
        noGuaranteeAcknowledgedAt = null,
        startConfirmationCount = 1,
        endConfirmationCount = 1,
        placeholderText = "[待获取]",
        circuitBreakerEnabled = circuitBreakerEnabled,
        updatedAt = 0L
    )

    @Test
    fun `merge 把 circuitBreakerEnabled 回写到结果里（false → true 能被打开）`() {
        val merged = merge(config(circuitBreakerEnabled = false), ConfigUpdate(circuitBreakerEnabled = true))
        assertNotNull("合法更新不应被拒", merged)
        assertTrue("显式传 true 必须回写", merged!!.circuitBreakerEnabled)
    }

    @Test
    fun `merge 把 circuitBreakerEnabled 回写到结果里（true → false 能被关闭）`() {
        val merged = merge(config(circuitBreakerEnabled = true), ConfigUpdate(circuitBreakerEnabled = false))
        assertNotNull(merged)
        assertFalse("显式传 false 必须回写", merged!!.circuitBreakerEnabled)
    }

    @Test
    fun `merge 对 null 回落为库里的现值（不传就不改）`() {
        val off = merge(config(circuitBreakerEnabled = false), ConfigUpdate(intervalSeconds = 120))
        assertNotNull(off)
        assertFalse("库里是关，没传该字段时必须保持关", off!!.circuitBreakerEnabled)

        val on = merge(config(circuitBreakerEnabled = true), ConfigUpdate(intervalSeconds = 120))
        assertNotNull(on)
        assertTrue("库里是开，没传该字段时必须保持开", on!!.circuitBreakerEnabled)
    }

    @Test
    fun `merge 不会因为新字段而改动其它字段`() {
        val before = config(circuitBreakerEnabled = false)
        val after = merge(before, ConfigUpdate(circuitBreakerEnabled = true))
        assertNotNull(after)
        assertEquals(before.copy(circuitBreakerEnabled = true), after)
    }

    @Test
    fun `merge 保持 circuitBreakerRecoverySeconds 的既有校验范围 1-3600`() {
        assertEquals(1, merge(config(), ConfigUpdate(circuitBreakerRecoverySeconds = 1))!!.circuitBreakerRecoverySeconds)
        assertEquals(3600, merge(config(), ConfigUpdate(circuitBreakerRecoverySeconds = 3600))!!.circuitBreakerRecoverySeconds)
        assertEquals(300, merge(config(), ConfigUpdate(circuitBreakerRecoverySeconds = 300))!!.circuitBreakerRecoverySeconds)
        assertNull("0 超出下界必须被拒（否则熔断后立刻重试＝熔断失效）", merge(config(), ConfigUpdate(circuitBreakerRecoverySeconds = 0)))
        assertNull("3601 超出上界必须被拒", merge(config(), ConfigUpdate(circuitBreakerRecoverySeconds = 3601)))
    }

    @Test
    fun `merge 对 circuitBreakerRecoverySeconds 的 null 同样回落为现值`() {
        val merged = merge(config(circuitBreakerRecoverySeconds = 60), ConfigUpdate(batchSize = 20))
        assertNotNull(merged)
        assertEquals("没传就必须保持 60（种子值）", 60, merged!!.circuitBreakerRecoverySeconds)
    }

    // ---------------- 3. 快照默认值 ----------------

    private fun snapshot(circuitBreakerEnabled: Boolean? = null): MonitoringConfigSnapshot {
        val base = MonitoringConfigSnapshot(
            configVersion = 1L,
            monitoringEnabled = true,
            mode = MonitoringMode.POWER_SAVING,
            intervalSeconds = 60,
            batchSize = 50,
            maxConcurrency = 4,
            timeoutSeconds = 8,
            maxRetries = 2,
            retryBaseSeconds = 5,
            maxRetryDelaySeconds = 60,
            circuitBreakerThreshold = 3,
            circuitBreakerRecoverySeconds = 60,
            aggregationEnabled = true,
            aggregationThreshold = 4,
            aggregationWindowSeconds = 5,
            batchCooldownSeconds = 5,
            backgroundKeepAliveChoice = BackgroundKeepAliveChoice.UNSET,
            noGuaranteeAcknowledged = false,
            startConfirmationCount = 1,
            endConfirmationCount = 1,
            placeholderText = "[待获取]"
        )
        return if (circuitBreakerEnabled == null) base else base.copy(circuitBreakerEnabled = circuitBreakerEnabled)
    }

    @Test
    fun `MonitoringConfigSnapshot 的 circuitBreakerEnabled 默认是 true`() {
        assertTrue("不传该参数时必须是 true（保持引入开关之前的既有行为）", snapshot().circuitBreakerEnabled)
    }

    @Test
    fun `MonitoringConfigSnapshot 能携带 false（引擎据此不做熔断暂停）`() {
        assertFalse(snapshot(circuitBreakerEnabled = false).circuitBreakerEnabled)
    }

    // ---------------- 4. 备份 DTO：旧文件缺键 → 默认 true ----------------

    private fun monitoringSettings(circuitBreakerEnabled: Boolean = true) = BackupMonitoringSettings(
        monitoringEnabled = true,
        mode = "POWER_SAVING",
        intervalSeconds = 60,
        batchSize = 50,
        maxConcurrency = 4,
        timeoutSeconds = 8,
        maxRetries = 2,
        retryBaseSeconds = 5,
        maxRetryDelaySeconds = 60,
        circuitBreakerThreshold = 3,
        circuitBreakerRecoverySeconds = 60,
        aggregationEnabled = true,
        aggregationThreshold = 4,
        aggregationWindowSeconds = 5,
        batchCooldownSeconds = 5,
        freshnessStaleSeconds = 300,
        startConfirmationCount = 1,
        endConfirmationCount = 1,
        placeholderText = "[待获取]",
        circuitBreakerEnabled = circuitBreakerEnabled
    )

    private fun backupFile(circuitBreakerEnabled: Boolean = true) = BackupFile(
        manifest = BackupManifest(
            backupId = "b1",
            backupSchemaVersion = 1,
            formatVersion = 1,
            roomSchemaVersion = 10,
            createdAt = 0L,
            sourceDataVersion = 0L,
            scopeType = "ALL_DATA",
            selectedStreamerStableIds = emptyList(),
            recordCounts = emptyMap(),
            checksum = "x",
            appVersionName = "1.1"
        ),
        body = BackupBody(
            streamers = emptyList(), tags = emptyList(), groups = emptyList(),
            tagRefs = emptyList(), groupRefs = emptyList(), sessions = emptyList(),
            events = emptyList(), titles = emptyList(), corrections = emptyList(),
            policies = emptyList()
        ),
        settings = BackupSettings(monitoring = monitoringSettings(circuitBreakerEnabled))
    )

    /** 把当前版本写出的文件里 `circuitBreakerEnabled` 这个键删掉，等价于"旧版本写的备份"。 */
    private fun withoutKey(enabled: Boolean): String {
        val current = json.encodeToString(BackupFile.serializer(), backupFile(enabled))
        assertTrue("前提：当前版本导出时必须写出该键", current.contains("\"circuitBreakerEnabled\":$enabled"))
        val legacy = current
            .replace("\"circuitBreakerEnabled\":$enabled,", "")
            .replace(",\"circuitBreakerEnabled\":$enabled", "")
        assertFalse("前提：旧文件里确实没有这个键", legacy.contains("circuitBreakerEnabled"))
        return legacy
    }

    @Test
    fun `旧备份缺少 circuitBreakerEnabled 键时仍能反序列化并落到默认 true`() {
        val legacy = withoutKey(true)
        val parsed = json.decodeFromString(BackupFile.serializer(), legacy)
        assertTrue(
            "缺键必须落到默认 true，否则会抛序列化异常、被上层当成'备份文件损坏'",
            parsed.settings!!.monitoring!!.circuitBreakerEnabled
        )
    }

    @Test
    fun `旧备份缺键时 body 的校验和依然不变（文件仍可通过完整性校验）`() {
        val current = json.encodeToString(BackupFile.serializer(), backupFile(true))
        val legacy = withoutKey(true)
        assertEquals(
            "settings 不在 body 里，加字段不应影响旧文件的校验和",
            BackupChecksum.ofRawBody(json, current),
            BackupChecksum.ofRawBody(json, legacy)
        )
    }

    @Test
    fun `新备份写出 false 时能读回 false（关掉熔断的状态可以往返）`() {
        val text = json.encodeToString(BackupFile.serializer(), backupFile(circuitBreakerEnabled = false))
        assertTrue("false ≠ 默认值，必须真的写进文件", text.contains("\"circuitBreakerEnabled\":false"))
        val parsed = json.decodeFromString(BackupFile.serializer(), text)
        assertFalse(parsed.settings!!.monitoring!!.circuitBreakerEnabled)
    }

    @Test
    fun `monitoring 段缺 circuitBreakerEnabled 的裸 DTO 也能解析`() {
        val legacyDto = """
            {"monitoringEnabled":true,"mode":"POWER_SAVING","intervalSeconds":60,"batchSize":50,
             "maxConcurrency":4,"timeoutSeconds":8,"maxRetries":2,"retryBaseSeconds":5,
             "maxRetryDelaySeconds":60,"circuitBreakerThreshold":3,"circuitBreakerRecoverySeconds":60,
             "aggregationEnabled":true,"aggregationThreshold":4,"aggregationWindowSeconds":5,
             "batchCooldownSeconds":5,"freshnessStaleSeconds":300,"startConfirmationCount":1,
             "endConfirmationCount":1,"placeholderText":"[待获取]"}
        """.trimIndent()
        val dto = json.decodeFromString(BackupMonitoringSettings.serializer(), legacyDto)
        assertTrue(dto.circuitBreakerEnabled)
        assertEquals("默认值不能把已存在的字段带偏", 60, dto.circuitBreakerRecoverySeconds)
    }

    // ---------------- 5. 迁移与版本 ----------------

    /** 只记录 execSQL 的假库：迁移逻辑本身就是一串 DDL，用动态代理即可完整观察。 */
    private class RecordingDb {
        val statements = mutableListOf<String>()

        fun create(): SupportSQLiteDatabase = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when {
                method.name == "execSQL" -> {
                    statements.add(args!![0] as String)
                    null
                }
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
    }

    @Test
    fun `DB_VERSION 升到 10`() {
        val field = AppDatabase::class.java.getField("DB_VERSION")
        assertEquals("库版本必须与 @Database(version = ...) 同步", 10, field.getInt(null))
    }

    @Test
    fun `ALL 里注册了 9 到 10 的迁移且总数正确`() {
        val pairs = AppMigrations.ALL.map { it.startVersion to it.endVersion }
        assertTrue("必须注册 9→10，否则老库升级时 Room 直接抛 IllegalStateException", pairs.contains(9 to 10))
        assertEquals(
            "迁移链必须首尾相接：1→2→…→9→10",
            listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8, 8 to 9, 9 to 10),
            pairs
        )
    }

    @Test
    fun `V9_10 先摘掉部分索引再给两张表加列（DEFAULT 1 逐字一致）`() {
        val migration = AppMigrations.ALL.first { it.startVersion == 9 && it.endVersion == 10 }
        val recorder = RecordingDb()
        migration.migrate(recorder.create())
        val sql = recorder.statements

        val drops = sql.filter { it.startsWith("DROP INDEX IF EXISTS") }
        val alters = sql.filter { it.startsWith("ALTER TABLE") }
        val firstAlter = sql.indexOfFirst { it.startsWith("ALTER TABLE") }
        val lastDrop = sql.indexOfLast { it.startsWith("DROP INDEX IF EXISTS") }

        assertEquals("必须摘掉全部 16 条部分唯一索引", 16, drops.size)
        assertTrue("第一条语句就必须是 DROP INDEX（dropPartialIndexes 在最前）", sql.first().startsWith("DROP INDEX IF EXISTS"))
        assertTrue(
            "所有 DROP 必须发生在 ALTER 之前；否则迁移后 Room 的全量 schema 校验会因残留部分索引而失败（V8_9 踩过的坑）",
            lastDrop < firstAlter
        )
        assertEquals(
            listOf(
                "ALTER TABLE monitoring_config ADD COLUMN circuitBreakerEnabled INTEGER NOT NULL DEFAULT 1",
                "ALTER TABLE monitoring_config_revision ADD COLUMN circuitBreakerEnabled INTEGER NOT NULL DEFAULT 1"
            ),
            alters
        )
        alters.forEach {
            assertTrue("列类型必须是 INTEGER NOT NULL（Room 对 Boolean 的期望）", it.contains("INTEGER NOT NULL"))
            assertTrue("DEFAULT 必须逐字等于实体上的 @ColumnInfo(defaultValue = \"1\")", it.endsWith("DEFAULT 1"))
        }
    }

    @Test
    fun `两张实体表都带上了这个布尔列（当前表与修订快照口径一致）`() {
        // Room 的 @ColumnInfo 是 CLASS retention，运行时反射看不到注解本身，
        // 因此这里锁"结构"（新增列必须落在 entity 与 revision 两张表上、且是布尔），
        // DEFAULT 值一侧由上面那个测试锁住迁移 DDL，实体一侧由编译产物字节码核对。
        val entityParams = MonitoringConfigEntity::class.java.declaredConstructors.first().parameterTypes
        val revisionParams = MonitoringConfigRevisionEntity::class.java.declaredConstructors.first().parameterTypes

        assertEquals(
            "当前表比修订快照多一个主键 singletonId，参数个数只能差 1",
            entityParams.size,
            revisionParams.size + 1
        )
        assertEquals("当前表最后第二个参数是新增的布尔列（最后一个是 updatedAt）", "boolean", entityParams[entityParams.size - 2].name)
        assertEquals("修订表最后第二个参数是新增的布尔列（最后一个是 createdAt）", "boolean", revisionParams[revisionParams.size - 2].name)

        val instance = config(circuitBreakerEnabled = true)
        assertTrue(instance.circuitBreakerEnabled)
        val copy = instance.copy(circuitBreakerEnabled = false)
        assertFalse(copy.circuitBreakerEnabled)
        assertEquals("copy 的其它字段必须与原来完全一致", instance, copy.copy(circuitBreakerEnabled = true))
        assertEquals("默认值必须是 true（老行升级后 = 既有行为）", true, config().circuitBreakerEnabled)
    }

    @Test
    fun `DbSeed 的两条 INSERT 列数与值数一致且新列默认 1（新装库第一句 SQL）`() {
        // 这条 SQL 不经过编译期校验：列名与值一旦错位，只有**全新安装**的设备第一次建库时
        // 才会以 SQLiteException 暴露出来（而且是在开库回调里）。所以离线把它钉死。
        val recorder = RecordingDb()
        com.example.bilimonitor.data.local.db.DbSeed.ensureSingletonRows(recorder.create(), 1_234L)

        val inserts = recorder.statements.filter { it.contains("INSERT OR IGNORE INTO monitoring_config") }
        assertEquals("monitoring_config 与 monitoring_config_revision 各一条种子", 2, inserts.size)

        inserts.forEach { sql ->
            val columns = Regex("INSERT OR IGNORE INTO \\w+\\s*\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
                .find(sql)!!.groupValues[1]
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val values = Regex("VALUES\\s*\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
                .find(sql)!!.groupValues[1]
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            assertEquals("列数与值数必须一致（错位＝新装库建库即失败）", columns.size, values.size)

            val enabledIndex = columns.indexOf("circuitBreakerEnabled")
            assertTrue("两张表都必须有 circuitBreakerEnabled 列", enabledIndex >= 0)
            assertEquals("新装默认必须是 1（＝打开熔断，保持既有行为）", "1", values[enabledIndex])

            val recoveryIndex = columns.indexOf("circuitBreakerRecoverySeconds")
            assertTrue(recoveryIndex >= 0)
            assertEquals(
                "熔断后重试间隔的种子值**保持 60 不动**（用户定稿：不要改成 300）",
                "60",
                values[recoveryIndex]
            )
        }
    }

    // ---------------- helper ----------------

    private fun <T> allocateInstance(cls: Class<T>): T {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        @Suppress("UNCHECKED_CAST")
        return allocate.invoke(unsafe, cls) as T
    }
}
