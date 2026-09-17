package com.example.bilimonitor.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「封禁中」判定与调度单测（用户诉求 1 / 2）。
 *
 * 为什么这块**必须**靠单测：真实封禁无法复现（要求"房间在封禁之前就已加入监控"，
 * 而那个主播已经解封）。因此用例直接用**实测数据**构造：
 *  - 封禁房间 `room_id = 4948511`：`is_locked = true`、`lock_till = -1`（无期限）、
 *    `live_status = 0`、`is_hidden = false`；
 *  - 正常房间：`is_locked = false`、`lock_till = 0`。
 *
 * 以及**真实干扰项**：`code = -352`（风控）、超时、5xx、解析失败 —— 一律不得判成封禁。
 *
 * 文件末尾另有一组**新语义**断言（`RoomBanAddMessageTest`）：产品决策改成"允许添加已封禁的
 * 直播间"之后，判定为封禁不再等于拒绝添加 —— 仍然建立主播行、把封禁标记写进该行，
 * 由引擎跳过常规检查并在复查到期时探一次，解封后自动恢复。
 */
class RoomBanPolicyTest {

    private val now = 1_800_000_000_000L

    private fun probe(
        locked: Boolean,
        lockTill: Long? = null,
        apiCode: Int? = 0,
        hidden: Boolean? = false
    ) = RoomBanProbeResult(
        isLocked = locked, lockTill = lockTill, isHidden = hidden,
        apiCode = apiCode, apiMessage = "success"
    )

    // ---- 1) 判定：只有 is_locked=true 才算封禁 ----

    @Test
    fun `实测封禁房间_is_locked 为 true 判封禁且无期限`() {
        val state = RoomBanPolicy.nextState(
            previous = null,
            probe = probe(locked = true, lockTill = -1L),
            nowWall = now,
            recheckIntervalSeconds = 1800
        )
        assertNotNull("is_locked=true 必须判定为封禁", state)
        assertEquals(-1L, state!!.lockTill)
        assertEquals(now, state.lockedAtWall)
        assertEquals("复查时刻 = now + 30 分钟", now + 1_800_000L, state.nextCheckAtWall)
        assertEquals("无期限封禁", RoomBanPolicy.lockTillLabel(state.lockTill, now))
    }

    @Test
    fun `is_locked 为 false 不判封禁`() {
        assertNull(
            RoomBanPolicy.nextState(null, probe(locked = false, lockTill = 0L), now, 1800)
        )
    }

    @Test
    fun `限时封禁的展示与无期限不同`() {
        val until = now + 3 * 3600_000L
        val state = RoomBanPolicy.nextState(null, probe(locked = true, lockTill = until), now, 1800)!!
        assertEquals(until, state.lockTill)
        assertTrue(RoomBanPolicy.lockTillLabel(state.lockTill, now).startsWith("封禁至 "))
        assertEquals(
            "无期限封禁",
            RoomBanPolicy.lockTillLabel(
                RoomBanPolicy.nextState(null, probe(locked = true, lockTill = -1L), now, 1800)!!.lockTill,
                now
            )
        )
    }

    @Test
    fun `标注期限已过但服务端仍报封禁_如实说明不擅自解封`() {
        val past = now - 60_000L
        val state = RoomBanPolicy.nextState(null, probe(locked = true, lockTill = past), now, 1800)!!
        assertEquals(past, state.lockTill)
        assertTrue(
            "服务端才是权威：到期时间过了但它还说 is_locked=true，就仍然保持封禁",
            RoomBanPolicy.lockTillLabel(state.lockTill, now).startsWith("已过标注期限")
        )
    }

    @Test
    fun `期限未知时给出明确文案而不是瞎猜`() {
        val state = RoomBanPolicy.nextState(null, probe(locked = true, lockTill = 0L), now, 1800)!!
        assertEquals("期限未知", RoomBanPolicy.lockTillLabel(state.lockTill, now))
    }

    // ---- 2) 真实干扰项：一律不得判成封禁 ----

    @Test
    fun `风控 352 拿不到结论_不判封禁也不解除已有封禁`() {
        // 数据源对 code != 0（含实测的 -352 风控）返回 null = 未知。
        assertNull("未知不得产生封禁", RoomBanPolicy.nextState(null, null, now, 1800))
        // 已有封禁遇到风控：保持原样，且复查时刻**不被推迟**（否则风控期间会把复查越推越远）。
        val existing = RoomBanPolicy.nextState(null, probe(locked = true, lockTill = -1L), now, 1800)!!
        val afterProbe = RoomBanPolicy.nextState(existing, null, now + 1_900_000L, 1800)
        assertEquals("风控/超时不得改写已有封禁", existing, afterProbe)
        assertEquals(existing.nextCheckAtWall, afterProbe!!.nextCheckAtWall)
    }

