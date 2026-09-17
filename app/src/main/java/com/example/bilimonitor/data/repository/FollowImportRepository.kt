package com.example.bilimonitor.data.repository

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.FollowImportStatus
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.entity.FollowImportStagingEntity
import com.example.bilimonitor.data.local.entity.FollowImportTaskEntity
import com.example.bilimonitor.data.local.entity.StreamerEntity
import com.example.bilimonitor.data.local.DataFreshness
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.remote.bilibili.BiliAccountApi
import com.example.bilimonitor.data.remote.bilibili.BiliAccountParsers
import com.example.bilimonitor.data.remote.bilibili.FollowingItem
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

enum class ImportSource(val label: String) {
    /**
     * 按关注列表导入。
     *
     * 设为**默认来源**：实测该接口只依赖 `SESSDATA`（扫码登录即可满足，
     * 剥离到只剩 SESSDATA 仍返回 code=0 与完整列表）。
     * 而"直播观看历史"依赖用户真的在 B 站看过直播，很多账号是空的。
     */
    FOLLOWINGS("按关注导入"),

    /** 直播观看历史导入：需要账号确实有直播观看记录，否则列表为空。 */
    LIVE_WATCH_HISTORY("直播观看历史导入")
}

/**
 * 观看历史 / 关注列表接口的 `ps` 上限（实测值）。
 * 用同一个账号 cookie 实测：ps=30 返回 code=0，ps=50 返回 {"code":-400,"message":"请求错误"}。
 * 超过上限时服务端报的是"请求错误"而非参数提示，极易被误判为风控或凭证问题。
 */
const val HISTORY_PS_MAX = 30

/**
 * 单次导入条数的**默认**上限（自定义数量与"全部获取"共用）。
 * 可在「B 站账号 / 关注导入」页面调整，见 [ImportSettingsRepository]。
 * 这既是给监控引擎的负载上限（每个主播都会按检查间隔发起请求），
 * 也是分页的保护边界。
 */
const val IMPORT_MAX_COUNT = 500

/**
 * 分页保护上限：无论服务端怎么返回，都不会超过这么多页。
 * 正常情况下 500/30 ≈ 17 页足够；留出余量是为了应对
 * "服务端忽略了 type 过滤、每页有效条数变少"这类情况时不会无限翻页。
 *
 * ★ 这条上限同时是**硬天花板**：`IMPORT_PAGE_CAP × HISTORY_PS_MAX = 1200` 条，
 *   而用户可设置的导入上限最高到 [ImportSettingsRepository.ABSOLUTE_MAX]（100000）。
 *   两者冲突时用户永远拿不到他要的条数 —— 所以触顶**必须**把原因写进任务并显示给用户
 *   （见 fetchFollowings / fetchLiveHistory 的收尾判断），不允许静默截断成"导入完成"。
 */
const val IMPORT_PAGE_CAP = 40

/**
 * 冷启动结算在途导入任务时写进 `follow_import_task.error` 的文案。
 *
 * 为什么要写这么具体：它会被「B 站账号」页原样显示给用户，用户需要知道"上次那次导入
 * 没有跑完、得重新导入"，而不是只看到一句"失败"。措辞不承诺"库里一定没写进去"：
 * 进程若死在 APPLYING 的事务提交之后，数据其实已经落库，所以提示用户核对后再重导。
 *
 * 为什么放在这里而不是 [com.example.bilimonitor.background.StartupRecovery] 里：
 * 「B 站账号」页要按它识别"这次失败是上次进程被杀结算出来的"（只对这种情况弹常驻提示，
 * 普通的导入失败用户当场已经看到过错误横幅，每次进页面再提醒一遍只是噪音）。
 * 写入方与识别方必须是**同一个**常量，否则改一处文案就会让提示静默失效。
 */
const val IMPORT_INTERRUPTED_REASON =
    "导入未完成：应用进程在导入途中结束（被杀或异常退出），任务已由冷启动结算为失败；请核对结果后重新导入"

/** HTTP 429 的**有边界**匹配：只在它作为独立数字出现时才算限流（见 [FollowImportRepository] 的判据）。 */
private val RATE_LIMIT_HTTP_CODE = Regex("(?<!\\d)429(?!\\d)")

@Serializable
data class StagedFollow(val uid: Long, val name: String, val avatarUrl: String? = null)

/**
 * 关注导入（用户定稿）：
 *  - 两种来源可选：按关注列表（默认） / 直播观看历史
 *  - 数量可调（默认 50），或勾选"全部获取"
 *  - **接口单页上限是 30，超过就分批翻页**，最终取到用户要的条数
 *  - 预览列表带搜索
 */
