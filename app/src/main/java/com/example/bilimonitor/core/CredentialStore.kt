package com.example.bilimonitor.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登录凭证存储（原规范 18.1/18.3）：
 *  - 不保存密码（扫码登录本身无密码）；
 *  - Cookie（SESSDATA/bili_jct/DedeUserID）保存在 EncryptedSharedPreferences；
 *  - 退出登录立即清除。
 *
 * **持久化格式**：每条 cookie 存 `Cookie.toString()`（含 domain/path/expires/secure/httpOnly），
 * 键名 `ckj_<name>`。
 *
 * 为什么不再用 `name → value`：那个格式把 cookie 的语义字段全丢了 ——
 * 保存时硬编码 `domain=bilibili.com; path=/`、永不过期，
 * 于是服务端的登出（`Set-Cookie: x=; expires=1970`）在本地不生效，
 * 早已失效的 SESSDATA 会被一直带着发出去，表现为"接口说未登录、界面以为已登录"。
 *
 * **向后兼容**：旧版的 `ck_<name>` 键仍然会被读出来（按旧语义重建为
 * `domain=bilibili.com; path=/` 的域 cookie），老用户的登录态不会因为格式升级而丢失；
 * 下一次写入时旧键会被一并清掉，完成迁移。
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext context: Context,
    /**
     * 时间统一走项目内的 AppClock（网络校时 + 单调），**不再用 System.currentTimeMillis()**：
     * cookie 的 `expiresAt` 是服务端时间，设备时钟前跳会把仍然有效的 cookie 判成过期并丢掉；
     * 设备 cookie 的 7 天新鲜度判定同理。
     *
     * 这里直接注入是安全的（无依赖环）：`CredentialStore ← BiliCookieJar ← OkHttpClient`，
     * 而 `AppClock ← NetworkTimeSource`，`NetworkTimeSource` 是 `@Inject constructor()` 纯对象、
     * 不依赖网络层 —— 链条到此为止，绕不回 CredentialStore。
     */
    private val clock: AppClock
) {

    private val appContext = context.applicationContext
    private val prefsName = "bili_credentials"

    /**
     * 凭证文件句柄。**构造期不再直接 create**（台账 H19-C3）：
     *
     * `EncryptedSharedPreferences.create()` 会读 Android Keystore 里的主密钥，
     * 换锁屏/生物识别变更、或"设备迁移后凭证文件被云备份还原而密钥不可还原"时，
     * 它会抛 `GeneralSecurityException`/`InvalidProtocolBufferException`。
     * 原先这一步写在字段初始化里、且该单例被 `BiliCookieJar` → OkHttp → 所有网络仓库依赖，
     * 一旦抛出就是**应用启动即崩、每次冷启都崩、只能清数据**。
     *
     * 现在的策略：失败就把损坏的凭证文件删掉重建一次；仍失败则退化为**内存态**
     * （本次会话可用、重启后需要重新登录），绝不把整个应用一起拖死。
     */
    private val prefs: SharedPreferences by lazy {
        createPrefs() ?: createPrefsAfterWipe() ?: InMemoryPrefs().also {
            android.util.Log.e(
                "CredentialStore",
                "无法创建加密凭证存储，已退化为内存态：本次会话仍可用，重启后需要重新登录"
            )
        }
    }

    private fun createPrefs(): SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            appContext,
            prefsName,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.onFailure {
        android.util.Log.e("CredentialStore", "创建加密凭证存储失败：${it.message}", it)
    }.getOrNull()

    private fun createPrefsAfterWipe(): SharedPreferences? = runCatching {
        // 文件本身还在、密钥没了（或文件被写坏）时，唯一能自愈的做法就是把文件删掉重建。
        // 代价是"需要重新登录"，远小于"应用打不开"。
        appContext.deleteSharedPreferences(prefsName)
        EncryptedSharedPreferences.create(
            appContext,
            prefsName,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.onFailure {
        android.util.Log.e("CredentialStore", "删除损坏凭证文件后重建仍失败：${it.message}", it)
    }.getOrNull()

    /** 极简内存实现：只在加密存储彻底不可用时兜底，保证应用还能启动与联网。 */
    private class InMemoryPrefs : SharedPreferences {
        private val map = java.util.concurrent.ConcurrentHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = java.util.HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        /**
         * 契约要求返回**副本**（真实实现每次都会重新反序列化出一个新集合）：
         * 直接返回内部集合会让调用方原地修改就悄悄改掉"已存的值"。
         */
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            /** clear() 与真实实现一致：**提交时才生效**，且在 put/remove 之前应用。 */
            private var clearRequested = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { clearRequested = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearRequested) map.clear()
                removals.forEach { map.remove(it) }
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    /**
     * 结构化持久化：整体替换已存的 cookie 集合（因此"消失的 cookie"会被真正删掉）。
     *
     * 用"先清后写"而不是"逐条合并"，是为了让删除语义可靠 ——
     * 服务端下发过期/删除时，调用方把该条从内存 jar 里去掉，这里就会顺带把它从存储里去掉。
     */
    fun persist(cookies: List<okhttp3.Cookie>) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(COOKIE_PREFIX) || it.startsWith(LEGACY_PREFIX) }
            .forEach { editor.remove(it) }
        cookies.forEach { editor.putString(COOKIE_PREFIX + it.name, it.toString()) }
        editor.apply()
    }

    /** 读回全部 cookie：新格式优先，旧格式（`ck_*`）按老语义兼容重建。 */
    fun loadCookies(): List<okhttp3.Cookie> {
        val out = mutableListOf<okhttp3.Cookie>()
        prefs.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            when {
                key.startsWith(COOKIE_PREFIX) ->
                    runCatching { okhttp3.Cookie.parse(PARSE_URL, value) }.getOrNull()?.let { out += it }

                // 旧格式只有 name → value，按旧代码的行为重建为域 cookie
                key.startsWith(LEGACY_PREFIX) -> {
                    val name = key.removePrefix(LEGACY_PREFIX)
                    runCatching {
                        okhttp3.Cookie.Builder()
                            .name(name).value(value)
                            .domain("bilibili.com").path("/")
                            .build()
                    }.getOrNull()?.let { out += it }
                }
            }
        }
        return out
    }

    /**
     * 设备标识 cookie（buvid3）的写入时间戳（毫秒，0 表示从未记录）。
     *
     * 为什么要记：buvid3 一旦写入就永远"存在"，若 B 站侧让它失效（风控/过期），
     * 原逻辑 `hasDeviceCookie()` 永远为 true → 永不重取 → 主站接口持续 -400 且查不出原因。
     */
    fun deviceCookieAt(): Long = prefs.getLong(KEY_DEVICE_COOKIE_AT, 0L)

    fun markDeviceCookieAt(now: Long) {
        prefs.edit().putLong(KEY_DEVICE_COOKIE_AT, now).apply()
    }

    /**
     * 当前时间（网络校时的墙上时间）。
     * 暴露给同文件的 CookieJar，避免它在各处再写一个 `System.currentTimeMillis()`。
     */
    fun now(): Long = clock.nowWall()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        /** 新格式：整条 cookie 的序列化文本。 */
        const val COOKIE_PREFIX = "ckj_"

        /** 旧格式：只存 name → value（仅为兼容读取保留，写入时会被迁移掉）。 */
        const val LEGACY_PREFIX = "ck_"

        const val KEY_DEVICE_COOKIE_AT = "meta_device_cookie_at"

        /**
         * `Cookie.parse` 需要一个 URL 来补全 hostOnly cookie 的域。
         * 用 www 主站：B 站下发的登录类 cookie 都是 `.bilibili.com` 域 cookie
         * （序列化文本里带 `domain=`），解析后会保持域 cookie 语义、对 api 子域同样生效。
         * 用 Builder 而不是 `HttpUrl.get(...)`：前者在 OkHttp 4/5 上写法一致。
         */
        val PARSE_URL: okhttp3.HttpUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("www.bilibili.com")
            .build()
    }
}