    @Test
    fun `超时与 5xx 与解析失败都等价于未知`() {
        // 数据源把这三类归为 BanProbeOutcome.Failed（不再是含糊的 null），
        // 引擎对 Failed 一律按"未知"折算给策略层（nextState(probe = null)）——
        // 因此判定层面看到的仍然是"未知"：既不算封禁，也绝不解除已有封禁。
        assertNull(RoomBanPolicy.nextState(null, null, now, 1800))
        assertFalse("未知不是封禁", RoomBanPolicy.shouldSkipRegularCheck(null, now))
    }

    @Test
    fun `隐藏房间不等于封禁`() {
        // is_hidden 在封禁与正常房间上取值一致（实测），只做记录：is_locked=false → 不判封禁。
        assertNull(RoomBanPolicy.nextState(null, probe(locked = false, hidden = true), now, 1800))
    }

    // ---- 3) 批量接口自带的封禁标记（零额外请求那条路径） ----

    @Test
    fun `批量标记 is_locked 为 true 直接判封禁`() {
        assertTrue(RoomBanPolicy.lockFromBatchFlags(isLocked = true, lockTillMillis = null, nowWall = now) == true)
    }

    @Test
    fun `批量标记 is_locked 为 false 是明确未封禁`() {
        assertFalse(RoomBanPolicy.lockFromBatchFlags(isLocked = false, lockTillMillis = null, nowWall = now) == true)
        assertEquals(false, RoomBanPolicy.lockFromBatchFlags(isLocked = false, lockTillMillis = now + 1, nowWall = now))
    }

    @Test
    fun `没有 is_locked 时只有无期限或未来的 lock_till 才算封禁`() {
        assertEquals(
            "无期限（-1）是明确的封禁证据",
            true,
            RoomBanPolicy.lockFromBatchFlags(isLocked = null, lockTillMillis = -1L, nowWall = now)
        )
        assertEquals(
            "未来的封禁到期时刻是明确的封禁证据",
            true,
            RoomBanPolicy.lockFromBatchFlags(isLocked = null, lockTillMillis = now + 1000L, nowWall = now)
        )
        assertNull(
            "过期的 lock_till 不构成封禁证据",
            RoomBanPolicy.lockFromBatchFlags(isLocked = null, lockTillMillis = now - 1000L, nowWall = now)
        )
        assertNull(
            "字段缺失 = 未知（不是 false）",
            RoomBanPolicy.lockFromBatchFlags(isLocked = null, lockTillMillis = null, nowWall = now)
        )
    }

