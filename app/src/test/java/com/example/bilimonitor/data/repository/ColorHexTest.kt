package com.example.bilimonitor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 颜色代码解析（用户要求：可以用颜色代码填主题色）。
 *
 * 用户手上的色值来源五花八门，所以三种长度都要认；同时**必须能区分**"没填"（= 用默认色）
 * 与"填错了"（= 提示格式错误）—— 这两者混在一起会让用户以为自己的输入生效了。
 */
class ColorHexTest {

    @Test
    fun `six digit hex with hash`() {
        assertEquals(0xFFFB7299.toInt(), ColorHex.parse("#FB7299"))
    }

    @Test
    fun `hash is optional and case insensitive`() {
        assertEquals(ColorHex.parse("#fb7299"), ColorHex.parse("FB7299"))
        assertEquals(ColorHex.parse("#FB7299"), ColorHex.parse("fb7299"))
    }

    @Test
    fun `three digit shorthand expands each nibble`() {
        // #F09 → FF 00 99
        assertEquals(0xFFFF0099.toInt(), ColorHex.parse("#F09"))
    }

    @Test
    fun `eight digit form keeps the alpha channel`() {
        assertEquals(0x80FB7299.toInt(), ColorHex.parse("#80FB7299"))
    }

    @Test
    fun `0x prefix is accepted`() {
        // 从设计工具/代码里复制出来的值常带 0x 前缀
        assertEquals(0xFFFB7299.toInt(), ColorHex.parse("0xFB7299"))
        // 位数不对的（这里 7 位）仍然算无效，不能被"去掉前缀"蒙混过去
        assertNull(ColorHex.parse("0xFFB7299"))
    }

    @Test
    fun `empty means default not error`() {
        assertNull(ColorHex.parse(""))
        assertNull(ColorHex.parse("   "))
        // 空串是"用默认色"，属于合法输入
        assertTrue(ColorHex.isValid(""))
        assertTrue(ColorHex.isValid(null))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(ColorHex.parse("#GGGGGG"))
        assertNull(ColorHex.parse("#12345"))
        assertNull(ColorHex.parse("#1234567"))
        assertFalse(ColorHex.isValid("#12345"))
        assertFalse(ColorHex.isValid("红色"))
    }

    @Test
    fun `format round trips through parse`() {
        val argb = 0x80FB7299.toInt()
        assertEquals(argb, ColorHex.parse(ColorHex.format(argb)))
    }
}
