package com.example.bilimonitor.data.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高级保活的命令与解析契约。
 *
 * ## 为什么这些断言长这样
 * 下面每一条**输出样本都是从 Android 15（API 35）模拟器上实测抓下来的**，不是编的。
 * 实测过程中踩到一个真坑，值得写死在测试里：
 *
 * - `am get-standby-bucket <包名>` 打印的是 AppStandbyController 的**内部**常量（ACTIVE = **5**），
 *   而 `am get-standby-bucket`（不带包名）整表 dump 用的是 UsageStatsManager 的**公开**常量
 *   （ACTIVE = 10）。按公开常量写判定，会把"已生效"读成"没生效"。
 * - `cmd appops get <包> <op>` 在没有任何显式设置时输出两行
 *   （`No operations.` + `Default mode: allow`），解析必须能从这种形态里读出 allow，
 *   否则"系统默认就放行"会被误判成"没放行"。
 * - `cmd deviceidle whitelist` 每行是 `system-excidle,<包名>,<uid>` 三元组，
 *   按列解析很容易把 uid 当成包名。
 *
 * 这些都属于"字符错一个就静静判反"的地方，所以用单测钉死。
 */
class KeepAliveCommandsTest {

    // ---- 真实输出样本（模拟器抓取）----

    private val whitelistRealOutput = """
        system-excidle,com.android.providers.calendar,10079
        system-excidle,com.android.shell,2000
        user,com.example.bilimonitor,10153
        system,android,1000
    """.trimIndent()

    @Test
    fun `白名单解析_真实三元组格式能认出包名且不把 uid 当包名`() {
        val set = KeepAliveCommands.parseWhitelist(whitelistRealOutput)
        assertTrue("必须能认出目标包", "com.example.bilimonitor" in set)
        assertTrue("系统包也要在集合里（说明没漏行）", "com.android.shell" in set)
        assertFalse("没加入过的包不能在里面", "com.example.not.installed" in set)
        assertFalse("uid 不应被当成包名", "10153" in set)
    }

    @Test
    fun `白名单解析_兼容三元组与单包一行两种写法`() {
        assertTrue("单包一行要认", "com.c" in KeepAliveCommands.parseWhitelist("com.c\n"))
        val triples = KeepAliveCommands.parseWhitelist("system-excidle,com.a,10001")
        assertTrue("三元组取中间那段（包名）", "com.a" in triples)
        assertFalse("类型串不能被当包名", "system-excidle" in triples)
        assertFalse("uid 不能被当包名", "10001" in triples)
    }

    @Test
    fun `待机桶_5 与 10 都算不会被降级_其余档位不算`() {
        // ★ 这个 CLI 有两套常量尺度（内部 ACTIVE=5 / 公开 EXEMPTED=5；公开 ACTIVE=10 /
        //   内部 WORKING_SET=10），实测无法区分用的是哪套（详见 KeepAliveCommands 里的实测记录）。
        //   能确定的是"5 与 10 属于不会被降级的档位"，测试只钉这一条。
        assertEquals(5, KeepAliveCommands.parseStandbyBucket("5"))
        assertEquals(10, KeepAliveCommands.parseStandbyBucket("10"))
        assertTrue(KeepAliveCommands.isNotDegradedBucket(5))
        assertTrue(KeepAliveCommands.isNotDegradedBucket(10))
        assertFalse("20 起就是会被降级的档位", KeepAliveCommands.isNotDegradedBucket(20))
        assertFalse(KeepAliveCommands.isNotDegradedBucket(40))
        assertFalse("读不到时不能算生效", KeepAliveCommands.isNotDegradedBucket(null))
        assertFalse("0 这种无效值不能算", KeepAliveCommands.isNotDegradedBucket(0))
    }

    @Test
    fun `待机桶_描述不猜名字_只说会不会被降级`() {
        // 撒谎比模糊更糟：5 在不同读法下可能是 ACTIVE 也可能是 EXEMPTED，就不写死名字
        val five = KeepAliveCommands.bucketLabel(5)
        assertTrue("要说明不会被降级", five.contains("不会被按"))
        assertTrue("要如实说明尺度不确定", five.contains("无法确定"))
        assertTrue("20 要如实标成会被降级", KeepAliveCommands.bucketLabel(20).contains("会被降级"))
        assertEquals("读取失败", KeepAliveCommands.bucketLabel(null))
        assertTrue("未知值要如实显示数字", KeepAliveCommands.bucketLabel(99).contains("99"))
    }

    @Test
    fun `待机桶_输出夹带文字时取第一个整数`() {
        assertEquals(5, KeepAliveCommands.parseStandbyBucket("bucket=5\n"))
        assertNull(KeepAliveCommands.parseStandbyBucket(""))
        assertNull(KeepAliveCommands.parseStandbyBucket("Unknown command"))
    }