    @Test
    fun `lock_till 的两种编码都被正确归一成毫秒`() {
        // room_init 形态：unix 秒
        assertEquals(1_700_000_000_000L, RoomBanPolicy.parseLockTill("1700000000"))
        // 无期限
        assertEquals(-1L, RoomBanPolicy.parseLockTill("-1"))
        // 未封禁的各种写法 → null
        assertNull(RoomBanPolicy.parseLockTill("0"))
        assertNull(RoomBanPolicy.parseLockTill("0000-00-00 00:00:00"))
        assertNull(RoomBanPolicy.parseLockTill(""))
        assertNull(RoomBanPolicy.parseLockTill(null))
        // 垃圾文本 → null（绝不让坏文本变成封禁）
        assertNull(RoomBanPolicy.parseLockTill("not-a-date"))
        // 毫秒值不被二次放大
        assertEquals(1_700_000_000_000L, RoomBanPolicy.normalizeEpochMillis(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, RoomBanPolicy.normalizeEpochMillis(1_700_000_000L))
        // 秒/毫秒的分界常量就是判据本身（1e11 = 1973-03-03）；边界两侧各有单测锁住。
        assertEquals(100_000_000_000L, RoomBanPolicy.SECONDS_MILLIS_FLOOR)
    }

    // ---- 4) 调度：30 分钟内不再检查、到期恢复、解除后立刻恢复 ----

    @Test
    fun `封禁后在间隔内不再检查`() {
        val state = RoomBanPolicy.nextState(null, probe(locked = true, lockTill = -1L), now, 1800)!!
        assertTrue(RoomBanPolicy.shouldSkipRegularCheck(state, now))
        assertTrue("29 分钟内都跳过", RoomBanPolicy.shouldSkipRegularCheck(state, now + 1_799_000L))
    }

    @Test
    fun `到期后恢复检查`() {
        val state = RoomBanPolicy.nextState(null, probe(locked = true, lockTill = -1L), now, 1800)!!
        assertFalse("正好到期就恢复检查（边界取 >=）", RoomBanPolicy.shouldSkipRegularCheck(state, now + 1_800_000L))
        assertFalse(RoomBanPolicy.shouldSkipRegularCheck(state, now + 3_600_000L))
    }

    @Test
    fun `未封禁的主播永不被跳过`() {
        assertFalse(RoomBanPolicy.shouldSkipRegularCheck(null, now))
        assertFalse(RoomBanPolicy.shouldSkipRegularCheck(null, now + 10_000_000L))
    }

    @Test
    fun `解除封禁后立刻回到正常间隔`() {
        val banned = RoomBanPolicy.nextState(null, probe(locked = true, lockTill = -1L), now, 1800)!!
        // 复查拿到 is_locked=false → 立即解除
        val cleared = RoomBanPolicy.nextState(banned, probe(locked = false, lockTill = 0L), now + 60_000L, 1800)
        assertNull("解封必须立即生效，不能等到下一个复查窗口", cleared)
        assertFalse(RoomBanPolicy.shouldSkipRegularCheck(cleared, now + 60_000L))
    }

    @Test
    fun `复查间隔可自定义且被夹在合理范围`() {
        val short = RoomBanPolicy.nextState(null, probe(locked = true), now, 60)!!
        assertEquals(now + 60_000L, short.nextCheckAtWall)
        val long = RoomBanPolicy.nextState(null, probe(locked = true), now, 86_400)!!
        assertEquals(now + 86_400_000L, long.nextCheckAtWall)
        // 坏配置（0 / 负数 / 超大）不会把复查拖成"永不检查"或"每毫秒一次"。
        assertEquals(now + 60_000L, RoomBanPolicy.nextState(null, probe(locked = true), now, 0)!!.nextCheckAtWall)
        assertEquals(now + 60_000L, RoomBanPolicy.nextState(null, probe(locked = true), now, -5)!!.nextCheckAtWall)
        assertEquals(
            now + 86_400_000L,
            RoomBanPolicy.nextState(null, probe(locked = true), now, Int.MAX_VALUE)!!.nextCheckAtWall
        )
        assertTrue(RoomBanPolicy.RECHECK_RANGE.contains(RoomBanPolicy.DEFAULT_RECHECK_SECONDS))
    }

    @Test
    fun `续期保留首次封禁时刻与续期后的复查时刻`() {
        val first = RoomBanPolicy.nextState(null, probe(locked = true), now, 1800)!!
        val second = RoomBanPolicy.nextState(first, probe(locked = true), now + 1_800_000L, 1800)!!
        assertEquals("首次判定时刻不被复查覆盖", now, second.lockedAtWall)
        assertEquals(now + 1_800_000L, second.lastProbeAtWall)
        assertEquals("复查时刻以本次探针为基准重新计算", now + 3_600_000L, second.nextCheckAtWall)
    }

    @Test
    fun `探针门槛_未封禁时只在有怀疑时探`() {
        assertFalse("正常情况下零额外请求", RoomBanPolicy.shouldProbe(null, suspicionRaised = false, nowWall = now))
        assertTrue("本轮检查失败才探一次", RoomBanPolicy.shouldProbe(null, suspicionRaised = true, nowWall = now))
        val banned = RoomBanPolicy.nextState(null, probe(locked = true), now, 1800)!!
        assertFalse("封禁中未到期不重复探", RoomBanPolicy.shouldProbe(banned, false, now + 1000L))
        assertTrue("封禁中到期才复查", RoomBanPolicy.shouldProbe(banned, false, now + 1_800_000L))
    }
}

/** [RoomBanState] 与 `streamer.lastError` 之间的编解码（持久化正确性）。 */
class RoomBanCodecTest {

    private val state = RoomBanState(
        lockedAtWall = 1_800_000_000_000L,
        lastProbeAtWall = 1_800_000_100_000L,
        nextCheckAtWall = 1_800_001_800_000L,
        lockTill = -1L,
        isHidden = false,
        apiCode = 0,
        apiMessage = "success"
    )

    @Test
    fun `编解码往返一致`() {
        val decoded = RoomBanCodec.decode(RoomBanCodec.encode(state))
        assertNotNull(decoded)
        assertEquals(state, decoded)
    }

    @Test
    fun `限时封禁的期限也往返一致`() {
        val limited = state.copy(lockTill = 1_900_000_000_000L)
        assertEquals(limited, RoomBanCodec.decode(RoomBanCodec.encode(limited)))
    }

    @Test
    fun `普通错误文本不会被当成封禁标记`() {
        assertNull(RoomBanCodec.decode(null))
        assertNull(RoomBanCodec.decode(""))
        assertNull(RoomBanCodec.decode("HTTP 429 限流"))
        assertNull("前缀必须完全匹配", RoomBanCodec.decode("ROOM_BANNEDX {}"))
        assertFalse(RoomBanCodec.isBanned("网络不可用"))
    }

    @Test
    fun `坏 JSON 解析失败时返回 null 而不是崩`() {
        assertNull(RoomBanCodec.decode("ROOM_BANNED {oops"))
        assertNull("缺关键字段也当作未封禁", RoomBanCodec.decode("ROOM_BANNED {\"lockedAt\":1}"))
    }

