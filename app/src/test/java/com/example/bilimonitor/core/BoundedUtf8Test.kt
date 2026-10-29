package com.example.bilimonitor.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BoundedUtf8Test {
    @Test fun exactByteLimitAndBom() {
        val bytes = "\uFEFF中文".toByteArray(Charsets.UTF_8)
        assertEquals("中文", BoundedUtf8.read(ByteArrayInputStream(bytes), bytes.size.toLong()))
    }
    @Test fun unknownLengthCannotBypassLimit() {
        var bytesRead = 0
        val input = object : java.io.InputStream() {
            override fun read(): Int { bytesRead++; return 97 }
        }
        try { BoundedUtf8.read(input, 37); fail("oversize must fail") }
        catch (_: BackupTooLargeException) { assertEquals(38, bytesRead) }
    }
    @Test fun outputUsesSameByteLimit() {
        val bytes = ByteArrayOutputStream()
        val output = BoundedUtf8.output(bytes, 6)
        output.write("中文".toByteArray())
        try { output.write(33); fail("oversize must fail") }
        catch (_: BackupTooLargeException) { assertEquals(6, bytes.size()) }
    }
    @Test fun zeroLimitAcceptsOnlyEmptyInput() {
        assertEquals("", BoundedUtf8.read(ByteArrayInputStream(byteArrayOf()), 0))
        try { BoundedUtf8.read(ByteArrayInputStream(byteArrayOf(1)), 0); fail() }
        catch (_: BackupTooLargeException) { }
    }
}
