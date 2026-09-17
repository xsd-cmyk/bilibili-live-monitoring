package com.example.bilimonitor.ui.common

import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.domain.policy.RoomBanCodec
import com.example.bilimonitor.domain.policy.RoomBanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态标签文案的单测（本次需求：**封禁中的主播要显示「封禁中」，而不是「待确认」**）。
 *
 * 这里逐字锁定两件事，缺一不可：
 *  1. 封禁 ⇒ 「封禁中」，**优先于** `confirmedLiveStatus`（它仍是 UNKNOWN —— 封禁独立于确认状态）；
 *  2. 未封禁 ⇒ 四条既有文案**与改动前逐字相同**（防止"顺手改了别的路径"这种回归）。
 *
 * 通过 [labelOf] 而不是直接调 [streamerStatusLabel] 的原因：生产侧的形状是
 * "`streamer.lastError` 文本 → [RoomBanCodec.isBanned] → 布尔 → 文案"（见 HomeViewModel），
 * 用同一个形状断言，`lastError` 是坏 JSON / 无前缀 / null 时**不算封禁**这条契约才真的被覆盖到。
 */
class StreamerStatusLabelTest {

    /** 改动前首页 / 详情页那份 `when (status)` 的**原文**（本次不得改动其中任何一个字）。 */
    private val legacyLabels = mapOf(
        ConfirmedLiveStatus.LIVE to "直播中",
        ConfirmedLiveStatus.ROUND to "轮播中",
        ConfirmedLiveStatus.OFFLINE to "未开播",
        ConfirmedLiveStatus.UNKNOWN to "待确认"
    )

    private val now = 1_800_000_000_000L

    // ---- 1) 本次需求：封禁 ⇒ 封禁中 ----

    @Test
    fun `封禁中的主播_确认状态仍是 UNKNOWN 也显示封禁中`() {
        assertEquals(
            "封禁主播的 confirmedLiveStatus 恒为 UNKNOWN（引擎跳过了常规检查），" +
                "此时不得显示「待确认」",
            "封禁中",
            streamerStatusLabel(ConfirmedLiveStatus.UNKNOWN, banned = true)
        )
    }

    @Test
    fun `封禁优先_任何已确认状态下一律显示封禁中`() {
        ConfirmedLiveStatus.entries.forEach { status ->
            assertEquals(
                "封禁优先于 ${status.name}：封禁标记在就必须显示「封禁中」",
                "封禁中",
                streamerStatusLabel(status, banned = true)
            )
        }
    }

    @Test
    fun `封禁中的文案与四种既有文案都不同_界面上才能区分`() {
        val banned = streamerStatusLabel(ConfirmedLiveStatus.UNKNOWN, banned = true)
        legacyLabels.values.forEach { legacy ->
            assertNotEquals(
                "「封禁中」与既有文案「$legacy」相同的话，用户根本看不出这位主播被封了",
                legacy,
                banned
            )
        }
    }

    // ---- 2) 未封禁：与改动前逐字一致 ----

    @Test
    fun `未封禁时四条文案逐字不变`() {
        // 枚举完整性：将来新增一种确认状态却忘了定文案时，这条断言先失败，
        // 而不是让新状态悄悄落进 when 的某个分支。
        assertEquals(
            "新增了 ConfirmedLiveStatus 取值，本条用例必须同时补上它的文案",
            ConfirmedLiveStatus.entries.size,
            legacyLabels.size
        )
        ConfirmedLiveStatus.entries.forEach { status ->
            assertEquals(
                "未封禁的 ${status.name} 文案必须与改动前逐字一致",
                legacyLabels.getValue(status),
                streamerStatusLabel(status, banned = false)
            )
        }
    }

    @Test
    fun `未封禁的 UNKNOWN 仍是待确认`() {
        assertEquals("待确认", streamerStatusLabel(ConfirmedLiveStatus.UNKNOWN, banned = false))
        // 与生产侧同一条路径：lastError 为 null（从没失败过）= 未封禁
        assertEquals("待确认", labelOf(ConfirmedLiveStatus.UNKNOWN, null, ObservationResult.INVALID))
    }

    @Test
    fun `lastObservationResult 不参与文案`() {
        // 改动前 UNKNOWN 一律「待确认」，不管那次观察是 INVALID 还是 SUCCESS；
        // 本次也不许把观察结果拉进判定 —— 否则"未封禁时逐字一致"就不成立了。
        ObservationResult.entries.forEach { observation ->
            assertEquals(
                "观察结果 ${observation.name} 不得改变状态文案",
                "待确认",
                labelOf(ConfirmedLiveStatus.UNKNOWN, null, observation)
            )
        }
    }

    // ---- 3) 封禁标记的来源契约：解不出来就是没封禁 ----

    @Test
    fun `lastError 不是封禁标记时不算封禁`() {
        val notBanned = listOf(
            null,                                              // 从没写过错误
            "",                                                // 空串
            "网络超时：连接失败",                                  // 普通错误文本（无前缀）
            "ROOM_BANNED ",                                    // 只有前缀、没有 JSON
            "ROOM_BANNED {不是 JSON",                            // 前缀 + 坏 JSON
            "ROOM_BANNED {\"lockedAt\":1}",                     // 前缀 + 合法 JSON 但缺字段
            "room_banned {\"lockedAt\":1}",                     // 前缀大小写不符
            "ROOM_BANNED{\"lockedAt\":1}"                      // 前缀少了那个空格
        )
        notBanned.forEach { raw ->
            assertFalse(
                "「${raw}」不是封禁标记，不得判成封禁（宁可少算也不虚报）",
                RoomBanCodec.isBanned(raw)
            )
            assertEquals(
                "「${raw}」下必须仍显示改动前的「待确认」",
                "待确认",
                labelOf(ConfirmedLiveStatus.UNKNOWN, raw, ObservationResult.INVALID)
            )
        }
    }

    @Test
    fun `合法的封禁标记才算封禁_正向对照`() {
        // 反向对照：没有这条，把 isBanned 写成恒 false 也能让上面那条用例通过。
        val raw = RoomBanCodec.encode(
            RoomBanState(
                lockedAtWall = now,
                lastProbeAtWall = now,
                nextCheckAtWall = now + 1_800_000L,
                lockTill = -1L,
                isHidden = false,
                apiCode = 0,
                apiMessage = "success"
            )
        )
        assertTrue("encode 出来的标记必须能解回封禁", RoomBanCodec.isBanned(raw))
        assertEquals("封禁中", labelOf(ConfirmedLiveStatus.UNKNOWN, raw, ObservationResult.SUCCESS))
        // 已确认在播的主播被封禁，显示的同样是「封禁中」（封禁优先）
        assertEquals("封禁中", labelOf(ConfirmedLiveStatus.LIVE, raw, ObservationResult.INVALID))
    }

    /**
     * 与生产调用**同一形状**的取值：`HomeViewModel` 先把 `streamer.lastError` 解成布尔
     * （[RoomBanCodec.isBanned]），再把状态与布尔一起交给 [streamerStatusLabel]。
     *
     * [observation] 在实现里**刻意不用**，也没有传给 [streamerStatusLabel] ——
     * 这正是"`lastObservationResult` 不参与状态文案"这条约定的表达方式：
     * 改动前它就不参与，本次也不许把它拉进来。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun labelOf(
        status: ConfirmedLiveStatus,
        lastError: String?,
        observation: ObservationResult
    ): String = streamerStatusLabel(status, banned = RoomBanCodec.isBanned(lastError))
}