/** OkHttp CookieJar：把 B 站下发的 Cookie 持久化，并在请求时自动附带（仅 bilibili.com 域）。 */
class BiliCookieJar(private val store: CredentialStore) : okhttp3.CookieJar {

    private val cache = HashMap<String, List<okhttp3.Cookie>>()

    init {
        // 启动时把持久化 Cookie 放入内存缓存（以域为桶）；
        // 结构化读回，domain/path/expires 都保留，不再硬编码成 bilibili.com。
        val saved = store.loadCookies()
        if (saved.isNotEmpty()) {
            cache["bilibili.com"] = saved
        }
    }

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        if (cookies.none { it.domain.contains("bilibili") }) return
        synchronized(cache) {
            val key = "bilibili.com"
            val now = store.now()
            val merged = (cache[key].orEmpty() + cookies)
                // 删除/过期语义：服务端用 `Set-Cookie: x=; expires=1970`（或 Max-Age=0）
                // 表达"删掉这个 cookie"，OkHttp 会照原样交给我们，因此必须自己丢弃它 ——
                // 否则失效的 SESSDATA 会被一直带着发出去，界面还以为处于登录状态。
                .filter { it.expiresAt > now && it.value.isNotEmpty() }
                .groupBy { it.name }
                .mapValues { (_, v) -> v.last() }
                .values.toList()
            cache[key] = merged
            store.persist(merged)
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        if (!url.host.contains("bilibili")) return emptyList()
        val all = synchronized(cache) { cache["bilibili.com"].orEmpty() }
        val matched = all.filter { it.matches(url) }
        // 诊断：把**真正返回给 OkHttp 的列表**打出来（只看名字，不打印值）
        android.util.Log.d(
            "BiliCookieJar",
            "loadForRequest ${url.host}${url.encodedPath}：持有 ${all.size} 个，" +
                "返回 ${matched.size} 个=[${matched.joinToString(",") { it.name }}]"
        )
        return matched
    }

