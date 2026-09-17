package com.example.bilimonitor.data.remote.bilibili

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * B 站账号接口（原规范 18：仅用于可选的关注导入，核心监控不依赖登录）。
 * 扫码登录（web 二维码）+ 账号信息 + 关注列表。Cookie 由 BiliCookieJar 自动携带/持久化。
 */
interface BiliAccountApi {

    @GET
    suspend fun getRaw(@Url url: String): JsonObject

    companion object {
        const val QR_GENERATE = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        fun qrPollUrl(key: String) =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$key"
        const val NAV = "https://api.bilibili.com/x/web-interface/nav"

        // 已删除的三个成员（避免后来者踩坑）：
        //  - postRaw：bili_ticket 方案的遗留，现改用 /x/frontend/finger/spi 取设备标识；
        //  - genWebTicketUrl：同上，且全仓库无引用；
        //  - followingsUrl(mid, page, ps = 50)：默认值 **50 违反接口 ps ≤ 30 的硬上限**，
        //    谁直接拿来用就会拿到 -400（这个坑已经排查过一轮）。关注列表现在由
        //    FollowImportRepository 自带参数（ps 已夹到 HISTORY_PS_MAX=30）走 getMainSiteJson。
    }
}

@Serializable
data class QrGenerateResult(val url: String, val qrcodeKey: String)

@Serializable
data class NavResult(val isLogin: Boolean, val mid: Long, val uname: String)

@Serializable
data class FollowingItem(val mid: Long, val uname: String, val face: String? = null)

object BiliAccountParsers {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun parseQrGenerate(obj: JsonObject): QrGenerateResult? {
        val code = obj["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        if (code != 0) return null
        val data = obj["data"]?.jsonObject ?: return null
        return QrGenerateResult(
            url = data["url"]?.jsonPrimitive?.content ?: return null,
            qrcodeKey = data["qrcode_key"]?.jsonPrimitive?.content ?: return null
        )
    }

    /**
     * 轮询结果为双层 code：顶层 code 表示请求本身是否成功（恒 0），
     * 扫码业务状态在 data.code：0=已确认 86090=已扫码未确认 86101=未扫码 86038=已过期。
     */
    fun parseQrPoll(obj: JsonObject): Pair<Int, String?> {
        val topOk = obj["code"]?.jsonPrimitive?.content?.toIntOrNull() == 0
        val data = obj["data"]?.jsonObject
        val code = data?.get("code")?.jsonPrimitive?.content?.toIntOrNull() ?: (if (topOk) -1 else -2)
        val url = data?.get("url")?.jsonPrimitive?.content
        return code to url
    }

    fun parseNav(obj: JsonObject): NavResult? {
        val code = obj["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        if (code != 0) return null
        val data = obj["data"]?.jsonObject ?: return null
        val isLogin = data["isLogin"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val mid = data["mid"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val uname = data["uname"]?.jsonPrimitive?.content ?: ""
        return NavResult(isLogin, mid, uname)
    }

    fun parseFollowings(obj: JsonObject): List<FollowingItem> {
        val data = obj["data"] ?: return emptyList()
        val listObj: JsonElement = try {
            data.jsonObject["list"] ?: return emptyList()
        } catch (e: Exception) {
            // data 直接是数组的老形态
            return runCatching {
                data.jsonArray.map { el ->
                    val o = el.jsonObject
                    FollowingItem(
                        mid = o["mid"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        uname = o["uname"]?.jsonPrimitive?.content ?: "",
                        face = o["face"]?.jsonPrimitive?.content
                    )
                }.filter { it.mid > 0 }
            }.getOrDefault(emptyList())
        }
        return runCatching {
            listObj.jsonArray.map { el ->
                val o = el.jsonObject
                FollowingItem(
                    mid = o["mid"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    uname = o["uname"]?.jsonPrimitive?.content ?: "",
                    face = o["face"]?.jsonPrimitive?.content
                )
            }.filter { it.mid > 0 }
        }.getOrDefault(emptyList())
    }
}
