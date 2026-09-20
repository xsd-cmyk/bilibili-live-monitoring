package com.example.bilimonitor.domain.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知合并阈值的判定规则。
 *
 * ## 为什么这份测试必须存在
 * 这条规则被用户纠正过一次：原实现把阈值当成"一条通知里装多少位"，
 * 于是"阈值 4 + 10 位主播在同一个 5 秒窗口内开播"会发出 **3 条**通知（4＋4＋2），
 * 而用户要的是 **1 条含全部 10 位**的通知。
 * 现在的语义是「**超过**阈值 → 把这一批**全部**合并成一条」，
 * 边界（正好等于阈值时不合并、阈值 1 表示"2 位以上就合并"）全部钉在这里。
 */
class NotificationAggregationPolicyTest {

    @Test
    fun `超过阈值才合并_正好等于阈值不合并`() {
        assertFalse("5 位、阈值 5：正好等于阈值 → 逐条单独发", NotificationAggregationPolicy.shouldMergeAll(5, 5))
        assertTrue("6 位、阈值 5：超过阈值 → 合并成一条", NotificationAggregationPolicy.shouldMergeAll(6, 5))

        assertFalse("4 位、阈值 4 → 不合并", NotificationAggregationPolicy.shouldMergeAll(4, 4))
        assertTrue("5 位、阈值 4 → 合并", NotificationAggregationPolicy.shouldMergeAll(5, 4))

        assertFalse("1 位、阈值 1 → 不合并", NotificationAggregationPolicy.shouldMergeAll(1, 1))
        assertTrue("2 位、阈值 1 → 合并（阈值 1 的含义就是「超过 1 位」）",
            NotificationAggregationPolicy.shouldMergeAll(2, 1))
    }

    @Test
    fun `一位主播永远不需要合并`() {
        for (threshold in 1..20) {
            assertFalse(
                "只有 1 位主播时不该走合并（阈值 $threshold）",
                NotificationAggregationPolicy.shouldMergeAll(1, threshold)
            )
        }
        assertFalse("一位都没有时也不该合并", NotificationAggregationPolicy.shouldMergeAll(0, 1))
    }

    @Test
    fun `阈值是触发条件_不是每条通知的数量上限`() {
        // 10 位主播、阈值 4：结果必须是"合并"，而且合并进去的是**全部 10 位** ——
        // 这正是用户要的语义（旧实现会切成 4＋4＋2 三条通知）。
        assertTrue(
            "阈值 4 下 10 位主播应当合并成一条（而不是分成多个批次）",
            NotificationAggregationPolicy.shouldMergeAll(10, 4)
        )
        // 这条断言是"语义说明"性质的：判定只看是否超过，没有任何上界截断。
        assertTrue("再来多少位都仍然只判断「超过」", NotificationAggregationPolicy.shouldMergeAll(99, 4))
    }

    @Test
    fun `聚合窗口在阈值 1 时也必须启用`() {
        // ★ 原实现的门控是 threshold > 1：阈值 1 会被静默当成"完全不聚合"，
        //   与"超过 1 位就合并"自相矛盾。配置校验允许 1，所以门控必须接受 1。
        assertTrue("阈值 1 合法（= 超过 1 位就合并）",
            NotificationAggregationPolicy.usesAggregationWindow(enabled = true, threshold = 1))
        assertTrue("阈值 4 合法",
            NotificationAggregationPolicy.usesAggregationWindow(enabled = true, threshold = 4))
    }

    @Test
    fun `关掉开关或非法阈值时不使用聚合窗口`() {
        assertFalse("用户关了聚合开关",
            NotificationAggregationPolicy.usesAggregationWindow(enabled = false, threshold = 4))
        assertFalse("阈值 0 非法（配置校验会拦），不能进聚合路径",
            NotificationAggregationPolicy.usesAggregationWindow(enabled = true, threshold = 0))
        assertFalse("负阈值同样不合法",
            NotificationAggregationPolicy.usesAggregationWindow(enabled = true, threshold = -1))
    }
}
