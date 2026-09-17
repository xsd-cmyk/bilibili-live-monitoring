package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 一轮 Tick **汇总级**错误码的选择单测（收口项：不再无条件写死 `API_REJECTED`）。
 *
 * ## 为什么必须有单测
 * 修复前这条汇总日志的 `errorCode` 恒为 `AppError.API_REJECTED`（"接口被拒绝"），
 * 而同一行的 detail 里写着 `原因集合=[TIMEOUT]` —— 用户按"接口被拒绝"去查，
 * 方向从第一步就是错的。选码规则现在是一个纯函数，这里把每条规则逐字钉死：
 * 尤其在混合原因下"选谁代表本轮"这件事，只有单测能证明它不会被悄悄改回去。
 */
class RoundFailureCodeTest {

    @Test
    fun `单一原因直接用它的错误码（不再写死 API_REJECTED）`() {
        assertEquals(
            AppError.NETWORK_TIMEOUT,
            RoundFailureCode.appErrorOf(setOf(RoomFailureReason.TIMEOUT))
        )
        assertEquals(
            AppError.API_REJECTED,
            RoundFailureCode.appErrorOf(setOf(RoomFailureReason.API_REJECTED))
        )
        assertEquals(
            AppError.API_INVALID_RESPONSE,
            RoundFailureCode.appErrorOf(setOf(RoomFailureReason.MALFORMED_RESPONSE))
        )
        assertEquals(
            AppError.API_INVALID_RESPONSE,
            RoundFailureCode.appErrorOf(setOf(RoomFailureReason.MISSING_IN_RESPONSE))
        )
        assertEquals(
            AppError.NETWORK_UNAVAILABLE,
            RoundFailureCode.appErrorOf(setOf(RoomFailureReason.NETWORK))
        )
        // ★ 限流现在有**自己的**错误码（缺陷 2）：它曾被按"网络不可用"记录，
        //   于是"服务端让我们退避"被写成了"网络断了"。原始信号（HTTP 429 / Retry-After）
        //   照旧完整写进 detail（见 RoomFailureMapping）。
        assertEquals(
            AppError.RATE_LIMITED,
            RoundFailureCode.appErrorOf(setOf(RoomFailureReason.RATE_LIMITED))
        )
        assertEquals(
            AppError.NETWORK_UNAVAILABLE,
            RoundFailureCode.appErrorOf(setOf(RoomFailureReason.SERVER_ERROR))
        )
    }

    @Test
    fun `原因集合为空时给 UNKNOWN 而不是猜一个`() {
        assertNull(RoundFailureCode.dominantReason(emptySet()))
        assertEquals(AppError.UNKNOWN, RoundFailureCode.appErrorOf(emptySet()))
    }

    @Test
    fun `混合原因取更具体的那个`() {
        // 超时 + 服务端拒绝 ⇒ 超时（机制明确、可区分）
        assertEquals(
            AppError.NETWORK_TIMEOUT,
            RoundFailureCode.appErrorOf(
                setOf(RoomFailureReason.TIMEOUT, RoomFailureReason.API_REJECTED)
            )
        )
        // 解析失败 + 服务端拒绝 ⇒ 响应不可用
        assertEquals(
            AppError.API_INVALID_RESPONSE,
            RoundFailureCode.appErrorOf(
                setOf(RoomFailureReason.API_REJECTED, RoomFailureReason.MALFORMED_RESPONSE)
            )
        )
        // 超时 + 解析失败 ⇒ 超时（TIMEOUT 优先，且两者都比兜底的 NETWORK 具体）
        assertEquals(
            AppError.NETWORK_TIMEOUT,
            RoundFailureCode.appErrorOf(
                setOf(RoomFailureReason.MALFORMED_RESPONSE, RoomFailureReason.TIMEOUT)
            )
        )
    }

    @Test
    fun `兜底的 NETWORK 永远不压过任何具体原因`() {
        assertEquals(
            AppError.API_REJECTED,
            RoundFailureCode.appErrorOf(
                setOf(RoomFailureReason.NETWORK, RoomFailureReason.API_REJECTED)
            )
        )
        assertEquals(
            AppError.API_INVALID_RESPONSE,
            RoundFailureCode.appErrorOf(
                setOf(RoomFailureReason.NETWORK, RoomFailureReason.MISSING_IN_RESPONSE)
            )
        )
        assertEquals(
            AppError.NETWORK_TIMEOUT,
            RoundFailureCode.appErrorOf(
                setOf(RoomFailureReason.NETWORK, RoomFailureReason.TIMEOUT)
            )
        )
    }

    @Test
    fun `代表原因一定来自传入的集合本身`() {
        val reasons = setOf(RoomFailureReason.RATE_LIMITED, RoomFailureReason.SERVER_ERROR)
        val dominant = RoundFailureCode.dominantReason(reasons)
        assertEquals(true, dominant in reasons)
        // 限流有独立错误码之后不再是"网络族"：这两个原因选谁代表本轮，最终错误码不同
        // （顺序按 RoundFailureCode 的 PRIORITY：RATE_LIMITED 比 SERVER_ERROR 具体）。
        assertEquals(RoomFailureReason.RATE_LIMITED, dominant)
        assertEquals(
            AppError.RATE_LIMITED,
            RoundFailureCode.appErrorOf(reasons)
        )
    }

    @Test
    fun `每一种失败原因都能映射出错误码（不存在无法映射的取值）`() {
        // 与 RoomFailureMapping.appErrorOf 同源：枚举新增取值时这里会立刻暴露漏配
        for (reason in RoomFailureReason.entries) {
            assertEquals(
                "reason=$reason 的映射与单原因路径不一致",
                RoomFailureMapping.appErrorOf(reason),
                RoundFailureCode.appErrorOf(setOf(reason))
            )
        }
    }
}