    @Test
    fun `编码长度被截断在字段上限内`() {
        val long = state.copy(apiMessage = "x".repeat(2000))
        val encoded = RoomBanCodec.encode(long)
        assertTrue("lastError 不能被超长 message 撑爆", encoded.length <= RoomBanCodec.MAX_CHARS)
        assertTrue(encoded.startsWith(RoomBanCodec.PREFIX))
    }

    @Test
    fun `证据文本带上全部原始信号`() {
        val text = RoomBanCodec.evidence(state, nowWall = 1_800_002_000_000L)
        assertTrue(text.contains("is_locked=true"))
        assertTrue(text.contains("lock_till=-1"))
        assertTrue(text.contains("无期限封禁"))
        assertTrue(text.contains("is_hidden=false"))
        assertTrue(text.contains("api_code=0"))
    }
}

/**
 * 「允许添加已封禁的直播间」这条产品行为的文案与语义单测（取代原先的"拒绝添加"）。
 *
 * ## 与旧语义的关系（如实说明，不删测试）
 * 原先的拒绝添加语义**没有**任何单测覆盖（`AddStreamerResult.Banned` 在测试树里零引用，
 * 已逐文件核对），因此这里不存在"因为改语义而失效"的断言 —— 本组用例是**新增**的，
 * 用来把新语义钉住：判定为封禁 ⇒ 仍然建行 + 写标记 + 跳常规检查 + 到期复查。
 *
 * ## 为什么只能测到这一层
 * 仓储层（`StreamerRepository.addStreamer` 的"先 insert 再 applyBanState"顺序）
 * 需要真实 Room 数据库：`db.withTransaction` 是 `RoomDatabase` 的扩展，本模块的测试依赖
 * 只有 JUnit + coroutines-test（无 Robolectric、无 mock 框架），所以那一层由代码结构与
 * 注释保证、靠父代理的统一编译/真机验证兜底 —— 这里把**它调用的纯函数**全部锁死。
 */
class RoomBanAddMessageTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `已添加但封禁中的文案逐字锁定_无期限`() {
        assertEquals(
            "已添加：某某（该直播间当前处于封禁中：无期限封禁）。" +
                "它现在不会开播，监控也不会误报开播/下播；" +
                "解封前按较低频率（约 30 分钟一次）自动复查，解封后自动恢复正常监控。",
            RoomBanPolicy.addSuccessMessage("某某", -1L, 1800, now)
        )
    }

    @Test
    fun `限时封禁说清楚封到什么时候`() {
        val until = now + 3 * 3600_000L
        val label = RoomBanPolicy.lockTillLabel(until, now)
        assertTrue("限时封禁必须给出到期时刻", label.startsWith("封禁至 "))
        val message = RoomBanPolicy.addSuccessMessage("某某", until, 1800, now)
        assertTrue("文案里的期限必须与落库/调度用的是同一份解析：$message", message.contains("（该直播间当前处于封禁中：$label）"))
        assertFalse("不能把限时封禁说成无期限：$message", message.contains("无期限"))
    }

    @Test
    fun `期限未知时如实说未知而不是瞎猜`() {
        assertTrue(
            RoomBanPolicy.addSuccessMessage("某某", 0L, 1800, now)
                .contains("（该直播间当前处于封禁中：期限未知）")
        )
        assertTrue(
            RoomBanPolicy.addSuccessMessage("某某", null, 1800, now)
                .contains("（该直播间当前处于封禁中：期限未知）")
        )
    }

    @Test
    fun `标注期限已过但服务端仍报封禁_文案不自作主张说已解封`() {
        val message = RoomBanPolicy.addSuccessMessage("某某", now - 60_000L, 1800, now)
        assertTrue("服务端才是权威，只能如实说明：$message", message.contains("已过标注期限"))
        assertFalse("不得擅自宣布已解封：$message", message.contains("已解封"))
    }

    @Test
    fun `复查间隔文案跟随配置且向上取整`() {
        assertTrue(RoomBanPolicy.addSuccessMessage("某某", -1L, 1800, now).contains("约 30 分钟一次"))
        assertTrue(RoomBanPolicy.addSuccessMessage("某某", -1L, 3600, now).contains("约 60 分钟一次"))
        assertTrue(RoomBanPolicy.addSuccessMessage("某某", -1L, 60, now).contains("约 1 分钟一次"))
        // 坏配置（0 / 负数）被夹到下限，不会说出"约 0 分钟一次"这种胡话。
        assertTrue(RoomBanPolicy.addSuccessMessage("某某", -1L, 0, now).contains("约 1 分钟一次"))
        assertTrue(RoomBanPolicy.addSuccessMessage("某某", -1L, -5, now).contains("约 1 分钟一次"))
    }

