package com.example.bilimonitor.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份校验和的结构升级兼容性（H12/H15 的教训固化）。
 *
 * 真实事故：备份校验和原本按"反序列化成 BackupBody 再重新序列化"的文本计算，
 * 于是**每次给 body 加字段**（哪怕带默认值）都会让历史上所有备份的校验和失配 ——
 * 用户的旧备份会全部被判成"文件已损坏"。
 * 现在校验以文件里的**原始 body 文本**为准，这组测试就是钉住这个性质。
 */
class BackupChecksumTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun manifest(checksum: String) = BackupManifest(
        backupId = "b1",
        backupSchemaVersion = 1,
        formatVersion = 1,
        roomSchemaVersion = 6,
        createdAt = 0L,
        sourceDataVersion = 0L,
        scopeType = "ALL_DATA",
        selectedStreamerStableIds = emptyList(),
        recordCounts = emptyMap(),
        checksum = checksum,
        appVersionName = "1.1"
    )

    private fun emptyBody() = BackupBody(
        streamers = listOf(
            BackupStreamer(
                stableId = "st-1", uid = 1001L, roomId = 2002L, shortRoomId = 0L,
                name = "测试主播", nameLocked = false, avatarUrl = null, roomTitle = null,
                parentAreaName = null, areaName = null, coverUrl = null, liveUrl = null,
                confirmedLiveStatus = "UNKNOWN", deleted = false, monitoringEnabled = true,
                isFavorite = false, createdAt = 0L
            )
        ),
        tags = emptyList(), groups = emptyList(),
        tagRefs = emptyList(), groupRefs = emptyList(), sessions = emptyList(),
        events = emptyList(), titles = emptyList(), corrections = emptyList(),
        policies = emptyList()
    )

    /**
     * 造一个"旧版本写出的文件"：当前数据类序列化后**删掉 `areas` 键**，
     * 并按删掉后的 body 文本计算校验和 —— 这正是旧版本当年的行为。
     */
    private fun legacyFile(): Pair<String, String> {
        val current = json.encodeToString(BackupFile.serializer(), BackupFile(manifest("x"), emptyBody()))
        val withoutAreas = current.replace("\"areas\":[],", "").replace(",\"areas\":[]", "")
        val bodyText = json.parseToJsonElement(withoutAreas).jsonObject["body"]!!.toString()
        val checksum = BackupChecksum.sha256(bodyText)
        val withChecksum = withoutAreas.replace("\"checksum\":\"x\"", "\"checksum\":\"$checksum\"")
        return withChecksum to checksum
    }

    @Test
    fun `旧文件里确实没有 areas 键`() {
        val (content, _) = legacyFile()
        val body = json.parseToJsonElement(content).jsonObject["body"]!!.jsonObject
        assertFalse("旧文件的 body 不应含 areas", body.containsKey("areas"))
    }

    @Test
    fun `原始 body 哈希与旧版本写出的一致`() {
        val (content, checksum) = legacyFile()
        assertEquals(checksum, BackupChecksum.ofRawBody(json, content))
    }

    @Test
    fun `重新编码会把新字段补进去 因此不能作为唯一判据`() {
        val (content, _) = legacyFile()
        val body = json.decodeFromString(BackupFile.serializer(), content).body
        assertNotEquals(
            BackupChecksum.ofRawBody(json, content),
            BackupChecksum.ofReencoded(json, body)
        )
    }

    @Test
    fun `旧备份在新增字段之后依然通过校验`() {
        val (content, checksum) = legacyFile()
        val body = json.decodeFromString(BackupFile.serializer(), content).body
        assertTrue(BackupChecksum.matches(json, content, body, checksum))
    }

    @Test
    fun `内容被改动后校验失败`() {
        val (content, checksum) = legacyFile()
        // 改一个仍能正常解析的字段（主播昵称），确保失败原因是校验和而不是解析异常
        val tampered = content.replace("测试主播", "被篡改的主播")
        assertNotEquals(content, tampered)
        val body = json.decodeFromString(BackupFile.serializer(), tampered).body
        assertEquals("被篡改的主播", body.streamers.first().name)
        assertFalse(BackupChecksum.matches(json, tampered, body, checksum))
    }

    @Test
    fun `新字段自身参与校验`() {
        val body = emptyBody().copy(
            areas = listOf(BackupArea("s1", "虚拟主播·生活娱乐", "虚拟主播", "生活娱乐", 1L))
        )
        val content = json.encodeToString(BackupFile.serializer(), BackupFile(manifest("x"), body))
        val checksum = BackupChecksum.ofRawBody(json, content)!!
        assertEquals(1, json.decodeFromString(BackupFile.serializer(), content).body.areas.size)
        assertTrue(BackupChecksum.matches(json, content, body, checksum))
        val tampered = content.replace("生活娱乐", "唱见")
        val tamperedBody = json.decodeFromString(BackupFile.serializer(), tampered).body
        assertFalse(BackupChecksum.matches(json, tampered, tamperedBody, checksum))
    }

    @Test
    fun `校验和字段本身不参与 body 哈希`() {
        val (content, checksum) = legacyFile()
        val other = content.replace("\"checksum\":\"$checksum\"", "\"checksum\":\"0000\"")
        assertEquals(checksum, BackupChecksum.ofRawBody(json, other))
        // 顺带确认 checksum 确实写进了 manifest（解析得到）
        assertEquals(
            "0000",
            json.parseToJsonElement(other).jsonObject["manifest"]!!.jsonObject["checksum"]!!.jsonPrimitive.content
        )
    }
}
