package com.example.bilimonitor.core

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WBI 签名（B 站主站接口的反爬校验）。
 *
 * 背景：B 站对相当一部分 `api.bilibili.com/x/...` 接口要求请求带 `w_rid` 与 `wts` 两个参数，
 * 密钥来自 `nav` 响应里的 `wbi_img.img_url` / `sub_url`。
 * 缺少签名时典型表现不是"未登录"(-101)，而是 **`-400 请求错误`** ——
 * 服务器已识别账号但判定请求不合法。这正是「登录正常、观看历史导入却一直失败」的原因。
 *
 * 算法（公开）：
 * 1. 取两个 URL 的文件名（去扩展名）拼成 orig；
 * 2. 按固定混淆表重排后取前 32 位 = mixinKey（密钥每日轮换）；
 * 3. 参数过滤 `!'()*` 字符、按 key 升序拼接、末尾追加 mixinKey，取 MD5 = w_rid；
 * 4. 一并附上 `wts`（当前秒级时间戳）。
 *
 * 已用 `x/web-interface/wbi/view` 实测验证：带签名 code=0，不带签名被拒。
 */
@Singleton
class WbiSigner @Inject constructor(
    private val clock: AppClock
) {
    @Volatile
    private var cachedMixinKey: String? = null

    @Volatile
    private var cachedAtSeconds: Long = 0

    /**
     * 用 `nav` 响应刷新密钥。密钥每日轮换，因此按 [KEY_TTL_SECONDS] 判定过期。
     * 失败时保留旧值（宁可继续用旧密钥，也不要让整个导入不可用）。
     */
    fun updateFromNav(navBody: kotlinx.serialization.json.JsonObject) {
        runCatching {
            val wbi = navBody["data"]?.jsonObject?.get("wbi_img")?.jsonObject ?: return
            fun keyOf(field: String): String? =
                wbi[field]?.jsonPrimitive?.content
                    ?.substringAfterLast('/')
                    ?.substringBefore('.')

            val imgKey = keyOf("img_url")
            val subKey = keyOf("sub_url")
            if (imgKey.isNullOrBlank() || subKey.isNullOrBlank()) return
            cachedMixinKey = mixinKeyOf(imgKey + subKey)
            cachedAtSeconds = clock.nowWall() / 1000
            android.util.Log.i(
                "WbiSigner",
                "密钥已更新：imgKey=$imgKey subKey=$subKey mixinKey=$cachedMixinKey"
            )
        }.onFailure { android.util.Log.w("WbiSigner", "从 nav 更新密钥失败：${it.message}") }
    }

    /**
     * 密钥是否已过期。
     *
     * B 站每天轮换 `img_key` / `sub_key`，过期后**算出来的 `w_rid` 服务端一定不认**，
     * 表现是与"参数非法"完全相同的稳定 `-400`，极难定位。
     * 原先 `cachedAtSeconds` 只写不读、"按天缓存"只停留在注释里 ——
     * 而实时监控是长期常驻进程，于是密钥永不刷新，
     * 用户看到的就是"昨天还能用的导入，今天全废"（唯一自愈方式是杀进程或打开登录页）。
     */
    private fun isExpired(nowSeconds: Long): Boolean =
        cachedAtSeconds <= 0L || nowSeconds - cachedAtSeconds >= KEY_TTL_SECONDS

    /**
     * 是否已具备签名能力：**有密钥且未过期**。
     *
     * 调用方 `AuthRepository.ensureMainSiteReady()` 是"不 ready 就刷新"的写法，
     * 因此把"过期"也算作 not ready 之后，密钥轮换会在下一次主站请求前自动重新获取，
     * 不需要再单独加一个刷新入口。
     */
    fun ready(): Boolean = cachedMixinKey != null && !isExpired(clock.nowWall() / 1000)

    /**
     * 对查询参数签名；返回**包含 `wts` / `w_rid` 的完整参数表**。
     *
     * 未取得密钥、或密钥已过期时返回原参数（**不拿过期密钥硬签**）：
     * 硬签只会换来 `-400`，把"密钥过期"伪装成"参数非法"。
     * 返回原参数时调用方可据"参数个数没变"判断这次其实没签名。
     */
    fun sign(params: Map<String, String>): Map<String, String> {
        val mixin = cachedMixinKey ?: return params
        if (isExpired(clock.nowWall() / 1000)) {
            android.util.Log.w(
                "WbiSigner",
                "签名密钥已过期（超过 ${KEY_TTL_SECONDS / 3600} 小时），本次不签名，等待上层刷新"
            )
            return params
        }
        val signed = params.toMutableMap()
        signed["wts"] = (clock.nowWall() / 1000).toString()
        // 过滤 !'()* 后再排序拼接
        val cleaned = signed.mapValues { (_, v) -> v.filterNot { it in "!'()*" } }
        val query = cleaned.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        signed["w_rid"] = md5(query + mixin)
        // 诊断：把密钥与待签名串原样打出，便于独立复算 MD5 验证签名是否正确
        android.util.Log.i(
            "WbiSigner",
            "mixinKey=$mixin\n待签名串=$query\nw_rid=${signed["w_rid"]}"
        )
        return signed
    }

    /**
     * 把参数编码成查询串（过滤 `!'()*` → 按 key 升序 → 百分号编码），**不含签名**。
     */
    fun encodeQuery(params: Map<String, String>): String =
        params.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) ->
                "$k=" + java.net.URLEncoder.encode(v.filterNot { it in "!'()*" }, "UTF-8")
            }

    /**
     * 拼出可直接请求的 URL（参数原样，不含签名）。
     *
     * 与 [signToQuery] 共用同一个 [encodeQuery]，因此"签名用的值"和"发出去的值"
     * 不可能出现两套编码规则。
     */
    fun queryUrl(baseUrl: String, params: Map<String, String>): String =
        baseUrl + "?" + encodeQuery(params)

    /**
     * 一步得到可直接请求的 URL（带签名时含 `wts` / `w_rid`）。
     *
     * **不变量**：`w_rid` 的计算输入与实际发出去的 query 必须来自**同一份过滤后的值** ——
     * 服务端收到请求后先解码、再按同样的规则复算 MD5，所以这里每个值只过滤一次：
     * 签名用过滤后的原值，URL 用"过滤后再编码"的结果。
     *
     * 如果像原先那样"签名按过滤值算、URL 按未过滤原值拼"，只要某个参数里出现
     * `!'()*` 或 `&`、空格、非 ASCII，就会出现签名与内容不一致 —— 同样是稳定的 `-400`，
     * 且几乎无法从现象反推原因。当前调用点的参数都是数字，两者恰好等价；
     * 收敛到这个单点实现是为了以后不会踩坑。
     */
    fun signToQuery(baseUrl: String, params: Map<String, String>): String =
        queryUrl(baseUrl, sign(params))

    private fun mixinKeyOf(orig: String): String =
        MIXIN_KEY_ENC_TAB.map { orig.getOrNull(it) ?: ' ' }
            .joinToString("")
            .take(32)

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /**
         * 密钥有效期：B 站按自然日轮换，取 6 小时作为保守上限 ——
         * 宁可偶尔多刷一次 `nav`（一次轻量请求），也不要拿过期密钥去换 `-400`。
         */
        const val KEY_TTL_SECONDS = 6 * 3600L

        /** 官方混淆表（公开常量）。 */
        val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
        )
    }
}
