package com.example.bilimonitor.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DAO 的 SQL 契约（缺陷 1 的读侧过滤、缺陷 3 的恒真条件）。
 *
 * ## 为什么是"读源码"的测试
 * 这两处修复的**本体就是 SQL**（`WHERE` 里少一个恒真条件、多一个 `monitoringEnabled = 1`，
 * 以及"代际过期的计数行在出口处不可见"），而 SQL 的语义只能在真数据库上执行验证：
 * 本仓库的 DAO/事务/唯一索引类验证全部放在 instrumented 测试（需要设备或模拟器），
 * 纯 JVM 单测连一个 SQLite 实现都没有（`sqlite-jdbc` 不是本模块的测试依赖）。
 * 于是这里退一步，把"SQL 里的条件是否还在"钉死 —— 它照样能抓住"修复被改回去"：
 * 删掉 `monitoringEnabled = 1`、把 `streamerMonitorGeneration = :…` 这类恒真条件加回来、
 * 把 `REPLACE` 换回 `IGNORE`、去掉读侧的代际过滤，本文件的断言**立刻失败**（负向对照）。
 * SQL 的实际执行语义另有一份离线验证（真实 SQLite 上跑同一段 SQL）记录在交付说明里。
 *
 * ★ **它证明不了 SQLite 语义**（别把这里的绿色当成"SQL 正确"）：把
 *   `lastObservationSequence < :sequence` 改成 `<=`，或把 `EXISTS` 改成 `NOT EXISTS`，
 *   字符一个没少，本文件**照样全绿**。这类语义反转只能靠真库执行/instrumented 测试抓。
 *   另一面：断言依赖签名文本（形参名、`): Int`），改返回类型或形参名会**误报** ——
 *   那时先确认签名变更本身是否合理，再同步这里的期望值。
 *
 * ## 定位方式
 * 单测的工作目录由 Gradle 决定（AGP 默认是模块目录），因此这里**向上逐级查找**，
 * 兼容"工作目录 = app/"、"工作目录 = 仓库根"两种情形；找不到就直接失败（不静默跳过），
 * 否则这个契约就形同虚设。
 */
class MonitorDaoSqlContractTest {

    private val source: String by lazy { readDaoSource() }

    @Test
    fun `streamer CAS 必须检查暂停、且不得再有恒真的主播代际条件`() {
        val sql = querySqlOf("suspend fun casConfirmedObservation(")
        val params = parameterListOf("suspend fun casConfirmedObservation(")

        assertTrue(
            "必须带 monitoringEnabled = 1：暂停之后在途的观察不得再提交转换（缺陷 3 的修复点）",
            sql.contains("monitoringEnabled = 1")
        )
        assertFalse(
            "恒真的 streamerMonitorGeneration 条件必须删掉（期望值取自同一事务内刚读到的行）",
            sql.contains("streamerMonitorGeneration")
        )
        assertFalse(
            "恒真的 streamerMonitorGeneration 形参也必须删掉，否则会被再次用进 WHERE",
            params.contains("streamerMonitorGeneration")
        )
        assertTrue(
            "单调观察序号必须保留（真正的保护之一）",
            sql.contains("observationSequence < :observationSequence")
        )
        assertTrue("租约 fencing token 必须保留（真正的保护之二）", sql.contains("fencingToken = :fencingToken"))
        assertTrue(
            "全局监控代际必须保留（真正的保护之二）",
            sql.contains("monitorGeneration = :monitorGeneration")
        )
        assertTrue("维护模式必须保留（导出/恢复期间禁止写状态机）", sql.contains("maintenanceMode = 'OFF'"))
    }

    @Test
    fun `pending CAS 必须保留单调序号与暂停检查、且不得再有恒真代际条件`() {
        val sql = querySqlOf("suspend fun updatePendingTransitionCas(")
        val params = parameterListOf("suspend fun updatePendingTransitionCas(")

        assertTrue(
            "单调观察序号必须保留（过期观察不得推进计数）",
            sql.contains("lastObservationSequence < :sequence")
        )
        assertTrue(
            "已暂停的主播不得再推进任何计数（让本语句自包含，不依赖调用方先 CAS 过 streamer）",
            sql.contains("monitoringEnabled = 1")
        )
        assertFalse("恒真的 monitorGeneration 条件必须删掉", sql.contains("expectedMonitorGeneration"))
        assertFalse("恒真的 streamerMonitorGeneration 条件必须删掉", sql.contains("expectedStreamerGeneration"))
        assertFalse("对应的形参也必须删掉", params.contains("expectedMonitorGeneration"))
        assertFalse("对应的形参也必须删掉", params.contains("expectedStreamerGeneration"))
    }

    @Test
    fun `代际重置必须用 REPLACE 而不是 IGNORE`() {
        val reset = annotationOf("suspend fun resetPendingTransition(row", "@Insert")
        assertTrue(
            "代际变化必须**整行重建**（REPLACE）；IGNORE 对已存在的行是 no-op，正是缺陷 1 的根因",
            reset.contains("OnConflictStrategy.REPLACE")
        )
        val ensure = annotationOf("suspend fun ensurePendingTransition(row", "@Insert")
        assertTrue(
            "只有「行不存在才新建」那条路径才该用 IGNORE（并发时先到的那一行说了算）",
            ensure.contains("OnConflictStrategy.IGNORE")
        )
    }

