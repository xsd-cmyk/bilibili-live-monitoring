package com.example.bilimonitor.data.repository

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.BiliCookieJar
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.AuthSessionEntity
import com.example.bilimonitor.data.remote.bilibili.BiliAccountApi
import com.example.bilimonitor.data.remote.bilibili.BiliAccountParsers
import com.example.bilimonitor.data.remote.bilibili.NavResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class AccountState(
    val checking: Boolean = false,
    val loggedIn: Boolean = false,
    val mid: Long = 0,
    val uname: String = ""
)

sealed interface QrLoginState {
    data object Idle : QrLoginState
    data class QrReady(val qrUrl: String, val key: String) : QrLoginState
    data object WaitingScan : QrLoginState
    data object ScannedWaitingConfirm : QrLoginState
    data object Expired : QrLoginState
    data class Confirmed(val account: NavResult) : QrLoginState
    data class Failed(val message: String) : QrLoginState
}

/**
 * 账号仓库（原规范 18 / 0.4 账号最终边界）：
 * 登录仅服务于可选的关注导入；退出登录递增 authGeneration，旧任务数据只写入隔离缓冲。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val db: AppDatabase,
    private val api: BiliAccountApi,
    private val cookieJar: BiliCookieJar,
    private val logDao: LogDao,
    private val clock: AppClock,
    private val wbiSigner: com.example.bilimonitor.core.WbiSigner
) {
    private val _account = MutableStateFlow(AccountState(checking = true))
    val account: StateFlow<AccountState> = _account

    private val _qrState = MutableStateFlow<QrLoginState>(QrLoginState.Idle)
    val qrState: StateFlow<QrLoginState> = _qrState

    suspend fun refreshAccount() {
        _account.value = _account.value.copy(checking = true)
        val navObj = runCatching { api.getRaw(BiliAccountApi.NAV) }.getOrNull()
        // nav 响应同时携带 WBI 签名密钥（wbi_img），主站接口需要它，
        // 因此每次刷新账号顺手更新密钥（按天轮换）。
        navObj?.let { wbiSigner.updateFromNav(it) }
        val nav = navObj?.let { runCatching { BiliAccountParsers.parseNav(it) }.getOrNull() }
        val loggedIn = nav != null && nav.isLogin && nav.mid > 0
        // 登录态判定失败时留下可排查的痕迹：区分「请求失败」与「接口说未登录」
        if (!loggedIn) {
            android.util.Log.w(
                "BiliAuth",
                "登录态判定为未登录：nav=${if (nav == null) "解析失败/请求失败" else "isLogin=${nav.isLogin} mid=${nav.mid}"}" +
                    "，已保存的 cookie 数量=${cookieJar.debugCookieCount()}"
            )
        } else {
            // 登录成功后立刻补齐设备 cookie，否则主站接口（观看历史等）会返回 -400。
            ensureDeviceCookies()
        }
        _account.value = if (loggedIn) {
            AccountState(loggedIn = true, mid = nav!!.mid, uname = nav.uname)
        } else {
            AccountState(loggedIn = false)
        }
    }

    /**
     * 补齐 B 站设备标识 cookie（buvid3 / buvid4）。
     *
     * `hasDeviceCookie()` 的语义是"有值**且未超过 7 天**"，因此设备标识过期后
     * 这里会自动重新取一次 —— 原实现只看"有没有"，写一次就永不刷新，
     * 一旦服务端让它失效，主站接口就会一直 -400 且完全没有排查线索。
     */
    suspend fun ensureDeviceCookies() {
        if (cookieJar.hasDeviceCookie()) return
        // `/x/frontend/finger/spi` 把设备标识放在**响应体**里（b_3 / b_4），
        // 而不是 Set-Cookie，所以必须自己解析并写回 CookieJar。
        val endpoints = listOf(
            "https://api.bilibili.com/x/frontend/finger/spi",
            "https://www.bilibili.com/x/frontend/finger/spi"
        )
        for (url in endpoints) {
            val obj = runCatching { api.getRaw(url) }.getOrNull() ?: continue
            val data = runCatching { obj["data"]?.jsonObject }.getOrNull()
            val b3 = data?.get("b_3")?.jsonPrimitive?.contentOrNull
            val b4 = data?.get("b_4")?.jsonPrimitive?.contentOrNull
            if (!b3.isNullOrBlank()) {
                cookieJar.putDeviceCookies(b3, b4)
                android.util.Log.i("BiliAuth", "已写入设备 cookie buvid3（来源 $url）")
                return
            }
        }
        android.util.Log.w("BiliAuth", "未能取得设备 cookie，主站接口可能返回 -400")
    }

    /** 凭证到位情况摘要，供导入等流程写日志排查。 */
    fun credentialSummary(): String = cookieJar.credentialSummary()

    /** 登录轮询成功后调用：记录凭证到位情况，便于排查。 */
    private fun logAuthCookieState() {
        android.util.Log.i(
            "BiliAuth",
            "登录后 cookie 状态：总数=${cookieJar.debugCookieCount()}，${cookieJar.credentialSummary()}"
        )
    }

    /**
     * 用浏览器里复制来的 Cookie 串直接登录。
     *
     * **顺序很关键：先校验、后落盘**。
     * 原实现是"立刻写入 → 再用 nav 校验"，用户粘了一串过期/复制不全的 Cookie 时，
     * 同名的新值已经把原本可用的 SESSDATA 覆盖掉了：提示"Cookie 无效"的同时，
     * 用户其实已经被踢下线，而且界面上完全看不出这回事。
     * 现在分两阶段：
     *  1. 导入时 `persist = false` —— **只改进程内的 jar，不动磁盘**。
     *     校验期间进程被杀，下次启动读到的仍是原来那套（可能有效的）凭证，
     *     不会出现"未经验证的新 cookie 已经落盘、旧登录态已丢"；
     *  2. nav 校验通过后才 `persistCurrent()` 落盘。
     * 校验失败则用快照整体回滚（内存 + 磁盘 + 设备标识时间戳一起）。
     *
     * @return null 表示成功，否则返回失败原因。
     */
    suspend fun loginWithCookie(rawCookie: String): String? {
        val backup = cookieJar.snapshot()
        val imported = cookieJar.importCookieHeader(rawCookie, persist = false)
        if (imported <= 0) return "没有解析出有效的 Cookie，请确认粘贴的是浏览器请求头里的 Cookie 值"
        // 立刻用 nav 验证这串 Cookie 是否真的有效
        val navObj = runCatching { api.getRaw(BiliAccountApi.NAV) }.getOrNull()
        val nav = navObj?.let { runCatching { BiliAccountParsers.parseNav(it) }.getOrNull() }
        if (nav == null || !nav.isLogin || nav.mid <= 0) {
            // 校验失败：把原有登录态原样恢复，避免"试一次错 Cookie 就把能用的登录弄坏"
            cookieJar.restore(backup)
            return "Cookie 无效或已过期（导入 $imported 条），已恢复原有登录态。" +
                "请在浏览器重新登录 B 站后复制最新 Cookie"
        }
        // 校验通过：正式落盘（此后才存在"已登录"这个事实）
        cookieJar.persistCurrent()
        // WBI 密钥同样来自 nav，校验通过后才更新（失败路径不留任何副作用）
        navObj?.let { wbiSigner.updateFromNav(it) }
        ensureDeviceCookies()
        createAuthSession(nav)
        _account.value = AccountState(loggedIn = true, mid = nav.mid, uname = nav.uname)
        _qrState.value = QrLoginState.Confirmed(nav)
        android.util.Log.i(
            "BiliAuth",
            "Cookie 登录成功：${nav.uname}(${nav.mid})，凭证状态：${cookieJar.credentialSummary()}"
        )
        return null
    }

    /**
     * 主站接口的**尽力准备**（不是准入门槛）。
     *
     * 返回的字符串是**诊断信息**，调用方只应写进日志，不该据此中断请求。
     *
     * 教训：这里曾经把「缺 `bili_ticket`」当作硬性前置条件，直接拒绝发起导入 ——
     * 而 `bili_ticket` 只由网页 JS 注入、HTTP 客户端根本拿不到，
     * 于是导入功能被自家守卫永久锁死，真正的失败原因（`ps` 超限）反而看不见。
     * 依赖是否真的必要，只能由服务端回答：先发请求，再按返回码判断。
     */
    suspend fun ensureMainSiteReady(): String? {
        // `ready()` 的语义是"有密钥**且未过期**"，所以这一句同时覆盖两种情况：
        // 从没取过密钥，以及密钥已超过有效期（B 站每日轮换）。
        // 密钥过期后仍拿旧密钥签名会得到稳定 -400，且与"参数非法"无法区分，
        // 因此宁可在这里多刷一次 nav。
        if (!wbiSigner.ready()) {
            runCatching { api.getRaw(BiliAccountApi.NAV) }
                .getOrNull()?.let { wbiSigner.updateFromNav(it) }
        }
        ensureDeviceCookies()
        return when {
            !wbiSigner.ready() -> "未能获取接口签名密钥（WBI），请检查网络后重试"
            !cookieJar.hasDeviceCookie() -> "未能获取设备标识，请检查网络后重试"
            else -> null
        }
    }

    suspend fun generateQr(): Boolean {
        val raw = runCatching { api.getRaw(BiliAccountApi.QR_GENERATE) }.getOrNull()
        val qr = raw?.let { runCatching { BiliAccountParsers.parseQrGenerate(it) }.getOrNull() }
        android.util.Log.i(
            "QrLogin",
            "generateQr: 响应=${if (raw == null) "请求失败" else raw.toString().take(160)}" +
                "，解析=${if (qr == null) "失败" else "成功(key=${qr.qrcodeKey.take(8)}…)"}"
        )
        return if (qr != null) {
            _qrState.value = QrLoginState.QrReady(qr.url, qr.qrcodeKey)
            true
        } else {
            _qrState.value = QrLoginState.Failed("获取登录二维码失败，请检查网络")
            false
        }
    }

    /** 一次轮询；返回是否到达终态（成功/过期/失败）。 */
    suspend fun pollQrOnce(key: String): Boolean {
        if (_qrState.value is QrLoginState.Confirmed ||
            _qrState.value is QrLoginState.Expired ||
            _qrState.value is QrLoginState.Failed
        ) return true
        val (code, _) = runCatching {
            BiliAccountParsers.parseQrPoll(api.getRaw(BiliAccountApi.qrPollUrl(key)))
        }.getOrElse { return false }
        when (code) {
            0 -> {
                // Cookie 已由 BiliCookieJar 在响应时自动持久化。
                refreshAccount()
                val nav = runCatching {
                    BiliAccountParsers.parseNav(api.getRaw(BiliAccountApi.NAV))
                }.getOrNull()
                if (nav != null && nav.isLogin) {
                    // 扫码登录刚拿到 SESSDATA，但主站接口还需要设备 cookie（buvid3），
                    // 否则观看历史等接口会返回 -400。这里立刻补齐。
                    runCatching { ensureDeviceCookies() }
                    // 登录响应可能同时下发了风控票据（bili_ticket），记录状态便于排查
                    logAuthCookieState()
                    createAuthSession(nav)
                    _qrState.value = QrLoginState.Confirmed(nav)
                } else {
                    _qrState.value = QrLoginState.Failed("登录成功但获取账号信息失败")
                }
                return true
            }
            86090 -> { _qrState.value = QrLoginState.ScannedWaitingConfirm; return false }
            86101 -> { if (_qrState.value !is QrLoginState.WaitingScan) _qrState.value = QrLoginState.WaitingScan; return false }
            86038 -> { _qrState.value = QrLoginState.Expired; return true }
            else -> { _qrState.value = QrLoginState.Failed("二维码状态异常 ($code)"); return true }
        }
    }

    private suspend fun createAuthSession(nav: NavResult) {
        val now = clock.nowWall()
        db.withTransaction {
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "LOGIN", targetType = "auth_session",
                    targetStableId = nav.mid.toString(), occurredAt = now,
                    detailJson = null
                )
            )
            db.authSessionDao().insert(
                AuthSessionEntity(
                    authSessionId = Ids.newId(),
                    authGeneration = currentAuthGeneration() + 1,
                    accountHint = "${nav.uname}(${nav.mid})",
                    createdAt = now,
                    revokedAt = null
                )
            )
        }
    }

    suspend fun logout() {
        val now = clock.nowWall()
        val nextGeneration = currentAuthGeneration() + 1
        cookieJar.clearAll()
        db.withTransaction {
            // 真正推进凭证代际（0.4 / 248.9）：
            // currentAuthGeneration() = MAX(authGeneration)，而 revokeAll 只置 revokedAt、
            // 不改 authGeneration。原实现只把 nextGeneration 写进审计 JSON，
            // 导致退出登录后 MAX(authGeneration) 不变 —— 在途的导入任务代际校验依旧通过，
            // 仍可写入业务数据，隔离形同虚设。
            // 这里落一条 generation 更高的"已登出"会话行（accountHint=null），
            // 使 currentAuthGeneration() 立即前进，FollowImportRepository.applyImport 的
            // 代际比较随之失效并按"登录状态已变化"取消任务。
            db.authSessionDao().insert(
                AuthSessionEntity(
                    authSessionId = Ids.newId(),
                    authGeneration = nextGeneration,
                    accountHint = null,
                    createdAt = now,
                    revokedAt = now
                )
            )
            db.authSessionDao().revokeAll(now)
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "LOGOUT", targetType = "auth_session",
                    targetStableId = null, occurredAt = now,
                    detailJson = "{\"nextAuthGeneration\":$nextGeneration}"
                )
            )
        }
        _account.value = AccountState(loggedIn = false)
        _qrState.value = QrLoginState.Idle
    }

    /** 当前凭证代际 = 最新 auth_session 的 authGeneration（无会话时为 0）。 */
    suspend fun currentAuthGeneration(): Long = db.authSessionDao().currentGeneration()
}
