package com.example.bilimonitor.core

/**
 * eventSequence（进而 transitionId）的**唯一**推进规则。
 *
 * ## 修的是什么（阻断级缺陷，不可自愈）
 * `transitionId` 的生成规则是确定性的（0.6.16.1）：
 * `"{streamerStableId}:{eventSequence}:{from}->{to}"` —— 没有随机分量、也没有时间分量。
 * 而 `status_history` 上有唯一索引 `idx_status_history_transition(streamerId, transitionId)`，
 * 且插入用的是 `OnConflictStrategy.ABORT`。
 *
 * 原先序号只来自 `live_event`：`SELECT COALESCE(MAX(eventSequence), 0) + 1 ...`。
 * 问题在于两张表的**生命周期不同**：
 *  - `live_event` 行**会被删除** —— 用户删除"最近一条已结束场次"时连同事件一起删
 *    （`HistoryRepository` 删场次 → `LiveEventDao.deleteBySession`），
 *    容量清理也会删（`MaintenanceRepository` → `deleteById`）；
 *  - `status_history` **全仓只有 insert / select，没有任何 DELETE**。
 *
 * 于是"删掉最近一条已结束场次"之后 `MAX(eventSequence)` **回退**，下一次转换重新拿到
 * 同一个 `eventSequence`；若 `from`/`to` 也相同（例如 `OFFLINE->LIVE`），
 * 新行与历史行的 transitionId **逐字相同** ⇒ 唯一索引冲突 ⇒ **整个转换事务回滚**
 * （确认状态、场次、事件、通知全部丢弃）；下一 Tick 再次检测到同一转换、再次冲突 ——
 * 该主播**永久不再产生任何状态转换/场次/事件/通知**，并且每个 Tick 写一条**无抑制**的错误。
 *
 * ## 规则
 * `next = max(live_event 的 MAX, status_history 里解析出的 MAX(seq), status_history 行数) + 1`
 *
 * 三项缺一不可，且**取 max 使任何一路输入异常都只会让结果偏大，绝不偏小**：
 *  ① `liveEventMaxSequence` —— 与旧行为一致，保证事件表里已经用过的序号不会被重复发放。
 *  ② `statusHistoryMaxSequence` —— 直接取"历史上真正发放过的最大序号"这一高水位。
 *     该表**从不删除**，所以这个高水位只增不减，这正是修复"删事件导致序号回退"的关键项。
 *  ③ `statusHistoryRowCount` —— **完全不依赖任何字符串解析**的兜底上界：
 *     每次成功转换恰好写 1 行 `status_history`，而第 i 次转换拿到的序号 `S_i <= i`
 *     （归纳：写入时 `live_event` 里的序号都来自更早的转换 `S_j <= j < i`，故 `MAX <= i-1`），
 *     所以行数永远 ≥ 历史上发放过的任何序号。
 *
 * ## 为什么不改 transitionId 的格式（加随机/时间分量）
 * transitionId 同时是**去重键**（`status_history` 与 `live_event` 两个唯一索引都建在它上面），
 * 加随机分量等于把去重语义直接废掉；而且它**没有治根** —— `eventSequence` 本身仍会回退，
 * 与 `status_history` 里的旧行对不上，`live_event.eventSequence` 的排序/审计意义也会失真。
 *
 * ## 为什么不加列/加表（不迁移）
 * 高水位完全可以由**已经存在且从不删除的** `status_history` 推导出来（上面第 ②③ 项），
 * 因此不需要新增"序号高水位"列或表，也就不需要动 `DB_VERSION` 与迁移脚本。
 *
 * ## 为什么不做"冲突时换号重试"
 * 冲突发生在 `MonitorRepository.applyConfirmedObservationAtomically` 的
 * `db.withTransaction` 内部，在那里加重试需要改该文件（本批次由其他代理负责）；
 * 更重要的是"先发一个必定冲突的号、再补救"本身就不如"一开始就不发同一个号"。
 *
 * 纯函数：不依赖 Android / Room / 时钟 / 随机数，可直接在 JVM 单测里锁定行为
 * （见 `EventSequencesTest`）。
 */
object EventSequences {

    /**
     * 计算下一个可安全使用的 eventSequence（保证 ≥ 1）。
     *
     * @param liveEventMaxSequence `live_event` 里该主播的最大 eventSequence（无行时 0）
     * @param statusHistoryMaxSequence `status_history.transitionId` 里解析出的最大 seq（无行/解析不出时 0）
     * @param statusHistoryRowCount `status_history` 里该主播的行数（只增不减的兜底上界）
     */
    fun nextEventSequence(
        liveEventMaxSequence: Long,
        statusHistoryMaxSequence: Long,
        statusHistoryRowCount: Long
    ): Long {
        // coerceAtLeast(0)：输入理论上不可能为负，但负数会让 max 失去意义（并可能把序号压回 1），
        // 这里统一夹到 0，保证"只增不减"这条不变量在任何输入下都成立。
        val highWaterMark = maxOf(
            liveEventMaxSequence.coerceAtLeast(0L),
            statusHistoryMaxSequence.coerceAtLeast(0L),
            statusHistoryRowCount.coerceAtLeast(0L)
        )
        return highWaterMark + 1L
    }
}