    @Test
    fun `读侧的 pending 行必须按代际过滤`() {
        val sql = querySqlOf("suspend fun getPendingTransition(streamerId")
        assertTrue(
            "必须与系统运行锁的 monitorGeneration 比对（否则跨进程存活的旧计数会被当成连续计数）",
            sql.contains("system_runtime_lock")
        )
        assertTrue("必须读 pending 行自己的全局代际", sql.contains("p.monitorGeneration"))
        assertTrue("必须与主播行的主播级代际比对", sql.contains("s.streamerMonitorGeneration"))
    }

    @Test
    fun `写入路径必须有不过滤的读法`() {
        // 代际过期的那一行**确实还在库里**：写入路径若只看过滤后的读法，会误判成"没有行"，
        // 走 IGNORE 新建 ⇒ 旧计数永远不会被重置（与被修复的缺陷一模一样）。
        val sql = querySqlOf("suspend fun getPendingTransitionAnyGeneration(streamerId")
        assertFalse(sql.contains("system_runtime_lock"))
        assertFalse(sql.contains("JOIN streamer"))
    }

    // ---- 源码定位工具 ----

    private fun readDaoSource(): String {
        val relative = "src/main/java/com/example/bilimonitor/data/local/dao/MonitorDaos.kt"
        // 先从工作目录往上找（Gradle 单测的工作目录是模块目录 app/，也可能被配成仓库根），
        // 再退回到"本测试类自己的 class 文件所在目录"往上找（AGP 把单测 class 放在
        // app/build/tmp/kotlin-classes/… 这样的目录里，往上走几级同样是 app/）。
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
            "找不到 MonitorDaos.kt（工作目录 = ${File("").absolutePath}；" +
                "起始查找点 = ${starts.joinToString { it.absolutePath }}）；" +
                "本测试必须读到真实源文件，不能静默跳过"
        )
    }

    /** 本测试类 class 文件所在目录（仅当它以目录形式出现在 classpath 上时才可用）。 */
    private fun codeSourceDirs(): List<File> = runCatching {
        val location = MonitorDaoSqlContractTest::class.java.protectionDomain?.codeSource?.location
            ?: return emptyList()
        listOf(File(location.toURI())).filter { it.isDirectory }
    }.getOrDefault(emptyList())

    /** 取出某个方法签名**之前最近**的那个 `@Query` 块（三引号与单行两种写法都支持）。 */
    private fun querySqlOf(signature: String): String {
        val index = source.indexOf(signature)
        assertTrue("MonitorDaos.kt 里找不到方法：$signature", index >= 0)
        val annotation = source.lastIndexOf("@Query", index)
        assertTrue("@Query 注解缺失（方法签名被改过？）：$signature", annotation in 0 until index)
        val parenOpen = source.indexOf('(', annotation)
        val tripleOpen = source.indexOf("\"\"\"", annotation)
        if (tripleOpen in annotation until index &&
            parenOpen in annotation until tripleOpen &&
            source.substring(parenOpen + 1, tripleOpen).isBlank()
        ) {
            val close = source.indexOf("\"\"\"", tripleOpen + 3)
            assertTrue("三引号 @Query 没有闭合（方法签名被改过？）：$signature", close in (tripleOpen + 3) until index)
            return source.substring(tripleOpen + 3, close)
        }
        // 单行写法：@Query("SELECT …")
        val singleOpen = source.indexOf('"', parenOpen)
        val singleClose = source.indexOf('"', singleOpen + 1)
        assertTrue(
            "@Query 既不是三引号块、也不是单行字符串：$signature",
            singleOpen in parenOpen until index && singleClose in (singleOpen + 1) until index
        )
        return source.substring(singleOpen + 1, singleClose)
    }

    /** 取出某个方法签名**之前最近**的那个单行注解文本（例如 `@Insert(onConflict = …)`）。 */
    private fun annotationOf(signature: String, annotationName: String): String {
        val index = source.indexOf(signature)
        assertTrue("MonitorDaos.kt 里找不到方法：$signature", index >= 0)
        val start = source.lastIndexOf(annotationName, index)
        assertTrue("$annotationName 注解缺失：$signature", start in 0 until index)
        val end = source.indexOf('\n', start)
        return source.substring(start, if (end > start) end else source.length)
    }

    /** 取出方法形参列表文本（用于断言"某个形参是否已经删掉"）。 */
    private fun parameterListOf(signature: String): String {
        val index = source.indexOf(signature)
        assertTrue("MonitorDaos.kt 里找不到方法：$signature", index >= 0)
        val end = source.indexOf("): Int", index)
        assertTrue("方法返回值不再是 Int（测试需要同步更新）：$signature", end > index)
        return source.substring(index, end)
    }
}