    @Test
    fun `三种期限表达都在告诉用户不会开播也不会误报`() {
        for (lockTill in listOf(-1L, now + 3600_000L, 0L)) {
            val message = RoomBanPolicy.addSuccessMessage("某某", lockTill, 1800, now)
            assertTrue("必须说明现在不会开播：$message", message.contains("它现在不会开播"))
            assertTrue("必须说明不会误报：$message", message.contains("监控也不会误报开播/下播"))
            assertTrue("必须是成功提示（已添加）：$message", message.startsWith("已添加：某某"))
        }
    }

    // ---- 新语义：判定为封禁时仍然建立主播行，并把封禁标记写进去，交给复查接管 ----

    @Test
    fun `判定为封禁时的落库标记可被解码_并跳过常规检查直到复查到期`() {
        // 与 StreamerRepository.addStreamer 完全同一条构造路径：
        //   RoomBanPolicy.nextState(previous = null, probe = 探针, recheckIntervalSeconds = 配置值)
        // 得到的状态随后由 MonitorRepository.applyBanState → RoomBanCodec.encode 写进
        // streamer.lastError（所以这里连编码一起断言：写得进去、读得出来、字段不丢）。
        val state = RoomBanPolicy.nextState(
            previous = null,
            probe = RoomBanProbeResult(
                isLocked = true, lockTill = -1L, isHidden = false,
                apiCode = 0, apiMessage = "success", roomId = 4948511L
            ),
            nowWall = now,
            recheckIntervalSeconds = 1800
        )
        assertNotNull("判定为封禁时必须有可落库的状态（旧实现是直接拒绝添加）", state)
        val persisted = RoomBanCodec.encode(requireNotNull(state))
        assertTrue("写进 lastError 的必须是封禁标记", RoomBanCodec.isBanned(persisted))
        assertEquals("编解码往返不得丢字段", state, RoomBanCodec.decode(persisted))
        assertTrue("封禁期间跳过常规检查 ⇒ 不开播、也不会误报", RoomBanPolicy.shouldSkipRegularCheck(state, now))
        assertTrue(
            "到期后由封禁探针接管（默认 30 分钟）",
            RoomBanPolicy.shouldProbe(state, suspicionRaised = false, nowWall = now + 1_800_000L)
        )
        assertFalse("未到期不重复探", RoomBanPolicy.shouldProbe(state, suspicionRaised = false, nowWall = now + 1_000L))
    }

    @Test
    fun `探针拿不到结论时不写封禁标记_失败仍按原分类处理`() {
        // 添加路径上的"问不出来"（超时 / 风控 -352 / 5xx / 解析失败）一律不得变成封禁：
        // 探针为 null 时 nextState 原样返回 previous（添加路径的 previous 就是 null）。
        assertNull(RoomBanPolicy.nextState(null, null, now, 1800))
        // 探针明确说"没被封"同样不写标记。
        assertNull(RoomBanPolicy.nextState(null, RoomBanProbeResult(isLocked = false, lockTill = 0L), now, 1800))
        // 批量标记那条路径同理：没有 is_locked 且 lock_till 不构成证据 → 未知，不判封禁。
        assertNull(
            RoomBanPolicy.lockFromBatchFlags(isLocked = null, lockTillMillis = null, nowWall = now)
        )
    }

    @Test
    fun `探针带回真实房间号_否则引擎永远复查不了`() {
        // 允许添加之后，主播行里的 roomId 是引擎复查的唯一依据（refreshDueBanProbes 只用
        // roomId ?: shortRoomId 发探针）。room_init 的 id 参数也接受 uid，所以真实房间号
        // 只能从探针响应里带回来 —— 这条用例锁住"字段存在且默认值不破坏旧调用点"。
        assertEquals(4948511L, RoomBanProbeResult(isLocked = true, lockTill = -1L, roomId = 4948511L).roomId)
        assertNull("旧调用点（批量标记路径）不传也不会编译失败", RoomBanProbeResult(isLocked = true).roomId)
    }
}

/**
 * 封禁链路**记录策略**单测（本次日志缺陷修复的核心验收点）。
 *
 * ## 为什么这块必须靠单测
 * 记录频率的正确性没法在真机上"看一眼"验证：用户实测到的是"11:58→12:05 每分钟一条
 * API_REJECTED"，而修复后要证明的是
 *  ① **连续 10 轮 tick 且封禁状态不变 ⇒ 0 条记录**（缺陷 2：刷屏）；
 *  ② 同一失败信号连续出现只落库 **1** 条（探针失败不推进复查时刻，会每轮重试）；
 *  ③ 只有"进入封禁 / 解除封禁"才算状态变化（缺陷 1：错误码撒谎的根源是把正常调度当成错误）。
 * 这三条判据都被抽成了不含数据库、不含 Android 依赖的纯函数（[BanLogPolicy] / [BanLogEdgeGate]），
 * 因此可以在这里逐字锁定 —— 而"每轮写一条"这种回归会立刻让用例变红。
 *
 * 注意：这里测的是**记录策略**，不是判定与调度（后者见上面的 RoomBanPolicyTest）。判定与调度
 * 在这次修复中逐字未动。
 */
