package com.example.bilimonitor.data.local.convert

import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.entity.LiveSessionLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 枚举列读路径的容错（台账 H19-2.2）。
 *
 * 为什么要专门为"读一个不认识的字符串"写测试：本项目真实踩过一次 ——
 * 手工把 `lifecycleState` 写成 `'CLOSED'`（合法值只有 ACTIVE/ABANDONED），
 * 裸 `valueOf` 在 Room 读取那一行时抛异常，应用进入"启动即崩、反复重启都崩"的死循环，
 * 用户唯一的自救手段是清除应用数据。
 *
 * 现在的约定：认不出 → 回退到该枚举里语义最保守的取值；只有 null 才返回 null。
 * （`android.util.Log` 在 JVM 单测里因为 `isReturnDefaultValues = true` 返回默认值，
 *  不会抛 "not mocked"，所以这里可以直接调真实实现。）
 */
class EnumSafeTest {

    @Test
    fun `known value parses to the enum constant`() {
        assertEquals(
            ConfirmedLiveStatus.LIVE,
            EnumSafe.parse(ConfirmedLiveStatus.values(), "LIVE", ConfirmedLiveStatus.UNKNOWN)
        )
        assertEquals(
            LiveSessionLifecycleState.ACTIVE,
            EnumSafe.parse(
                LiveSessionLifecycleState.values(), "ACTIVE",
                LiveSessionLifecycleState.ABANDONED
            )
        )
    }

    @Test
    fun `unknown value falls back instead of throwing`() {
        // 这一条就是当年把应用打死的那种数据
        assertEquals(
            LiveSessionLifecycleState.ABANDONED,
            EnumSafe.parse(
                LiveSessionLifecycleState.values(), "CLOSED",
                LiveSessionLifecycleState.ABANDONED
            )
        )
        assertEquals(
            ConfirmedLiveStatus.UNKNOWN,
            EnumSafe.parse(ConfirmedLiveStatus.values(), "BANNED", ConfirmedLiveStatus.UNKNOWN)
        )
    }

    @Test
    fun `null stays null so nullable columns keep working`() {
        assertNull(EnumSafe.parse(ConfirmedLiveStatus.values(), null, ConfirmedLiveStatus.UNKNOWN))
    }

    @Test
    fun `value matching is case sensitive like the DDL literal`() {
        // DDL 里的字面量是大写；小写属于"不认识的写入"，必须走回退而不是"尽量匹配"
        assertEquals(
            ConfirmedLiveStatus.UNKNOWN,
            EnumSafe.parse(ConfirmedLiveStatus.values(), "live", ConfirmedLiveStatus.UNKNOWN)
        )
    }
}
