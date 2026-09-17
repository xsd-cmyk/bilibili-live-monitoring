package com.example.bilimonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定"删掉最近一条已结束场次之后，下一次转换仍能得到**未被占用**的 eventSequence / transitionId"。
 *
 * 背景（阻断级缺陷）：`transitionId = "{stableId}:{seq}:{from}->{to}"` 是确定性的，
 * `status_history` 上有唯一索引 `idx_status_history_transition(streamerId, transitionId)` 且插入用
 * `OnConflictStrategy.ABORT`；而序号原先只来自 `live_event` 的 `MAX(eventSequence) + 1`，
 * 这张表的行却**会被删除**（删场次 / 容量清理），`status_history` 则**只增不减**。
 * 于是"删掉最近一条已结束场次"会让序号回退，下一次转换复用一个已被 `status_history`
 * 占用的 transitionId ⇒ 整个转换事务回滚、该主播永久不再产生任何转换。
 *
 * 本测试用 [FakeLiveEventDao] 在内存里重放 `LiveEventDao` 的三路查询与 `status_history`
 * 的唯一约束；被测对象是纯函数 [EventSequences.nextEventSequence]。
 */
class EventSequencesTest {

    // ------------------------------------------------------------ 纯函数本身

    @Test
    fun `空库第一次转换得到序号1`() {
        assertEquals(1L, EventSequences.nextEventSequence(0L, 0L, 0L))
    }

    @Test
    fun `取三路高水位的最大值再加一`() {
        // live_event 的 MAX 最大
        assertEquals(3L, EventSequences.nextEventSequence(2L, 1L, 1L))
        // status_history 解析出的最大 seq 最大（= 删事件后的真实高水位）
        assertEquals(8L, EventSequences.nextEventSequence(2L, 7L, 4L))
        // status_history 行数最大（解析失效时的兜底）
        assertEquals(10L, EventSequences.nextEventSequence(1L, 2L, 9L))
        // 备份恢复可能把 live_event 的序号抬得很高 —— 取 max 后仍然安全
        assertEquals(101L, EventSequences.nextEventSequence(100L, 0L, 0L))
    }

    @Test
    fun `解析失效时行数兜底仍能保证序号不回退`() {
        // statusHistoryMaxSequence = 0 模拟"transitionId 里解析不出序号"
        assertEquals(6L, EventSequences.nextEventSequence(0L, 0L, 5L))
    }

    @Test
    fun `负数输入被夹到0 不会把序号压回去`() {
        assertEquals(1L, EventSequences.nextEventSequence(-5L, -5L, -5L))
        assertEquals(4L, EventSequences.nextEventSequence(-5L, 3L, -5L))
    }

    // ------------------------------------------- transitionId 解析（与 DAO 的 SQL 同语义）

    @Test
    fun `transitionId 解析与 DAO 的 SQL 语义一致`() {
        val stable = "str-3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        assertEquals(42L, parseSequenceLikeSql("$stable:42:OFFLINE->LIVE"))
        assertEquals(1L, parseSequenceLikeSql("$stable:1:ROUND->OFFLINE"))
        // 前缀里含数字（UUID 就含数字与连字符）：不得被误当成序号
        assertEquals(7L, parseSequenceLikeSql("$stable:7:LIVE->UNKNOWN"))
        // 手工改过的备份（前缀里带冒号）⇒ 解析降级为 0，而不是算出一个"看起来合理"的错值
        assertEquals(0L, parseSequenceLikeSql("bad:prefix:7:OFFLINE->LIVE"))
        // 格式不符时一律 0，绝不抛异常
        assertEquals(0L, parseSequenceLikeSql(""))
        assertEquals(0L, parseSequenceLikeSql("not-a-transition-id"))
    }

    // -------------------------------------------------------------- 验收用例（缺陷 1 复现）

    @Test
    fun `删掉最近一条已结束场次后 下一次转换仍得到未被占用的 transitionId`() {
        val dao = FakeLiveEventDao()
        val stable = STREAMER_STABLE_ID

        // 更早的两次转换（seq1 开播 / seq2 下播），事件行都还在
        dao.transition(stable, "OFFLINE", "LIVE", writesEvent = true)
        dao.transition(stable, "LIVE", "OFFLINE", writesEvent = true)
        // 流 A：开播（seq3，START 事件）+ 下播（seq4，END 事件）
        dao.transition(stable, "OFFLINE", "LIVE", writesEvent = true)
        dao.transition(stable, "LIVE", "OFFLINE", writesEvent = true)
        assertEquals(listOf(1L, 2L, 3L, 4L), dao.liveEvents.toList())

        // 用户在历史页删掉"流 A"：live_event 的 3/4 被删，status_history 一行都不动
        dao.deleteEvents(listOf(3L, 4L))
        assertEquals(2L, dao.liveEvents.max())
        assertEquals(4, dao.statusHistory.size)

        // 旧规则（只有 live_event 的 MAX + 1）此刻会重新发出 seq3，
        // 与历史行 "$stable:3:OFFLINE->LIVE" 逐字相同 ⇒ 唯一索引冲突 ⇒ 转换事务整滚。
        // 这条断言就是"缺陷确实被复现"的证据；它若失败，说明本用例没有复现缺陷。
        val oldRuleSequence = (dao.liveEvents.maxOrNull() ?: 0L) + 1L
        assertEquals(3L, oldRuleSequence)
        assertTrue(
            "旧规则确实会复用已被占用的 transitionId",
            dao.statusHistory.contains("$stable:$oldRuleSequence:OFFLINE->LIVE")
        )

        // 新规则：取到的号必须未被占用（transition() 内部按唯一索引 ABORT 的语义断言）
        val newTransitionId = dao.transition(stable, "OFFLINE", "LIVE", writesEvent = true)
        assertNotEquals("$stable:3:OFFLINE->LIVE", newTransitionId)
        assertEquals("$stable:5:OFFLINE->LIVE", newTransitionId)
        assertEquals(1, dao.statusHistory.count { it == newTransitionId })
    }