@Singleton
class FollowImportRepository @Inject constructor(
    private val db: AppDatabase,
    private val api: BiliAccountApi,
    private val authRepository: AuthRepository,
    private val importSettings: ImportSettingsRepository,
    private val logDao: LogDao,
    private val clock: AppClock,
    private val wbiSigner: com.example.bilimonitor.core.WbiSigner,
    @Named("appScope") private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latestTask(): FollowImportTaskEntity? = db.followImportDao().latestTask()

    /**
     * 开始一次导入。
     *
     * @param count 用户自定义的条数（`fetchAll` 为 true 时忽略）
     * @param fetchAll 全部获取：翻页直到接口没有更多数据，或触到上限
     */
    suspend fun startImport(source: ImportSource, count: Int, fetchAll: Boolean = false): String? {
        val account = authRepository.account.value
        if (!account.loggedIn) return null
        val now = clock.nowWall()
        val taskId = Ids.newId()
        val generation = authRepository.currentAuthGeneration()
        // 上限可在设置里改（默认 500）；自定义数量同样受它约束
        val ceiling = runCatching { importSettings.currentMaxCount() }.getOrDefault(IMPORT_MAX_COUNT)
        val limit = if (fetchAll) ceiling else count.coerceIn(1, ceiling)
        android.util.Log.i(
            "FollowImport",
            "开始导入：来源=${source.label}，目标=${if (fetchAll) "全部（上限 $ceiling）" else "$limit 条"}"
        )
        db.followImportDao().insertTask(
            FollowImportTaskEntity(
                importTaskId = taskId,
                authGeneration = generation,
                status = FollowImportStatus.REQUESTED,
                startedAt = now,
                finishedAt = null,
                stagingCount = 0,
                error = null
            )
        )
        scope.launch(importFailureHandler(taskId)) {
            when (source) {
                ImportSource.LIVE_WATCH_HISTORY ->
                    fetchLiveHistory(taskId, generation, account.mid, limit)
                ImportSource.FOLLOWINGS ->
                    fetchFollowings(taskId, generation, account.mid, limit)
            }
        }
        return taskId
    }

    /**
     * 抓取/落暂存的**兜底异常处理**。
     *
     * 原实现把全部后续工作丢进无异常处理器的 appScope：只有网络调用被包住，
     * `insertStaging` 等完全裸露。一旦抛出（主键冲突、库被关闭、解析异常），
     * 协程静默死掉 → 任务永久停在 REQUESTED/FETCHING → UI 的"正在获取列表"永远转圈，
     * 且没有任何重试入口（正好击穿 B5 的修复目标）。
     * 这里保证任何未捕获异常都把任务置为 FAILED 并写明原因。
     */
    private fun importFailureHandler(taskId: String) = CoroutineExceptionHandler { _, throwable ->
        val raw = throwable.message ?: throwable::class.java.simpleName
        val detail = "导入中断：$raw"
        scope.launch {
            runCatching {
                db.followImportDao().updateTask(
                    taskId, FollowImportStatus.FAILED.name, clock.nowWall(), 0, detail
                )
            }
            // ★ 兜底异常同样要进错误台账（缺陷 6）：这里原先是"只写任务表 + logcat"，
            //   而诊断包只读 application_error_log —— 导入中断在诊断包里是一片空白。
            logImportFailure(taskId, detail, appErrorOfBusinessCode(code = null, message = raw))
        }
    }

    /**
     * 失败落任务表 + 留痕。
     *
     * @param errorCode 按原因选择：网络类 → [AppError.NETWORK_UNAVAILABLE]，风控/限流 →
     *   [AppError.RATE_LIMITED]，未登录/参数被拒 → [AppError.API_REJECTED]，其它 → [AppError.UNKNOWN]。
     *   归因不许撒谎：把限流记成"网络不可用"会把用户引向错误的排查方向。
     */
    private fun fail(taskId: String, msg: String, errorCode: AppError = AppError.UNKNOWN) {
        scope.launch {
            runCatching {
                db.followImportDao().updateTask(taskId, FollowImportStatus.FAILED.name, clock.nowWall(), 0, msg)
            }
            logImportFailure(taskId, "导入失败：$msg", errorCode)
        }
    }

    /**
     * 导入失败/降级写 `application_error_log`（缺陷 6）。
     *
     * 为什么必须写：任务表的 `error` 只是"这一次导入的当前状态"，而用户按提示导出诊断包时
     * 读的是错误台账；此前导入失败只写 logcat + 任务表，诊断包里看不到任何导入失败。
     *
     * @param detail 截断到 320 字符 —— 台账用于定位问题，关键信息都在句首，
     *   长正文只会把诊断包撑大。
     */
    private suspend fun logImportFailure(taskId: String, detail: String, errorCode: AppError) {
        runCatching {
            logDao.insertAppError(
                ApplicationErrorLogEntity(
                    errorId = Ids.newId(),
                    // 用任务 id 当关联键：诊断包里能把这条错误与 follow_import_task 的那次导入对上
                    operationId = taskId,
                    occurredAt = clock.nowWall(),
                    errorCode = errorCode,
                    detail = detail.take(320)
                )
            )
        }
    }

    /**
     * 主站 GET 请求：先带 WBI 签名发一次，被拒时**再退回不带签名**发一次。
     *
     * ★ 唯一的例外是风控/限流（缺陷 7）：code=-352/-412，或消息里带 429/`Retry-After` 语义时
     *   **不再发第二次请求** —— 那只会加压、延长限流窗口，而签名与否跟风控无关，
     *   重试拿不到任何新信息。这类响应会带着"未再重试"的 note 返回，绝不伪装成"试过了"。
     *
     * 为什么要退回：签名并非所有接口都强制，而"算错签名/密钥过期"表现为稳定的
     * `-400`，与"参数非法"完全无法从返回码区分。多试一次才能把二者分开，
     * 也能避免签名环节出问题时整个导入功能直接不可用。
     *
     * URL 一律经由 `WbiSigner.queryUrl` 拼装（签名与编码共用同一份过滤后的值），
     * 不再手工拼字符串 —— 手工拼容易出现"签名按过滤值算、URL 按原值发"的不一致。
     *
     * @return 成功时是接口 JSON；失败时是异常（网络层问题）。
     */
    private suspend fun getMainSiteJson(
        baseUrl: String,
        params: Map<String, String>
    ): Result<kotlinx.serialization.json.JsonObject> {
        fun codeOf(o: kotlinx.serialization.json.JsonObject?) =
            o?.get("code")?.jsonPrimitive?.content?.toIntOrNull() ?: -1

        fun messageOf(o: kotlinx.serialization.json.JsonObject?) =
            o?.get("message")?.jsonPrimitive?.contentOrNull

        val signed = wbiSigner.sign(params)
        val first = runCatching { api.getRaw(wbiSigner.queryUrl(baseUrl, signed)) }
        val firstObj = first.getOrNull()
        // ★ 请求层就已被限流（HTTP 429 / 带 `Retry-After` 的响应会被 Retrofit 抛成异常）：
        //   这种时候"退回无签名再发一次"是纯粹的加压，直接原样交回失败原因。
        if (firstObj == null && looksRateLimited(null, first.exceptionOrNull()?.message)) {
            android.util.Log.w(
                "FollowImport",
                "首个请求已被限流（${first.exceptionOrNull()?.message}），未再重试：无签名重试同样只会加压"
            )
            return first
        }
        if (codeOf(firstObj) == 0) return Result.success(firstObj!!)
        val firstCode = codeOf(firstObj)
        val firstMsg = messageOf(firstObj)
        // ★ 风控/限流（code=-352/-412，或 message 里带 429/Retry-After/风控语义）：
        //   **不再发第二次请求**（缺陷 7）。签名与否跟风控无关，重试没有信息增益，
        //   只会延长限流窗口；原因如实写进 note，交给 UI 显示。
        if (firstObj != null && looksRateLimited(firstCode, firstMsg)) {
            android.util.Log.w(
                "FollowImport",
                "请求触发风控/限流（code=${firstCode} message=${firstMsg}），未再重试"
            )
            return Result.success(
                firstObj.withBothCodes(
                    firstCode = firstCode,
                    secondCode = null,
                    apiMessage = firstMsg,
                    note = "服务端触发风控/限流，本次未再重试（重复请求只会加重限流），请等待一段时间后再导入"
                )
            )
        }
        // 没签名密钥（或密钥已过期，sign 会返回原参数）时 signed == params：
        // 这一发是**无签名**请求，退回重试没有意义，而且失败原因里必须说清楚 ——
        // 否则用户只看到原始的 `-400 请求错误`，与"参数非法"完全无法区分。
        if (signed.size == params.size) {
            if (firstObj == null) return first
            android.util.Log.w(
                "FollowImport",
                "本次未取得 WBI 签名密钥（请求未签名），接口返回 code=${firstCode}"
            )
            return Result.success(
                firstObj.withBothCodes(
                    firstCode = firstCode,
                    secondCode = null,
                    apiMessage = firstMsg,
                    note = "未取得 WBI 签名密钥（本次请求未签名），请检查网络后重试"
                )
            )
        }

        android.util.Log.w(
            "FollowImport",
            "带签名请求被拒（code=${firstCode}），退回不带签名重试一次"
        )
        val second = runCatching { api.getRaw(wbiSigner.queryUrl(baseUrl, params)) }
        val secondObj = second.getOrNull()
        if (codeOf(secondObj) == 0) {
            android.util.Log.i("FollowImport", "不带签名反而成功，说明问题出在 WBI 签名环节")
            return Result.success(secondObj!!)
        }
        val secondCode = codeOf(secondObj)
        android.util.Log.w(
            "FollowImport",
            "不带签名同样被拒（code=${secondCode}）"
        )
        // 两次都失败：把**两个 code 一起**交回调用方。
        // 只回传第一个 code 会丢掉关键区分度：带签名 -400 而无签名 -101，
        // 说明"签名环节有问题"；两个都是 -101 才是"未登录"。
        return when {
            firstObj != null -> Result.success(
                firstObj.withBothCodes(firstCode, secondCode, messageOf(firstObj))
            )
            secondObj != null -> Result.success(
                secondObj.withBothCodes(firstCode, secondCode, messageOf(secondObj))
            )
            else -> first
        }
    }

    /**
     * 合成失败说明，塞进 `message` 字段：
     * 调用方（以及最终给用户看的文案）读的就是 `code` + `message`，这样无需改动它们的取值方式。
     *
     * @param secondCode 为 null 表示**没有发起第二次请求**：本次请求根本没签名，
     *   或者首个请求就撞上风控/限流（见 [getMainSiteJson] 里的例外）。
     * @param note 额外的、必须让用户看到的原因说明（例如"未取得签名密钥"）。
     */
    private fun kotlinx.serialization.json.JsonObject.withBothCodes(
        firstCode: Int,
        secondCode: Int?,
        apiMessage: String?,
        note: String? = null
    ): kotlinx.serialization.json.JsonObject {
        val codes = if (secondCode == null) {
            "code=$firstCode"
        } else {
            "签名请求 code=$firstCode，无签名请求 code=$secondCode"
        }
        val detail = buildString {
            if (note != null) append(note).append("：")
            append(codes)
            apiMessage?.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
        }
        return kotlinx.serialization.json.JsonObject(
            this.toMutableMap().apply { put("message", kotlinx.serialization.json.JsonPrimitive(detail)) }
        )
    }

    // ===== 原因归因与文案（缺陷 1/2/6/7 共用） =====

    /**
     * 是否属于风控/限流（缺陷 7 的判据）。
     *
     * 为什么必须单独识别：`-352`（风控校验失败）与 `-412`（请求被拦截）说明服务端**已经在拒绝我们**，
     * 此时"换成无签名再发一次"只是加压、延长限流窗口；签名与否跟风控无关，重试没有信息增益。
     * 消息文本也算判据：部分链路会把 429 / `Retry-After` 语义放进 `message` 而不是 HTTP 状态码。
     */
    private fun looksRateLimited(code: Int?, message: String?): Boolean {
        if (code == -352 || code == -412 || code == -429) return true
        val text = message?.lowercase() ?: return false
        // ★ "429" 不能用裸子串匹配（复查发现的缺陷）：错误文本里的时间戳、端口、字节数都可能
        //   含这三个数字，一旦误判就会跳过"退回无签名再试一次"，并把无关失败记成限流（归因撒谎）。
        //   要求它作为一个独立的数字出现；业务码 -429 已在上面显式列出。
        return text.contains("retry-after") || text.contains("too many requests") ||
            RATE_LIMIT_HTTP_CODE.containsMatchIn(text) ||
            text.contains("风控") || text.contains("请求过于频繁")
    }

    /**
     * 业务 code → 人话（缺陷 1）。
     * 用户看到"code=-352，风控/限流，请等待一段时间后再试"才知道该等一会儿再来；
     * 只给一个裸数字，他只能猜 —— 而猜错方向的代价是继续猛点导入、把限流拖得更久。
     */
    private fun businessCodeLabel(code: Int): String = when (code) {
        -352, -412 -> "风控/限流，请等待一段时间后再试"
        -101 -> "账号未登录或登录已失效，请重新登录"
        -400 -> "请求参数未被接受（服务端答「请求错误」，通常是参数越界或请求过于频繁）"
        else -> "服务端拒绝"
    }

    /**
     * "翻页中途被服务端拒绝、只能拿到一部分"的说明（缺陷 1）。
     *
     * 必须带上业务 code：用户与诊断包拿到这句话才能判断下一步是等一会儿（风控）、
     * 重新登录（-101）还是别的；只说"导入完成"等于把中断原因藏起来。
     */
    private fun partialReason(page: Int, code: Int, apiMessage: String?, got: Int): String {
        val said = apiMessage?.takeIf { it.isNotBlank() }?.let { "，服务端说：${it.take(80)}" }.orEmpty()
        return "第 ${page} 页被服务端拒绝（code=${code}，${businessCodeLabel(code)}${said}），本次只取到 ${got} 条"
    }

    /** 触到分页上限时的说明：两个来源共用同一句式，避免口径漂移（缺陷 2）。 */
    private fun pageCapReason(got: Int, limit: Int): String =
        "已达单次分页上限 ${IMPORT_PAGE_CAP} 页，本次取到 ${got} 条（你设置的上限是 ${limit}）"

    /**
     * 业务 code / 接口消息 → 错误台账分类码（缺陷 6）。
     * 归因不许撒谎：风控/限流必须记成 [AppError.RATE_LIMITED]，不能借"网络不可用"糊过去 ——
     * 那会把用户引向"查网络"的错误方向，也会让诊断页的归因与事实相反。
     */
    private fun appErrorOfBusinessCode(code: Int?, message: String?): AppError = when {
        looksRateLimited(code, message) -> AppError.RATE_LIMITED
        code == -101 -> AppError.API_REJECTED
        code == -400 -> AppError.API_REJECTED
        else -> AppError.UNKNOWN
    }

    /**
     * 请求层异常（Retrofit 抛出：IO 错误 / HTTP 4xx-5xx / 响应解析失败）→ 台账分类码。
     * 只有限流语义会被单独识别出来，其余按既有口径记"网络不可用"，
     * 避免把网络类问题从诊断页里抹掉。
     */
    private fun appErrorOfRequestFailure(message: String?): AppError =
        if (looksRateLimited(null, message)) AppError.RATE_LIMITED else AppError.NETWORK_UNAVAILABLE

    // ===== 直播观看历史导入（需登录，超 30 条自动分批翻页） =====

    private suspend fun fetchLiveHistory(taskId: String, generation: Long, mid: Long, limit: Int) {
        val dao = db.followImportDao()
        dao.updateTask(taskId, FollowImportStatus.FETCHING.name, null, 0, null)

        // 观看历史是**主站**接口：除登录 cookie 外通常还需要
        //   ① 设备标识 cookie（buvid3） ② WBI 签名（w_rid / wts）
        // 但这两项只是"尽量具备"，**不作为门槛**：是否真的必需由服务端决定，
        // 先发请求再看返回码（历史上正是因为在这里硬拦，导入被永久锁死）。
        val diag = authRepository.ensureMainSiteReady()
        android.util.Log.i(
            "FollowImport",
            "主站准备：${diag ?: "OK"}；wbiReady=${wbiSigner.ready()}；" +
                "凭证=${authRepository.credentialSummary()}"
        )

        // ★ 接口对 `ps` 有**硬上限 30**，超过就返回 {"code":-400,"message":"请求错误"}。
        //   所以"数量填 50 只拿到 30 条"从来不是数量没生效，而是**一次只能要 30 条**。
        //   正确做法：每页 30 条，用上一页返回的 cursor（max / view_at）翻下一页，
        //   直到攒够用户要的条数、或接口没有更多数据。
        //
        //   实测（同一账号）：type=live&business=live&ps=30 连翻 5 页，
        //   每页 30/30/30/30/14 条且**页间零重叠**，共 134 条直播记录，
        //   末页返回条数 < ps 即为结束标志；cursor.max 每页都在变化。
        val collected = LinkedHashMap<Long, FollowingItem>()
        var cursorMax: String? = null
        var cursorViewAt: String? = null
        var page = 0
        var scanned = 0
        var stoppedByCap = false
        // 抓取**中途停下**的原因（缺陷 1/2）：非 null 就说明"预览里的条目不是全部"，
        // 必须一路带到任务表与 UI；interruptCode 保留业务 code 供错误台账归因。
        var interruptReason: String? = null
        var interruptCode: Int? = null

        while (collected.size < limit && page < IMPORT_PAGE_CAP) {
            page++
            // 每页不超过 ps 上限，也不超过还缺的条数
            val pageSize = minOf(HISTORY_PS_MAX, limit - collected.size).coerceAtLeast(1)
            val params = linkedMapOf("ps" to pageSize.toString(), "type" to "live", "business" to "live")
            cursorMax?.let { params["max"] = it }
            cursorViewAt?.let { params["view_at"] = it }

            val obj = getMainSiteJson("https://api.bilibili.com/x/web-interface/history/cursor", params)
                .getOrElse {
                    // "网络请求失败"会把 HTTP 4xx/5xx（服务端其实回了话）与响应解析失败一起
                    // 误报成网络问题，这里如实说"请求失败"，错误码按限流语义区分。
                    fail(taskId, "请求失败：${it.message}", appErrorOfRequestFailure(it.message))
                    return
                }
            val code = obj["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            android.util.Log.i(
                "FollowImport",
                "观看历史第 $page 页（ps=$pageSize）：code=$code " +
                    "message=${obj["message"]?.jsonPrimitive?.content}"
            )
            // 注意：必须用 jsonPrimitive.content 读值，不能用 JsonElement.toString()。
            // kotlinx 的 toString() 返回的是**序列化文本**：字符串 primitive 会带外层双引号，
            // 于是昵称入库变成 "\"张三\""、字符串型的 mid/face 直接解析失败被静默丢弃。
            // 同包的 BiliAccountParsers 用的就是正确写法，这里原先漏改。
            if (code != 0) {
                val apiMsg = obj["message"]?.jsonPrimitive?.contentOrNull
                    ?: obj["msg"]?.jsonPrimitive?.contentOrNull
                // -101 = 账号未登录。把接口原话带上，用户才知道该去重新登录，
                // 而不是只看到一句笼统的"获取失败"。
                // 已有数据时不再整体失败：把已经拿到的部分交给用户，并说明中断原因。
                if (collected.isNotEmpty()) {
                    // ★ 部分获取必须留下原因（缺陷 1）：原实现直接 break，insertStaging 随后
                    //   把任务的 error 写成 null，UI 于是走成功分支 —— 用户要 500 条只拿到 30 条，
                    //   看到的却是"导入完成"。
                    android.util.Log.w("FollowImport", "第 ${page} 页失败（code=${code}），保留已获取的 ${collected.size} 条")
                    if (collected.size < limit) {
                        interruptReason = partialReason(page, code, apiMsg, collected.size)
                        interruptCode = code
                    }
                    break
                }
                // 一条都没拿到才整体失败；原因里带上人话标签，用户才知道该做什么。
                fail(
                    taskId,
                    "获取观看历史失败（code=${code}，${businessCodeLabel(code)}" +
                        (if (apiMsg != null) "，服务端说：$apiMsg" else "") + "）",
                    appErrorOfBusinessCode(code, apiMsg)
                )
                return
            }

            // data 缺失/形态变化：以前这里是裸 `break`，已拿到一部分时同样会静默降级成
            // "取到多少算多少"，所以原因必须记下来（与 code != 0 的处理口径一致）。
            val data = try { obj["data"]?.jsonObject } catch (e: Exception) { null }
            if (data == null) {
                if (collected.isNotEmpty() && collected.size < limit) {
                    interruptReason = "第 ${page} 页响应里没有 data 字段（接口形态可能已变化），" +
                        "本次只取到 ${collected.size} 条"
                }
                break
            }
            val list = try { data["list"]?.jsonArray } catch (e: Exception) { null }
            val rawCount = list?.size ?: 0
            scanned += rawCount
            if (rawCount == 0) break

            list?.forEach { el ->
                runCatching {
                    val o = el.jsonObject
                    // 只收直播记录。`type=live` 目前是服务端唯一生效的过滤参数
                    // （只传 business=live 会连视频 UP 主一起返回），这里再按 business
                    // 兜一层，避免接口行为变化时把视频 UP 主当主播导进来。
                    // business 缺失时退回用 uri 判断（直播记录的 uri 指向 live.bilibili.com）。
                    val business = o["history"]?.jsonObject?.get("business")?.jsonPrimitive?.contentOrNull
                    val uri = o["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val isLive = business == "live" || (business == null && uri.contains("live.bilibili.com"))
                    if (!isLive) return@runCatching
                    // 兼容数字与字符串两种形态
                    val mid2 = o["author_mid"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: o["author_mid"]?.jsonPrimitive?.longOrNull
                        ?: o["uid"]?.jsonPrimitive?.content?.toLongOrNull()
                        ?: o["uid"]?.jsonPrimitive?.longOrNull
                        ?: return@runCatching
                    if (mid2 <= 0) return@runCatching
                    val name = o["author_name"]?.jsonPrimitive?.contentOrNull
                        ?: o["uname"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    val face = o["author_face"]?.jsonPrimitive?.contentOrNull
                    // 同一主播可能出现在多页（不同场次），以第一次出现为准
                    if (!collected.containsKey(mid2)) {
                        collected[mid2] = FollowingItem(mid2, name, face)
                    }
                }
            }

            // 把已获取条数写进任务的 stagingCount，UI 就能显示"已获取 N 个"进度
            dao.updateTask(taskId, FollowImportStatus.FETCHING.name, null, collected.size.toLong(), null)

            // 结束条件：本页条数不足请求量 = 服务端已到末尾
            if (rawCount < pageSize) break

            val cursor = try { data["cursor"]?.jsonObject } catch (e: Exception) { null }
            val nextMax = cursor?.get("max")?.jsonPrimitive?.contentOrNull
            val nextViewAt = cursor?.get("view_at")?.jsonPrimitive?.contentOrNull
            // 游标没有前进就必须停，否则会原地翻页死循环
            if (nextMax == null || nextMax == cursorMax) {
                android.util.Log.w("FollowImport", "游标未前进（max=${nextMax}），停止翻页")
                // 只有在"还没取够"时才算降级：已经取够条数时提前停下属于正常收尾，
                // 给它挂一句"只取到 N 条"就是撒谎。
                if (collected.size < limit) {
                    interruptReason = "第 ${page} 页后接口未返回新游标（max 未前进），" +
                        "为避免死循环已停止翻页，本次只取到 ${collected.size} 条"
                }
                break
            }
            cursorMax = nextMax
            cursorViewAt = nextViewAt
            if (collected.size >= limit) break
            if (page == IMPORT_PAGE_CAP) stoppedByCap = true
        }

        // ★ 因页数上限停下时必须说出来（缺陷 2）：用户把上限设成 2000 时，
        //   `IMPORT_PAGE_CAP × HISTORY_PS_MAX = 1200` 就是硬天花板，静默返回 1200 条
        //   会被当成"导入完成"。stoppedByCap 只在"整页取满且游标仍在前进"时才置位，
        //   再加"没取够"这一条，才不会把正常收尾误报成截断。
        if (interruptReason == null && stoppedByCap && collected.size < limit) {
            interruptReason = pageCapReason(collected.size, limit)
        }

        if (collected.isEmpty()) {
            // 把"拿到了多少条原始记录"写进错误里，便于区分
            // 「接口真没返回直播记录」与「返回了但字段名对不上」两种情况。
            android.util.Log.w("FollowImport", "观看历史为空：扫描=$scanned 条，页数=$page")
            fail(
                taskId,
                if (scanned == 0)
                    "观看历史里没有直播记录（只有视频/专栏记录）。" +
                        "如果想按已关注的主播导入，请把来源切换为「按关注导入」"
                else "观看历史解析失败：取到 $scanned 条但均非直播记录，可能是接口字段变化"
            )
            return
        }
        val items = collected.values.toList()
        android.util.Log.i(
            "FollowImport",
            "观看历史导入：翻 $page 页、扫描 $scanned 条，去重后 ${items.size} 个主播" +
                if (stoppedByCap) "（已达分页上限 $IMPORT_PAGE_CAP 页）" else ""
        )
        if (interruptReason != null) {
            // 降级同样要留痕：诊断包里必须能看到"这次导入只拿到一部分"
            android.util.Log.w("FollowImport", "观看历史导入降级：$interruptReason")
            logImportFailure(
                taskId, "观看历史导入未取全：$interruptReason",
                appErrorOfBusinessCode(interruptCode, null)
            )
        }
        // 原因经 insertStaging 写进任务，且绝不被覆盖（缺陷 1）：UI 才能显示"这是部分结果"
        insertStaging(taskId, generation, items, interruptReason)
    }

    // ===== 关注列表导入（分批翻页，超 30 条自动继续） =====

    private suspend fun fetchFollowings(taskId: String, generation: Long, mid: Long, limit: Int) {
        val dao = db.followImportDao()
        dao.updateTask(taskId, FollowImportStatus.FETCHING.name, null, 0, null)
        // 与观看历史一致：进门前做一次主站准备（设备 cookie + **WBI 密钥**）。
        //
        // 原先这里只补设备 cookie，不碰密钥 —— 密钥过期（每日轮换）后第一发就是无签名请求。
        // 现在该接口恰好不强制签名，所以看不出问题；一旦上游收紧就是稳定的 -400，
        // 而"带签名失败 → 退回无签名"的重试路径永远不会被触发（因为根本没签）。
        // 与 ensureMainSiteReady 的既有约定一致：结果只写日志，不作为准入门槛。
        // getOrElse 而不是 getOrNull：准备阶段真的抛异常时，日志要写"异常"而不是伪装成 OK。
        val diag = runCatching { authRepository.ensureMainSiteReady() }
            .getOrElse { "主站准备异常：${it.message}" }
        android.util.Log.i(
            "FollowImport",
            "关注列表主站准备：${diag ?: "OK"}；wbiReady=${wbiSigner.ready()}；" +
                "凭证=${authRepository.credentialSummary()}"
        )
        val collected = LinkedHashMap<Long, FollowingItem>()
        var page = 1
        // 中途停下的原因（缺陷 1/2）：与观看历史共用同一条通道，最终写进任务表并显示给用户
        var interruptReason: String? = null
        var interruptCode: Int? = null
        while (collected.size < limit && page <= IMPORT_PAGE_CAP) {
            // 与观看历史同样受 ps 上限约束（超限返回 -400），统一夹到安全值
            val pageSize = minOf(HISTORY_PS_MAX, limit - collected.size).coerceAtLeast(1)
            // 关注列表同为主站接口，尽力带 WBI 签名；被拒时 getMainSiteJson 会自动退回无签名重试
            val obj = getMainSiteJson(
                "https://api.bilibili.com/x/relation/followings",
                mapOf("vmid" to mid.toString(), "ps" to pageSize.toString(), "pn" to page.toString())
            ).getOrElse {
                // 同观看历史：HTTP 层出错（4xx/5xx）时服务端其实回了话，
                // 说成"网络请求失败"就是错误归因；错误码按限流语义区分。
                fail(taskId, "请求失败：${it.message}", appErrorOfRequestFailure(it.message))
                return
            }
            val code = obj["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            if (code != 0) {
                val apiMsg = obj["message"]?.jsonPrimitive?.contentOrNull
                // 已经拿到一部分时不再整体失败：把拿到的交给用户
                if (collected.isNotEmpty()) {
                    // ★ 但"只拿到一部分"必须留下**原因**（缺陷 1）：原实现直接 break，
                    //   随后 insertStaging 把任务置 PREVIEW 且 error 传 null，把中断原因
                    //   擦得一干二净 —— UI 于是走成功分支，用户要 500 条只拿到 30 条
                    //   却看到"导入完成"。
                    android.util.Log.w("FollowImport", "关注列表第 ${page} 页失败（code=${code}），保留已获取的 ${collected.size} 条")
                    if (collected.size < limit) {
                        interruptReason = partialReason(page, code, apiMsg, collected.size)
                        interruptCode = code
                    }
                    break
                }
                // 一条都没拿到才整体失败；原因里带上人话标签，用户才知道该做什么。
                fail(
                    taskId,
                    "获取关注列表失败（code=${code}，${businessCodeLabel(code)}" +
                        (if (apiMsg != null) "，服务端说：$apiMsg" else "") + "）",
                    appErrorOfBusinessCode(code, apiMsg)
                )
                return
            }
            val items = BiliAccountParsers.parseFollowings(obj)
            if (items.isEmpty()) break
            items.forEach { if (!collected.containsKey(it.mid)) collected[it.mid] = it }
            android.util.Log.i("FollowImport", "关注列表第 $page 页：本页 ${items.size} 条，累计 ${collected.size} 个")
            // 已获取条数写进 stagingCount，UI 显示进度
            dao.updateTask(taskId, FollowImportStatus.FETCHING.name, null, collected.size.toLong(), null)
            if (items.size < pageSize) break
            page++
        }
        // ★ 页数上限截断必须说出来（缺陷 2）：关注列表这条原先连 stoppedByCap 标志都没有，
        //   用户设 2000 条时 IMPORT_PAGE_CAP(40) × ps(30) = 1200 条封顶，多出来的永远拿不到，
        //   而且没有任何提示。循环因 `page <= IMPORT_PAGE_CAP` 不成立而退出时 page = 41；
        //   再要求"没取够"，才不会把刚好取够的情况误报成截断。
        if (interruptReason == null && page > IMPORT_PAGE_CAP && collected.size < limit) {
            interruptReason = pageCapReason(collected.size, limit)
        }
        val limited = collected.values.toList()
        android.util.Log.i(
            "FollowImport",
            "关注列表导入：翻 ${page} 页，去重后 ${limited.size} 个主播" +
                if (interruptReason != null) "（未取全：${interruptReason}）" else ""
        )
        if (limited.isEmpty()) { fail(taskId, "没有获取到关注列表"); return }
        if (interruptReason != null) {
            // 降级同样要留痕：诊断包里必须能看到"这次导入只拿到一部分"
            android.util.Log.w("FollowImport", "关注列表导入降级：$interruptReason")
            logImportFailure(
                taskId, "关注列表导入未取全：$interruptReason",
                appErrorOfBusinessCode(interruptCode, null)
            )
        }
        // 原因经 insertStaging 写进任务，且绝不被覆盖（缺陷 1）：UI 才能显示"这是部分结果"
        insertStaging(taskId, generation, limited, interruptReason)
    }

    /**
     * 把抓取结果写进暂存表并置 PREVIEW。
     *
     * @param interruptReason 抓取**中途停下**的原因（部分获取 / 触到页数上限）。非 null 表示
     *   "预览里的条目不是全部"，必须写进任务并显示给用户（缺陷 1/2）。
     */
    private suspend fun insertStaging(
        taskId: String,
        generation: Long,
        items: List<FollowingItem>,
        interruptReason: String? = null
    ) {
        val now = clock.nowWall()
        val rows = items.map { f ->
            FollowImportStagingEntity(
                importTaskId = taskId,
                uid = f.mid,
                authGeneration = generation,
                payloadJson = json.encodeToString(
                    StagedFollow.serializer(),
                    StagedFollow(uid = f.mid, name = f.uname, avatarUrl = f.face)
                ),
                createdAt = now
            )
        }
        db.withTransaction { db.followImportDao().insertStaging(rows) }
        // ★ 绝不覆盖已有的中断原因（缺陷 1）：这里原来恒传 error = null，
        //   把"翻页被拒 / 触到页数上限"的说明擦得一干二净，UI 只能当成成功。
        //   参数优先；万一原因已经写在任务行上，也要保留 —— 宁可信息重复，也不许丢原因。
        val reason = interruptReason ?: db.followImportDao().getTask(taskId)?.error
        db.followImportDao().updateTask(
            taskId, FollowImportStatus.PREVIEW.name, null, rows.size.toLong(), reason
        )
    }

    /** 应用预览：只写用户勾选的 uid；代际不匹配则隔离取消。返回 (新增, 已存在, 恢复)。 */
    suspend fun applyImport(taskId: String, selectedUids: Set<Long>): Triple<Int, Int, Int> {
        val dao = db.followImportDao()
        // ★ 任务行不存在时不能静默返回 (0,0,0)（复查发现的缺陷）：UI 会把它显示成
        //   "导入完成：新增 0，恢复 0，已存在 0" —— 什么都没发生，用户却看到成功。
        //   抛出去，UI 的 runCatching 会如实显示"导入失败：…"。
        val task = dao.getTask(taskId)
            ?: throw IllegalStateException("导入任务不存在（可能已被清理），请重新导入")
        val currentGeneration = authRepository.currentAuthGeneration()
        if (task.authGeneration != currentGeneration) {
            dao.updateTask(taskId, FollowImportStatus.CANCELLED.name, clock.nowWall(), task.stagingCount, "登录状态已变化，任务已隔离取消")
            return Triple(0, 0, 0)
        }
        dao.updateTask(taskId, FollowImportStatus.APPLYING.name, null, task.stagingCount, null)
        val staging = dao.listStaging(taskId).filter { it.uid in selectedUids }
        var added = 0; var existed = 0; var restored = 0
        val now = clock.nowWall()
        // 占位昵称取配置值（可配），与 StreamerRepository 的取值口径一致
        val placeholder = runCatching { db.configDao().getConfig()?.placeholderText }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "[待获取]"
        db.withTransaction {
            for (row in staging) {
                val follow = runCatching {
                    json.decodeFromString(StagedFollow.serializer(), row.payloadJson)
                }.getOrNull() ?: continue
                val existing = db.streamerDao().findByUid(row.uid)
                if (existing != null) {
                    if (existing.deletedAt != null) {
                        db.streamerDao().restore(existing.id, now)
                        db.streamerDao().bumpStreamerMonitorGeneration(existing.id, now)
                        // 与 StreamerRepository.softDelete / importStreamer / addStreamer 的恢复分支
                        // 逐字一致：代际已前进，残留的挂起计数必须一并清除 —— 否则会被新代际的
                        // 第一次观察当成"已连续确认若干次"，可能直接凑满阈值误报状态转换（缺陷 8）。
                        db.streamerDao().deletePendingTransition(existing.id)
                        restored++
                    } else {
                        existed++
                    }
                    continue
                }
                db.streamerDao().insert(
                    StreamerEntity(
                        stableId = Ids.stableId("str"),
                        uid = row.uid,
                        roomId = null,
                        shortRoomId = null,
                        name = follow.name.ifBlank { placeholder },
                        nameLocked = false,
                        avatarUrl = follow.avatarUrl,
                        roomTitle = null,
                        parentAreaName = null,
                        areaName = null,
                        coverUrl = null,
                        liveUrl = null,
                        confirmedLiveStatus = com.example.bilimonitor.data.local.ConfirmedLiveStatus.UNKNOWN,
                        lastObservationResult = ObservationResult.INVALID,
                        observationSequence = 0,
                        streamerMonitorGeneration = 0,
                        monitoringEnabled = true,
                        isFavorite = false,
                        monitorPolicyStableId = null,
                        lastCheckedAt = null,
                        lastConfirmedAt = null,
                        freshnessStatus = DataFreshness.UNKNOWN,
                        lastLiveStartedAt = null,
                        lastLiveEndedAt = null,
                        lastError = null,
                        deletedAt = null,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                added++
            }
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "APPLY_FOLLOW_IMPORT", targetType = "follow_import_task",
                    targetStableId = taskId, occurredAt = now,
                    detailJson = "{\"selected\":${selectedUids.size},\"added\":$added,\"restored\":$restored,\"existed\":$existed}"
                )
            )
            db.streamerDao().bumpSourceDataVersion(now)
        }
        dao.updateTask(taskId, FollowImportStatus.COMPLETED.name, clock.nowWall(), task.stagingCount, null)
        return Triple(added, existed, restored)
    }

    suspend fun listStaging(taskId: String): List<StagedFollow> =
        db.followImportDao().listStaging(taskId).mapNotNull { row ->
            runCatching {
                json.decodeFromString(StagedFollow.serializer(), row.payloadJson)
            }.getOrNull()
        }
}