class BanLogPolicyTest {

    private val now = 1_800_000_000_000L

    private fun banned(recheckSeconds: Int = 1800) = RoomBanPolicy.nextState(
        previous = null,
        probe = RoomBanProbeResult(
            isLocked = true, lockTill = -1L, isHidden = false,
            apiCode = 0, apiMessage = "success"
        ),
        nowWall = now,
        recheckIntervalSeconds = recheckSeconds
    )!!

    private fun probeStillLocked() = RoomBanProbeResult(
        isLocked = true, lockTill = -1L, isHidden = false, apiCode = 0, apiMessage = "success"
    )

    // ---- 1) 只有"进入 / 解除"才算状态变化 ----

    @Test
    fun `从未封禁到封禁_是进入封禁的状态变化`() {
        assertEquals(BanTransition.ENTERED, BanLogPolicy.transitionOf(null, banned()))
    }

    @Test
    fun `从封禁到未封禁_是解除封禁的状态变化`() {
        assertEquals(BanTransition.LIFTED, BanLogPolicy.transitionOf(banned(), null))
    }

    @Test
    fun `始终未封禁_不是状态变化`() {
        assertEquals(BanTransition.NONE, BanLogPolicy.transitionOf(null, null))
    }

    // ---- 2) 频率：状态不变就一条都不记 ----

    @Test
    fun `连续 10 轮复查仍是封禁中_一条留痕都不产生`() {
        // 修复前：这一场景在真机上表现为"每分钟一条 API_REJECTED"（用户实测 11:58→12:05）。
        var state: RoomBanState? = banned()
        var transitions = 0
        repeat(10) { round ->
            // 每一轮到期复查都拿到"仍封禁"的结论 → 续期，但状态本身没变。
            val next = RoomBanPolicy.nextState(
                previous = state,
                probe = probeStillLocked(),
                nowWall = now + round * 1_800_000L,
                recheckIntervalSeconds = 1800
            )
            if (BanLogPolicy.transitionOf(state, next) != BanTransition.NONE) transitions++
            state = next
        }
        assertEquals("10 轮复查状态都没变 ⇒ 应写 0 条（修复前是 10 条）", 0, transitions)
    }

    @Test
    fun `连续 10 轮跳过常规检查_不产生任何留痕`() {
        // 每轮 tick 都会对未到期的封禁主播打印"跳过"这一调度事实（logcat），
        // 但它**不产生新状态**：next 就是 previous，因此 transitionOf 恒为 NONE —— 不写任何库。
        val state = banned()
        repeat(10) {
            assertTrue(
                "未到期必须跳过常规检查（调度逻辑未改动）",
                RoomBanPolicy.shouldSkipRegularCheck(state, now + 60_000L)
            )
            assertEquals(BanTransition.NONE, BanLogPolicy.transitionOf(state, state))
        }
    }

    // ---- 3) 探针失败：同一信号只落库一条（边沿触发） ----

    @Test
    fun `同一失败信号连续 10 轮只落库一条`() {
        val gate = BanLogEdgeGate()
        val signature = "TIMEOUT|null|null|null"
        assertTrue("第一次失败必须落库", gate.shouldRecord("str-1", signature))
        repeat(9) {
            assertFalse("同一信号连续出现必须抑制（否则又是每分钟一条）", gate.shouldRecord("str-1", signature))
        }
        assertEquals(1, gate.tracked())
    }

    @Test
    fun `失败原因变化时重新落库`() {
        val gate = BanLogEdgeGate()
        assertTrue(gate.shouldRecord("str-1", "TIMEOUT|null|null|null"))
        assertFalse(gate.shouldRecord("str-1", "TIMEOUT|null|null|null"))
        assertTrue(
            "超时变成风控（-352）是新的失败分类，必须再记一条",
            gate.shouldRecord("str-1", "API_REJECTED|null|-352|请求过于频繁")
        )
    }

    @Test
    fun `一位主播的失败不影响另一位`() {
        val gate = BanLogEdgeGate()
        assertTrue(gate.shouldRecord("str-1", "TIMEOUT"))
        assertTrue("每位主播的第一次失败都必须留下记录", gate.shouldRecord("str-2", "TIMEOUT"))
        assertFalse(gate.shouldRecord("str-2", "TIMEOUT"))
    }

    @Test
    fun `探针恢复正常后_下次同一信号仍然值得记一条`() {
        val gate = BanLogEdgeGate()
        assertTrue(gate.shouldRecord("str-1", "TIMEOUT"))
        // 拿到结论 / 解除封禁时调用 clear：这一位主播已恢复正常。
        gate.clear("str-1")
        assertEquals(0, gate.tracked())
        assertTrue("恢复之后再次失败是新的故障，必须重新记录", gate.shouldRecord("str-1", "TIMEOUT"))
    }
}