    /** 清空全部保存的 cookie。 */
    fun clearAll() {
        synchronized(cache) { cache.clear() }
        store.clear()
    }

    /**
     * 从浏览器复制来的整个 Cookie 字符串导入登录态。
     *
     * 为什么需要这条路径：扫码登录只能拿到 SESSDATA 等基础凭证，
     * 而部分主站接口还需要设备标识与风控票据（buvid3 / bili_ticket 等），
     * 这些只有浏览器会话里才是完整的。直接粘贴浏览器 Cookie 是最可靠的登录方式。
     *
     * @param persist `false` 表示**只改进程内的 jar、先不落盘**（"先校验后落盘"用）：
     *   校验期间进程被杀也不会让未经验证的 cookie 覆盖掉原本可用的登录态；
     *   校验通过后由调用方显式调 [persistCurrent] 落盘。
     * @return 导入的 cookie 条数（0 表示没解析出任何有效项）。
     */
    fun importCookieHeader(raw: String, persist: Boolean = true): Int {
        val parsed = raw.trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .split(';')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
        if (parsed.isEmpty()) return 0

        synchronized(cache) {
            val key = "bilibili.com"
            val names = parsed.map { it.first }.toSet()
            // 同名的旧值先移除，避免重复
            val existing = cache[key].orEmpty().filterNot { it.name in names }
            val added = parsed.mapNotNull { (name, value) ->
                runCatching {
                    okhttp3.Cookie.Builder()
                        .name(name).value(value)
                        .domain("bilibili.com").path("/")
                        .build()
                }.getOrNull()
            }
            val merged = existing + added
            cache[key] = merged
            if (persist) {
                store.persist(merged)
                // 粘贴的 Cookie 里若自带 buvid3，也视作"刚刚拿到设备标识"，
                // 免得紧接着又被 ensureDeviceCookies() 重新取一次
                if (names.contains("buvid3")) store.markDeviceCookieAt(store.now())
            }
            return added.size
        }
    }

    /**
     * 把当前内存 jar 落盘（"先校验后落盘"的第二半）。
     * 若 jar 里有 buvid3，同时把设备标识时间戳记为"现在"。
     */
    fun persistCurrent() {
        synchronized(cache) {
            val cookies = cache["bilibili.com"].orEmpty()
            store.persist(cookies)
            if (cookies.any { it.name == "buvid3" && it.value.isNotBlank() }) {
                store.markDeviceCookieAt(store.now())
            }
        }
    }

    /** 关键 cookie 的到位情况（用于导入后的结果提示）。 */
    fun credentialSummary(): String {
        val cookies = synchronized(cache) { cache["bilibili.com"].orEmpty() }
        fun has(n: String) = cookies.any { it.name == n && it.value.isNotBlank() }
        return buildString {
            append("SESSDATA=").append(if (has("SESSDATA")) "有" else "缺")
            append("，buvid3=").append(if (has("buvid3")) "有" else "缺")
            append("，bili_ticket=").append(if (has("bili_ticket")) "有" else "缺")
        }
    }