    @Test
    fun `appops_没有任何显式设置时的 Default mode 必须读成 allow`() {
        // 实测输出："No operations.\r\nDefault mode: allow"
        val real = "No operations.\r\nDefault mode: allow"
        assertEquals("allow", KeepAliveCommands.parseAppOpMode(real))
        assertTrue("系统默认放行就不该报「未放行」", KeepAliveCommands.appOpAllowed("allow"))
    }

    @Test
    fun `appops_显式设置后的输出形态`() {
        assertEquals("allow", KeepAliveCommands.parseAppOpMode("RUN_IN_BACKGROUND: allow"))
        assertEquals("ignore", KeepAliveCommands.parseAppOpMode("WAKE_LOCK: ignore"))
        assertEquals("deny", KeepAliveCommands.parseAppOpMode("RUN_ANY_IN_BACKGROUND: deny"))
        assertEquals("default", KeepAliveCommands.parseAppOpMode("WAKE_LOCK: default"))
        assertNull(KeepAliveCommands.parseAppOpMode("没有冒号的输出"))
    }

    @Test
    fun `appops_放行判定_只认 allow`() {
        assertTrue(KeepAliveCommands.appOpAllowed("allow"))
        // ★ foreground 的语义是"仅前台可用"：对 RUN_IN_BACKGROUND / WAKE_LOCK 这些后台类 op
        //   恰恰等于"后台没放行"。曾经把它当放行，会得出与 op 语义相反的结论。
        assertFalse("foreground（仅前台）不能算后台已放行", KeepAliveCommands.appOpAllowed("foreground"))
        assertFalse(KeepAliveCommands.appOpAllowed("ignore"))
        assertFalse(KeepAliveCommands.appOpAllowed("deny"))
        assertFalse("读不到时不能算放行", KeepAliveCommands.appOpAllowed(null))
        assertTrue("default 交回系统策略，不算显式放行", !KeepAliveCommands.appOpAllowed("default"))
    }

    @Test
    fun `oom_score_adj_解析与保护判定`() {
        assertEquals(-1000, KeepAliveCommands.parseOomScoreAdj("-1000"))
        assertEquals(0, KeepAliveCommands.parseOomScoreAdj("0\n"))
        assertEquals(700, KeepAliveCommands.parseOomScoreAdj("700"))
        assertNull("进程不存在（missing）时不能当 0 处理", KeepAliveCommands.parseOomScoreAdj("missing"))
        assertNull(KeepAliveCommands.parseOomScoreAdj(""))
        // 保护判定：小于等于目标值才算受保护
        assertTrue(KeepAliveCommands.parseOomScoreAdj("-1000")!! <= KeepAliveCommands.OOM_SCORE_ADJ_PROTECTED)
        assertFalse(KeepAliveCommands.parseOomScoreAdj("0")!! <= KeepAliveCommands.OOM_SCORE_ADJ_PROTECTED)
    }

    @Test
    fun `命令字符串_与实测通过的命令逐字一致`() {
        // 这些命令都在 API 35 模拟器上实跑过（exit=0）
        val pkg = "com.example.bilimonitor"
        assertEquals("cmd deviceidle whitelist", KeepAliveCommands.CMD_WHITELIST_LIST)
        assertEquals("cmd deviceidle whitelist +$pkg", KeepAliveCommands.cmdWhitelistAdd(pkg))
        assertEquals("cmd deviceidle whitelist -$pkg", KeepAliveCommands.cmdWhitelistRemove(pkg))
        assertEquals("am set-standby-bucket $pkg active", KeepAliveCommands.cmdSetStandbyActive(pkg))
        assertEquals("am set-standby-bucket $pkg working_set", KeepAliveCommands.cmdSetStandbyWorkingSet(pkg))
        assertEquals("am get-standby-bucket $pkg", KeepAliveCommands.cmdGetStandbyBucket(pkg))
        // 两个"不会被降级"的取值（见尺度说明）
        assertTrue(KeepAliveCommands.BUCKET_NOT_DEGRADED_A < KeepAliveCommands.BUCKET_NOT_DEGRADED_B)
        assertEquals("cmd appops get $pkg WAKE_LOCK", KeepAliveCommands.cmdGetAppOp(pkg, "WAKE_LOCK"))
        assertEquals("cmd appops set $pkg WAKE_LOCK allow", KeepAliveCommands.cmdAppOpSet(pkg, "WAKE_LOCK", "allow"))
        assertEquals(3, KeepAliveCommands.APP_OPS.size)
        assertTrue(KeepAliveCommands.APP_OPS.contains("RUN_IN_BACKGROUND"))
        assertTrue(KeepAliveCommands.APP_OPS.contains("WAKE_LOCK"))
    }
}