/**
 * 缺陷 2 的回归锁：封禁探针的 `lock_till` **单位归一**（2026 修复）。
 *
 * ## 为什么必须有这一组
 * `room_init.lock_till` 是 unix **秒**（`-1` = 无期限），而展示/落库/调度全线按**毫秒**
 * 比较与格式化。原实现在 `BiliLiveApi.probeBan` 里直接透传原始值，于是服务端返回
 * `1767225600`（2026-01-01）时 `lockTill > nowWall` 恒为 false ⇒ 用户看到的是
 * 「已过标注期限（1970-01-19 …）仍未解封」，并被写进 `AddStreamerResult.AddedBanned.message`
 * 与审计留痕 —— 一个"少乘 1000"被说成"服务端给的期限已经过了"。
 *
 * 这一层能测：归一入口 [RoomBanPolicy.normalizeLockTillMillis] 与展示
 * [RoomBanPolicy.lockTillLabel] 都是纯函数。测不到的是 `BiliLiveApi.probeBan` 那一行
 * "有没有真的调它"（数据源依赖 retrofit/Android，JVM 单测加载不了），那一步靠父代理的
 * 统一编译 + 真机验证兜底。
 *
 * ## 断言为什么这样写
 *  - 不逐个断言日期字符串：`lockTillLabel` 内部用 `SimpleDateFormat` 按**设备时区**格式化，
 *    写死 "2026-01-01 00:00" 只能在 UTC+8 的机器上通过。这里改为与同一个 `nowWall` 量级的
 *    期望值相比对（两边用同一时区），因此任何时区都成立；
 *  - 同时断言**不得**出现 1970 / "已过标注期限"：这才是缺陷的真实症状，
 *    只断言"以「封禁至 」开头"会漏掉"日期算错但仍是未来时刻"那类回归。
 */
class RoomBanLockTillUnitTest {

    /** 2026-01-01 00:00:00 UTC 的 **unix 秒**取值（缺陷报告里的那个数）。 */
    private val untilSeconds = 1_767_225_600L

    /** 同上时刻的**毫秒**取值 —— 与 `untilSeconds` 只差 1000 倍。 */
    private val untilMillis = untilSeconds * 1000L

    /** 探测时刻：2025-12-31 23:00:00 UTC，即封禁到期**前**一小时。 */
    private val nowBefore = untilMillis - 3_600_000L

    /** 探测时刻：2026-01-01 01:00:00 UTC，即封禁到期**后**一小时。 */
    private val nowAfter = untilMillis + 3_600_000L

    /** 与 `lockTillLabel` 同源的格式化（同一时区、同一模式），用于比对期望日期。 */
    private fun wall(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(millis))

    // ---- 1) 归一入口本身 ----

    @Test
    fun `数值归一把秒与毫秒都归一成同一个毫秒时刻`() {
        // 服务端给秒（room_init 的实际形态）
        assertEquals(untilMillis, RoomBanPolicy.normalizeLockTillMillis(untilSeconds))
        // 服务端若给毫秒：**不得**被二次放大 1000 倍
        assertEquals(untilMillis, RoomBanPolicy.normalizeLockTillMillis(untilMillis))
    }

    @Test
    fun `无期限与未知在归一入口就被区分开`() {
        // -1 = 无期限封禁（服务端哨兵值），原样保留，绝不当成"1970 年到期"
        assertEquals(-1L, RoomBanPolicy.normalizeLockTillMillis(-1L))
        // 0 / null = 没给期限 = 未知
        assertNull("0 是未知，不是 1970-01-01", RoomBanPolicy.normalizeLockTillMillis(0L))
        assertNull("null 是未知", RoomBanPolicy.normalizeLockTillMillis(null))
    }

    @Test
    fun `秒毫秒分界两侧各自归一正确且不越界`() {
        val floor = RoomBanPolicy.SECONDS_MILLIS_FLOOR
        assertEquals("分界下方按秒 ×1000", floor, RoomBanPolicy.normalizeEpochMillis(floor / 1000L))
        assertEquals("分界上方视为毫秒，原样返回", floor, RoomBanPolicy.normalizeEpochMillis(floor))
        assertEquals(floor + 1L, RoomBanPolicy.normalizeEpochMillis(floor + 1L))
    }

    @Test
    fun `批量接口的字符串路径与数值路径归一结果一致`() {
        // 同一个到期时刻的两种编码必须落到同一个毫秒值，否则界面与引擎会各说各话。
        assertEquals(untilMillis, RoomBanPolicy.parseLockTill(untilSeconds.toString()))
        assertEquals(untilMillis, RoomBanPolicy.normalizeLockTillMillis(untilSeconds))
    }

    // ---- 2) 缺陷的真实症状：探针拿到秒值 → 必须显示"封禁至 2026-01-01 …" ----