    /** 仅用于诊断日志：已保存的 cookie 条数（不暴露任何值）。 */
    fun debugCookieCount(): Int = synchronized(cache) { cache["bilibili.com"].orEmpty().size }

    /**
     * 是否已具备**新鲜**的设备标识 cookie（buvid3）。缺失或过期时主站接口会返回 -400。
     *
     * 除了"有值"，还要求写入时间在 [DEVICE_COOKIE_TTL_MS] 内：设备标识写一次就永不重取的话，
     * 一旦它在服务端失效（风控/过期），`hasDeviceCookie()` 会永远为 true，
     * 重取逻辑再也不会触发 —— 用户看到的是"主站接口一直 -400"且无从排查。
     * 旧版本里没有时间戳（读到 0），会判定为过期并重取一次，正好完成迁移。
     */
    fun hasDeviceCookie(): Boolean = synchronized(cache) {
        val hasValue = cache["bilibili.com"].orEmpty().any { it.name == "buvid3" && it.value.isNotBlank() }
        val fresh = store.now() - store.deviceCookieAt() < DEVICE_COOKIE_TTL_MS
        hasValue && fresh
    }

    /**
     * cookie 集合 + 设备标识时间戳的整体快照。
     *
     * 为什么时间戳也要进快照：粘贴 Cookie 导入时会顺带把 buvid3 的时间戳记成"现在"，
     * 如果回滚只还原子 cookie 列表，那个"刚刚"的时间戳会留下 ——
     * 于是旧 buvid3 被判为新鲜，7 天内都不会再重取，正好把"设备标识失效"这条自愈路径堵死。
     */
    data class Snapshot(val cookies: List<okhttp3.Cookie>, val deviceCookieAt: Long)

    /**
     * 当前状态快照（用于"先校验后落盘"失败时回滚）。
     *
     * OkHttp 的 Cookie 是不可变对象，因此浅拷贝即可安全持有。
     */
    fun snapshot(): Snapshot = synchronized(cache) {
        Snapshot(cache["bilibili.com"].orEmpty().toList(), store.deviceCookieAt())
    }

    /** 用快照整体覆盖当前状态（内存 + 持久化一起回滚，含设备标识时间戳）。 */
    fun restore(snapshot: Snapshot) {
        synchronized(cache) {
            cache["bilibili.com"] = snapshot.cookies
            store.persist(snapshot.cookies)
            store.markDeviceCookieAt(snapshot.deviceCookieAt)
        }
    }

    /** 读某个 cookie 的值（用于签名输入，如 buvid3）。 */
    fun cookieValue(name: String): String? = synchronized(cache) {
        cache["bilibili.com"].orEmpty().firstOrNull { it.name == name }?.value
    }

    /**
     * 写入任意 cookie（设备标识 / 风控票据）。
     *
     * B 站的 `/x/frontend/finger/spi` 与 `/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket`
     * **都不通过 Set-Cookie 下发**，而是把值放在响应体里，因此必须由调用方显式写回。
     */
    fun putCookie(name: String, value: String?) {
        if (value.isNullOrBlank()) return
        synchronized(cache) {
            val key = "bilibili.com"
            val existing = cache[key].orEmpty().filterNot { it.name == name }
            val added = runCatching {
                okhttp3.Cookie.Builder()
                    .name(name).value(value)
                    .domain("bilibili.com").path("/")
                    .build()
            }.getOrNull() ?: return
            val merged = existing + added
            cache[key] = merged
            store.persist(merged)
            // 设备标识写入时打时间戳（见 hasDeviceCookie 的过期判定）
            if (name == "buvid3") store.markDeviceCookieAt(store.now())
        }
    }

    /**
     * 写入设备标识 cookie（buvid3 / buvid4）。
     *
     * buvid3 的时间戳由 [putCookie] 顺带打上（`hasDeviceCookie` 靠它判定新鲜度）。
     */
    fun putDeviceCookies(buvid3: String?, buvid4: String?) {
        putCookie("buvid3", buvid3)
        putCookie("buvid4", buvid4)
    }

    private companion object {
        /**
         * 设备标识的有效期：7 天。取的是保守值 —— 重取一次只是一次轻量请求，
         * 而设备标识失效导致的 `-400` 会让整个导入功能不可用。
         */
        const val DEVICE_COOKIE_TTL_MS = 7L * 24 * 3600 * 1000
    }
}
