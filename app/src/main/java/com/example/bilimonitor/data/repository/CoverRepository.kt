package com.example.bilimonitor.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 直播间封面的保存（用户要求：封面要支持保存到本地）。
 *
 * 复用应用唯一的 [OkHttpClient]：B 站图床（`*.hdslb.com`）有防盗链，
 * 不带 UA/Referer 会拿到 403 —— 该客户端的拦截器已经统一补上，
 * 自己 new 一个客户端下载会踩这个坑。
 *
 * 落点用 MediaStore 的 `Pictures/主播监控/`：targetSdk 35 下写自己插入的媒体
 * 不需要任何存储权限（与 `ExportRepository` 写 Download 同一套路），
 * 而且放进 Pictures 后系统相册能直接看到，比埋在应用私有目录有用得多。
 */
@Singleton
class CoverRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val streamerDao: StreamerDao,
    private val logDao: LogDao,
    private val clock: AppClock
) {
    /** 保存结果：文件名用于回显，路径用于提示。 */
    data class SavedCover(val fileName: String, val relativePath: String)

    /**
     * 下载并保存指定主播**当前**的直播间封面。
     *
     * 全程切到 IO 线程：OkHttp 的同步 `execute()` 与 MediaStore 写入都是阻塞调用，
     * 而 ViewModel 的 `viewModelScope.launch` 默认跑在主线程 ——
     * 实测会直接抛 `NetworkOnMainThreadException`（`<-- HTTP FAILED`），
     * 封面下载不下来的原因就是这个。仓库层自己负责线程，调用方不必关心。
     *
     * @return 成功时给出文件名与相对路径；失败时异常信息可直接展示给用户。
     */
    suspend fun saveCover(streamerId: Long): Result<SavedCover> = withContext(Dispatchers.IO) {
        runCatching {
            val streamer = streamerDao.findById(streamerId)
                ?: throw IllegalStateException("主播不存在（可能已被删除）")
            val url = streamer.coverUrl?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("还没有获取到封面，请先点右上角刷新资料")
            val bytes = download(url)
            val fileName = fileNameFor(streamer.name)
            val relativePath = "${Environment.DIRECTORY_PICTURES}/$FOLDER"
            writeToGallery(fileName, relativePath, bytes)
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "SAVE_COVER", targetType = "streamer",
                    targetStableId = streamer.stableId, occurredAt = clock.nowWall(),
                    detailJson = "{\"file\":\"$fileName\",\"bytes\":${bytes.size}}"
                )
            )
            SavedCover(fileName, relativePath)
        }
    }

    private fun download(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载封面失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IOException("下载封面失败（响应为空）")
            val bytes = body.bytes()
            if (bytes.isEmpty()) throw IOException("下载封面失败（内容为空）")
            return bytes
        }
    }

    private fun writeToGallery(fileName: String, relativePath: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建图片文件")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("无法写入图片文件")
        } catch (e: Exception) {
            // 半截文件不要留在相册里
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    /** `主播名_直播间封面_20260913_120501.jpg`；过滤文件系统非法字符。 */
    private fun fileNameFor(streamerName: String): String {
        val safe = streamerName.map { if (it in "\\/:*?\"<>|" || it.code < 0x20) '_' else it }
            .joinToString("").trim().trimEnd('.').ifBlank { "主播" }.take(40)
        val stamp = com.example.bilimonitor.core.AppClocks.stamp("yyyyMMdd_HHmmss")
        return "${safe}_直播间封面_$stamp.jpg"
    }

    private companion object {
        const val FOLDER = "主播监控"
    }
}
