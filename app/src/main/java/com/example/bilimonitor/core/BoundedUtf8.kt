package com.example.bilimonitor.core

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

class BackupTooLargeException : java.io.IOException("备份文件超过 64 MB 上限，请缩小备份范围")

/** Enforce bytes actually consumed, including providers with missing or inaccurate SIZE. */
object BoundedUtf8 {
    fun read(input: InputStream, limit: Long): String {
        val bounded = object : FilterInputStream(input) {
            var remaining = limit
            override fun read(): Int {
                val value = `in`.read()
                if (value != -1 && --remaining < 0) throw BackupTooLargeException()
                return value
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val n = `in`.read(bytes, offset, minOf(length.toLong(), remaining + 1).toInt())
                if (n > 0) { remaining -= n; if (remaining < 0) throw BackupTooLargeException() }
                return n
            }
        }
        return bounded.reader(Charsets.UTF_8).readText().removePrefix("\uFEFF")
    }

    fun output(output: OutputStream, limit: Long): OutputStream = object : FilterOutputStream(output) {
        var written = 0L
        override fun write(value: Int) {
            if (++written > limit) throw BackupTooLargeException()
            out.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written += length
            if (written > limit) throw BackupTooLargeException()
            out.write(bytes, offset, length)
        }
    }
}