    @Test
    fun `连续删除场次也不会让序号回退`() {
        val dao = FakeLiveEventDao()
        val stable = STREAMER_STABLE_ID
        val issued = mutableListOf<Long>()

        repeat(6) { round ->
            issued += parseSequenceLikeSql(dao.transition(stable, "OFFLINE", "LIVE", writesEvent = true))
            issued += parseSequenceLikeSql(dao.transition(stable, "LIVE", "OFFLINE", writesEvent = true))
            // 每一轮都把刚产生的事件全部删掉（比"只删最近一条"更极端）
            dao.deleteEvents(dao.liveEvents.toList())
            assertEquals("第 $round 轮删除后 live_event 应为空", 0, dao.liveEvents.size)
        }

        // 序号严格递增（status_history 从不删除 ⇒ 高水位只增不减）
        assertEquals(issued.sorted(), issued)
        assertEquals(issued.distinct().size, issued.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L), issued)
        assertEquals(12, dao.statusHistory.size)
        assertEquals(dao.statusHistory.size, dao.statusHistory.distinct().size)
    }

    @Test
    fun `任意转换与删除交错时 transitionId 永不重复且序号严格递增`() {
        val dao = FakeLiveEventDao()
        val stable = STREAMER_STABLE_ID
        val seenIds = mutableSetOf<String>()
        val issued = mutableListOf<Long>()
        // 固定种子：一旦失败可以原样复现
        val rnd = java.util.Random(20240607L)
        val states = listOf("OFFLINE", "LIVE", "ROUND")
        var from = "OFFLINE"

        repeat(300) { round ->
            val to = states[rnd.nextInt(states.size)]
            if (to != from) {
                val transitionId = dao.transition(stable, from, to, writesEvent = rnd.nextBoolean())
                val sequence = parseSequenceLikeSql(transitionId)
                if (issued.isNotEmpty()) {
                    assertTrue(
                        "第 $round 轮：序号必须严格递增（上次 ${issued.last()}，本次 $sequence）",
                        sequence > issued.last()
                    )
                }
                assertTrue("第 $round 轮 transitionId 重复：$transitionId", seenIds.add(transitionId))
                issued += sequence
                from = to
            }
            // 随机删事件行（删场次 / 容量清理），status_history 保持不删
            if (rnd.nextInt(3) == 0 && dao.liveEvents.isNotEmpty()) {
                dao.deleteEvents(listOf(dao.liveEvents[rnd.nextInt(dao.liveEvents.size)]))
            }
        }

        assertTrue("样本量不足，用例没有意义：${issued.size}", issued.size > 100)
        assertEquals(dao.statusHistory.size, dao.statusHistory.distinct().size)
    }

    // -------------------------------------------------------------------- 测试替身

    /**
     * `LiveEventDao` 三路查询的**内存替身**：
     *  - `statusHistory`：transitionId 列表，**从不删除**（与真实表一致）；
     *  - `liveEvents`：eventSequence 列表，删场次 / 容量清理时会被删掉。
     *
     * `transition()` 在写入前按"唯一索引 ABORT"的语义断言 transitionId 未被占用 ——
     * 那正是生产环境里让整个转换事务回滚的约束。
     */
    private class FakeLiveEventDao {
        val statusHistory = mutableListOf<String>()
        val liveEvents = mutableListOf<Long>()

        fun nextEventSequence(): Long = EventSequences.nextEventSequence(
            liveEventMaxSequence = liveEvents.maxOrNull() ?: 0L,
            statusHistoryMaxSequence = statusHistory.maxOfOrNull { parseSequenceLikeSql(it) } ?: 0L,
            statusHistoryRowCount = statusHistory.size.toLong()
        )

        /** 取号 → 写 `status_history`（唯一约束）→ 视情况写一行 `live_event`；返回 transitionId。 */
        fun transition(stableId: String, from: String, to: String, writesEvent: Boolean): String {
            val sequence = nextEventSequence()
            // 与 Ids.transitionId 完全相同的格式
            val transitionId = "$stableId:$sequence:$from->$to"
            assertFalse(
                "transitionId 撞上 status_history 的唯一索引（idx_status_history_transition）：$transitionId",
                statusHistory.contains(transitionId)
            )
            statusHistory += transitionId
            if (writesEvent) liveEvents += sequence
            return transitionId
        }

        /** 等价于 `LiveEventDao.deleteBySession`：只删事件行，不碰 status_history。 */
        fun deleteEvents(sequences: List<Long>) {
            liveEvents.removeAll(sequences)
        }
    }

    private companion object {
        const val STREAMER_STABLE_ID = "str-0f0f0f0f-1111-2222-3333-444444444444"
    }
}

/**
 * DAO 里那条 SQL 的等价实现（仅供单测建模；生产路径在 SQL 里）：
 * `CAST(substr(transitionId, instr(transitionId, ':') + 1) AS INTEGER)` ——
 * 取第一个冒号之后的**最长数字前缀**（遇到下一个 `:` 停止），取不到就是 0。
 */
private fun parseSequenceLikeSql(transitionId: String): Long {
    val afterFirstColon = transitionId.substringAfter(':', missingDelimiterValue = "")
    val digits = afterFirstColon.takeWhile { it.isDigit() }
    return digits.toLongOrNull() ?: 0L
}
