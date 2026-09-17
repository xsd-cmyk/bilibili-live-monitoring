package com.example.bilimonitor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 添加主播的输入解析单测（用户定稿：纯数字的识别方式改为可选，默认按房间号）。
 *
 * 背景：房间号与 UID 都是纯数字，字面上无法区分。原实现默认按 UID 处理，
 * 导致"输入房间号"这条最常见路径必然失败；且 `toLong()` 对超长数字会抛异常。
 */
class ParseStreamerInputTest {

    @Test
    fun `纯数字默认按房间号解析`() {
        assertEquals(
            ParsedInput.RoomId(22637261L),
            StreamerInputParser.parse("22637261", InputMode.ROOM)
        )
    }

    @Test
    fun `默认模式是房间号`() {
        // 不传 mode 时必须按房间号（默认参数值即产品决策本身）
        assertEquals(
            ParsedInput.RoomId(22637261L),
            StreamerInputParser.parse("22637261")
        )
        assertEquals("房间号", InputMode.ROOM.label)
        assertEquals(InputMode.ROOM, InputMode.entries.first())
    }

    @Test
    fun `纯数字在 UID 模式下按 UID 解析`() {
        assertEquals(
            ParsedInput.Uid(672328094L),
            StreamerInputParser.parse("672328094", InputMode.UID)
        )
    }

    @Test
    fun `直播间链接始终按房间号解析`() {
        assertEquals(
            ParsedInput.RoomId(22637261L),
            StreamerInputParser.parse("https://live.bilibili.com/22637261", InputMode.UID)
        )
    }

    @Test
    fun `个人空间链接始终按 UID 解析`() {
        assertEquals(
            ParsedInput.Uid(672328094L),
            StreamerInputParser.parse("https://space.bilibili.com/672328094", InputMode.ROOM)
        )
    }

    @Test
    fun `链接带查询串时仍能解析`() {
        assertEquals(
            ParsedInput.RoomId(22637261L),
            StreamerInputParser.parse("https://live.bilibili.com/22637261?visit_id=abc", InputMode.ROOM)
        )
    }

    @Test
    fun `超长数字串不再抛异常而是判定为无法识别`() {
        // 原实现用 toLong()，这个输入会抛 NumberFormatException
        assertNull(StreamerInputParser.parse("999999999999999999999999", InputMode.ROOM))
        assertNull(StreamerInputParser.parse("999999999999999999999999", InputMode.UID))
    }

    @Test
    fun `零与空输入被拒绝`() {
        assertNull(StreamerInputParser.parse("0", InputMode.ROOM))
        assertNull(StreamerInputParser.parse("", InputMode.ROOM))
        assertNull(StreamerInputParser.parse("   ", InputMode.ROOM))
    }

    @Test
    fun `无关链接与乱码被拒绝`() {
        assertNull(StreamerInputParser.parse("https://www.bilibili.com/video/BV1xx", InputMode.ROOM))
        assertNull(StreamerInputParser.parse("abc", InputMode.ROOM))
        assertNull(StreamerInputParser.parse("https://live.bilibili.com/h5/123", InputMode.ROOM))
    }

    @Test
    fun `首尾空白被忽略`() {
        assertEquals(
            ParsedInput.RoomId(22637261L),
            StreamerInputParser.parse("  22637261  ", InputMode.ROOM)
        )
    }
}
