package com.example.bilimonitor.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通知投递**租约判定**的 SQL 契约（2026 复查修复项）。
 *
 * ## 为什么要这份测试
 * 回收查询原先只看墙钟 `leaseUntilWall`，而 `nowWall` 来自网络校时（未同步用设备钟、
 * 同步后切到 elapsedRealtime + 服务器偏移），用户改系统时间同样会动它：
 *  - 时间**前跳** ⇒ 在途行被误判成"租约过期"，被回收扫描抢走重投（同一条通知重复提醒）；
 *  - 时间**后跳** ⇒ 在途行长期停在 PROCESSING（`findDue` 不取它），投递停滞数小时。
 * `claimOutbox` 其实一直在写 `leaseUntilElapsed` 与 `leaseBootId` 两列，只是**从来没被读过**。
 *
 * 修法是"同一次开机内用单调时钟、跨开机才退回墙钟"。这类改动的**本体就是 SQL 里的条件**，
 * 而 SQL 语义只能在真库上执行验证（本仓库的 DAO/事务/索引验证都在 instrumented 测试里，
 * 纯 JVM 单测没有 SQLite 实现）—— 所以这里退一步，把"条件是否还在"钉死：
 * 谁把 `leaseBootId` / `leaseUntilElapsed` 从这三条语句里去掉，测试**立刻失败**。
 *
 * ★ 它证明不了 SQLite 语义（别把绿色当成"SQL 正确"）：把 `<=` 改成 `>` 字符一个没少，
 *   本文件照样全绿。语义只能靠真机验证。
 */
class NotificationLeaseSqlContractTest {

    private val source: String by lazy { readDaoSource() }

    @Test
    fun `启动恢复的租约判定必须 boot 感知`() {
        val sql = querySqlOf("suspend fun findExpiredProcessing(")
        val params = parameterListOf("suspend fun findExpiredProcessing(")

        assertTrue(
            "必须按同一次开机的单调时钟判定（leaseBootId = :bootId 且 leaseUntilElapsed <= :elapsed）",
            sql.contains("leaseBootId = :bootId") && sql.contains("leaseUntilElapsed <= :elapsed")
        )
        assertTrue(
            "跨开机（bootId 不匹配）时才允许退回墙钟，条件必须显式排除同开机的情形",
            sql.contains("leaseBootId <> :bootId") && sql.contains("leaseUntilWall <= :now")
        )
        assertTrue("单调时钟与 bootId 必须是形参，不能写死", params.contains("elapsed") && params.contains("bootId"))
    }

    @Test
    fun `attempt 结算与 PROCESSING 回收必须与取件查询同一口径`() {
        // 三处若口径不一致，会出现"取件说没到期、结算说到期"这类自相矛盾的行为
        for (signature in listOf(
            "suspend fun settleExpiredAttempts(",
            "suspend fun recoverExpiredProcessing("
        )) {
            val sql = querySqlOf(signature)
            val params = parameterListOf(signature)
            assertTrue(
                "$signature 必须与 findExpiredProcessing 用同一套 boot 感知条件",
                sql.contains("leaseBootId = :bootId") && sql.contains("leaseUntilElapsed <= :elapsed")
            )
            assertFalse(
                "$signature 不得只剩墙钟判定（那就是修复被改回去了）",
                sql.contains("AND leaseUntilWall <= :now\n") &&
                    !sql.contains("leaseBootId <> :bootId")
            )
            assertTrue("$signature 的形参必须带上 elapsed 与 bootId", params.contains("elapsed"))
            assertTrue("$signature 的形参必须带上 elapsed 与 bootId", params.contains("bootId"))
        }
    }

    // ---- 源码定位工具（与 MonitorDaoSqlContractTest 同一套做法）----

    private fun readDaoSource(): String {
        val relative = "src/main/java/com/example/bilimonitor/data/local/dao/NotificationDaos.kt"
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
            "找不到 NotificationDaos.kt（工作目录 = ${File("").absolutePath}）；" +
                "本测试必须读到真实源文件，不能静默跳过"
        )
    }

    /** 本测试类 class 文件所在目录（仅当它以目录形式出现在 classpath 上时才可用）。 */
    private fun codeSourceDirs(): List<File> = runCatching {
        val location = NotificationLeaseSqlContractTest::class.java.protectionDomain?.codeSource?.location
            ?: return emptyList()
        listOf(File(location.toURI())).filter { it.isDirectory }
    }.getOrDefault(emptyList())

    /** 取出某个方法签名**之前最近**的那个 `@Query` 块（三引号与单行两种写法都支持）。 */
    private fun querySqlOf(signature: String): String {
        val index = source.indexOf(signature)
        assertTrue("NotificationDaos.kt 里找不到方法：$signature", index >= 0)
        val annotation = source.lastIndexOf("@Query", index)
        assertTrue("@Query 注解缺失（方法签名被改过？）：$signature", annotation in 0 until index)
        val parenOpen = source.indexOf('(', annotation)
        val tripleOpen = source.indexOf("\"\"\"", annotation)
        if (tripleOpen in annotation until index &&
            parenOpen in annotation until tripleOpen &&
            source.substring(parenOpen + 1, tripleOpen).isBlank()
        ) {
            val close = source.indexOf("\"\"\"", tripleOpen + 3)
            assertTrue("三引号 @Query 没有闭合：$signature", close in (tripleOpen + 3) until index)
            return source.substring(tripleOpen + 3, close)
        }
        val singleOpen = source.indexOf('"', parenOpen)
        val singleClose = source.indexOf('"', singleOpen + 1)
        assertTrue(
            "@Query 既不是三引号块、也不是单行字符串：$signature",
            singleOpen in parenOpen until index && singleClose in (singleOpen + 1) until index
        )
        return source.substring(singleOpen + 1, singleClose)
    }

    /** 取出方法形参列表文本（用于断言形参是否带上 elapsed / bootId）。 */
    private fun parameterListOf(signature: String): String {
        val index = source.indexOf(signature)
        assertTrue("NotificationDaos.kt 里找不到方法：$signature", index >= 0)
        val end = source.indexOf(")", index)
        assertTrue("方法签名没有闭合：$signature", end > index)
        return source.substring(index, end)
    }
}