    @Test
    fun `探针秒值经归一后显示为封禁至 2026-01-01 而不是 1970`() {
        val normalized = RoomBanPolicy.normalizeLockTillMillis(untilSeconds)
        assertEquals("归一结果必须是毫秒时刻", untilMillis, normalized)
        val label = RoomBanPolicy.lockTillLabel(normalized, nowBefore)
        assertEquals("封禁至 ${wall(untilMillis)}", label)
        assertFalse("绝不能把 2026 年说成 1970 年：$label", label.contains("1970"))
        assertFalse("归一成功后不得再落到已过标注期限：$label", label.contains("已过标注期限"))
    }

    @Test
    fun `探针毫秒值与秒值得到完全相同的展示`() {
        assertEquals(
            RoomBanPolicy.lockTillLabel(RoomBanPolicy.normalizeLockTillMillis(untilSeconds), nowBefore),
            RoomBanPolicy.lockTillLabel(RoomBanPolicy.normalizeLockTillMillis(untilMillis), nowBefore)
        )
    }

    @Test
    fun `探针路径的完整链路_归一后落库标题与提示都是正确的期限`() {
        // 与 StreamerRepository.addStreamer 完全同一条链路：
        //   探针（BiliLiveApi 归一）→ nextState（落库状态）→ addSuccessMessage（用户看到的提示）
        val probe = RoomBanProbeResult(
            isLocked = true,
            lockTill = RoomBanPolicy.normalizeLockTillMillis(untilSeconds),
            isHidden = false,
            apiCode = 0,
            apiMessage = "success",
            roomId = 4948511L
        )
        val state = RoomBanPolicy.nextState(null, probe, nowBefore, 1800)!!
        assertEquals("落库的必须是毫秒时刻", untilMillis, state.lockTill)
        val message = RoomBanPolicy.addSuccessMessage("某某", state.lockTill, 1800, nowBefore)
        assertTrue("提示必须说出真实到期时刻：$message", message.contains("封禁至 ${wall(untilMillis)}"))
        assertFalse("缺陷原文案必须消失：$message", message.contains("1970"))
        assertFalse("限时封禁不得被说成无期限：$message", message.contains("无期限"))
    }

    // ---- 3) 归一之后仍然逐字保持的既有语义 ----

    @Test
    fun `负一仍然是无期限`() {
        val state = RoomBanPolicy.nextState(
            null,
            RoomBanProbeResult(isLocked = true, lockTill = RoomBanPolicy.normalizeLockTillMillis(-1L)),
            nowBefore,
            1800
        )!!
        assertEquals(-1L, state.lockTill)
        assertEquals("无期限封禁", RoomBanPolicy.lockTillLabel(state.lockTill, nowBefore))
    }

    @Test
    fun `零与垃圾都是未知_绝不当成已过期`() {
        assertNull(RoomBanPolicy.normalizeLockTillMillis(0L))
        // null 与 0 走同一条"期限未知"，而**不是**"已过标注期限"
        assertEquals("期限未知", RoomBanPolicy.lockTillLabel(RoomBanPolicy.normalizeLockTillMillis(0L), nowBefore))
        assertEquals("期限未知", RoomBanPolicy.lockTillLabel(RoomBanPolicy.normalizeLockTillMillis(null), nowBefore))
        // 字符串路径的垃圾值同理：解析失败不得变成"已过期"
        assertNull(RoomBanPolicy.parseLockTill("not-a-date"))
        assertNull("0000-00-00 只是未封禁的写法，不是 1970 年到期", RoomBanPolicy.parseLockTill("0000-00-00 00:00:00"))
        assertNull(RoomBanPolicy.parseLockTill(""))
    }

    @Test
    fun `到期后的秒值仍如实说明已过期限而不是崩或归零`() {
        // 归一与"是否已过期"是两件事：归一正确之后，过期的秒值照样落到"已过标注期限"。
        val label = RoomBanPolicy.lockTillLabel(
            RoomBanPolicy.normalizeLockTillMillis(untilSeconds), nowAfter
        )
        assertTrue("服务端到期了却仍报 is_locked，只能如实说明：$label", label.startsWith("已过标注期限"))
        assertTrue("日期仍须是 2026 年那一天：$label", label.contains(wall(untilMillis)))
        assertFalse("不许再出现 1970：$label", label.contains("1970"))
    }

    // ---- 4) 防御：忘了归一时绝不为它编造一个 1970 年的日期 ----

    @Test
    fun `忘了归一时标签拒绝编造日期_绝不说已过期`() {
        // 探针再一次"直接透传秒值"这种回归，症状应当立刻自曝（"无法识别"），
        // 而不是伪装成一句听起来很确定的"已过标注期限（1970-01-19 …）"。
        val label = RoomBanPolicy.lockTillLabel(untilSeconds, nowBefore)
        assertEquals("期限未知（时间戳单位无法识别）", label)
        assertFalse("绝不能说它已经过期：$label", label.contains("已过标注期限"))
        assertFalse("绝不能出现 1970：$label", label.contains("1970"))
    }
}
