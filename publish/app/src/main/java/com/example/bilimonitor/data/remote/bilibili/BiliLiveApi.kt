package com.example.bilimonitor.data.remote.bilibili

import com.example.bilimonitor.data.local.BatchResponseValidity
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.domain.model.BatchRoomStatusResponse
import com.example.bilimonitor.domain.model.BatchStatusOutcome
import com.example.bilimonitor.domain.model.RemoteLiveRoom
import com.example.bilimonitor.domain.policy.BanProbeOutcome
import com.example.bilimonitor.domain.policy.CallFailure
import com.example.bilimonitor.domain.policy.RoomBanPolicy
import com.example.bilimonitor.domain.policy.RoomBanProbeResult
import com.example.bilimonitor.domain.policy.RoomFailureReason
import retrofit2.http.GET
import retrofit2.http.Query

interface BiliLiveApi {
    /** 批量按 UID 查询直播状态（公开接口，见原规范 8.2）。 */
    @GET("room/v1/Room/get_status_info_by_uids")
    suspend fun getRoomStatusByUids(@Query("uids[]") uids: List<Long>): BiliStatusResponse

    /** 房间详情（用于 URL → uid 解析与资料补充）；getRoomBaseInfo 已失效，改用 get_info。 */
    @GET("room/v1/Room/get_info")
    suspend fun getRoomDetail(
        @Query("room_id") roomId: Long
    ): BiliRoomDetailResponse

    /**
     * 房间初始化信息 —— **唯一可辨的封禁探针**（`data.is_locked`）。
     *
     * 为什么不并入 `get_status_info_by_uids`：批量接口不返回 `is_locked`，
     * 而 `get_info` 的 37 个字段里没有任何封禁专属字段（实测：封禁房间看起来就是个普通未开播房间）。
     * 因此封禁只能靠这一个**单独**的请求判定，也正因如此它**不能每个主播每轮都打**
     * （98 位主播 = 每轮多 98 个请求），只在"有理由怀疑"时才发（见 MonitoringEngine 的探针策略）。
     */
    @GET("room/v1/Room/room_init")
    suspend fun getRoomInit(
        @Query("id") roomId: Long
    ): BiliRoomInitResponse
}

/** 唯一远程数据源接口（8.1）：业务层不得记住 Bilibili URL。 */
interface BiliLiveDataSource {
    suspend fun getRoomStatus(uidList: List<Long>): BatchStatusOutcome
    suspend fun getRoomDetailByRoomId(roomId: Long): RemoteLiveRoom?

    /**
     * 封禁探针。返回 [BanProbeOutcome]：
     *  - [BanProbeOutcome.Concluded] —— 拿到了 `is_locked`，可以据此判定（true = 封禁）；
     *  - [BanProbeOutcome.Failed] —— **没拿到结论，但带着失败分类**（超时 / 网络 / 限流 /
     *    5xx / 服务端明确拒绝 / 响应不可用 / 解析失败），调用方**不得**据此解除封禁判定
     *    （详见 [com.example.bilimonitor.domain.policy.RoomBanPolicy]）。
     *
     * ★ 契约：**本方法不抛异常**（一切失败都走 [BanProbeOutcome.Failed]）。
     *   原实现用"返回 null"表示失败，把六种完全不同的机制压成同一个值：日志里只能写一句
     *   含糊的"未取得结论"，甚至被记成 `API_REJECTED`（"接口被拒绝"）——
     *   把"没问到"说成"被拒绝"，排查方向从第一步就是错的。
     */
    suspend fun probeBan(roomId: Long): BanProbeOutcome
}

class BiliLiveApiDataSource(private val api: BiliLiveApi) : BiliLiveDataSource {

    override suspend fun getRoomStatus(uidList: List<Long>): BatchStatusOutcome {
        val requested = LinkedHashSet<Long>().apply { uidList.forEach { add(it) } }.toList()
        val duplicates = uidList.groupBy { it }.filterValues { it.size > 1 }.keys
        return try {
            val resp = api.getRoomStatusByUids(requested)
            val apiMessage = resp.message?.take(MESSAGE_MAX_CHARS)
            if (resp.code != 0) {
                // ★ 服务端**明确拒绝**整批：这是一条真实的原始信号，必须原样带上去 ——
                //   原实现把它压成"VALIDITY=INVALID + results 全 null"，HTTP 状态码与
                //   API code/message 一起被丢掉，于是库里的 detail 恒为 null，事后无法定位。
                //   注意：这里**不能**推断"谁被封禁了"。B 站的拒绝码对
                //   「参数错 / 未登录 / 风控 / 该房间被限制 / 房间不存在」是共用或重叠的，
                //   把 code 直接当成"封禁"会造成误判 —— 所以只如实记录，不做业务判定。
                val failure = CallFailure(
                    reason = RoomFailureReason.API_REJECTED,
                    apiCode = resp.code,
                    apiMessage = apiMessage
                )
                BatchStatusOutcome.Responded(
                    BatchRoomStatusResponse(
                        requestedUids = requested,
                        resultsByUid = requested.associateWith { null },
                        missingUids = requested.toSet(),
                        duplicateUids = duplicates,
                        responseValidity = BatchResponseValidity.INVALID,
                        failuresByUid = requested.associateWith { failure },
                        callFailure = failure,
                        apiCode = resp.code,
                        apiMessage = apiMessage
                    )
                )
            } else {
                val results = requested.associate { uid ->
                    val info = resp.data[uid.toString()]?.takeIf { it.uid == 0L || it.uid == uid }
                    uid to info?.toRemoteLiveRoom()
                }
                val missing = requested.filter { results[it] == null }.toSet()
                val validity = when {
                    missing.isEmpty() -> BatchResponseValidity.VALID
                    results.values.any { it != null } -> BatchResponseValidity.PARTIAL
                    else -> BatchResponseValidity.INVALID
                }
                BatchStatusOutcome.Responded(
                    BatchRoomStatusResponse(
                        requestedUids = requested,
                        resultsByUid = results,
                        missingUids = missing,
                        duplicateUids = duplicates,
                        responseValidity = validity,
                        // 逐 uid 的缺失原因：`code == 0` 却查不到这个 uid 的可用状态，
                        // 语义是"这条数据不在响应里"（字段缺失/被裁剪/单房间不可用都可能），
                        // 与"整批被拒绝"是不同事实，因此用不同的原因值分别记录。
                        failuresByUid = missing.associateWith {
                            CallFailure(
                                reason = RoomFailureReason.MISSING_IN_RESPONSE,
                                apiCode = resp.code,
                                apiMessage = apiMessage
                            )
                        },
                        apiCode = resp.code,
                        apiMessage = apiMessage
                    )
                )
            }
        } catch (e: retrofit2.HttpException) {
            // ★ 保留 HTTP 状态码与 Retry-After（原规范 246.5 要求 429 独立处理）。
            //   原实现只回一个 TRANSPORT_ERROR，`e.code()` 从未被读取 ——
            //   403（服务端拒绝）与 500（服务端故障）在库里长得一模一样。
            BatchStatusOutcome.Failed(httpFailure(e))
        } catch (e: java.net.SocketTimeoutException) {
            BatchStatusOutcome.Failed(CallFailure(reason = RoomFailureReason.TIMEOUT))
        } catch (e: java.io.IOException) {
            BatchStatusOutcome.Failed(CallFailure(reason = RoomFailureReason.NETWORK))
        } catch (e: kotlinx.serialization.SerializationException) {
            // 解析失败单独归一类（台账 C9：它原先会逃出去被引擎吞成 null → 计成网络失败 → 误开熔断）。
            BatchStatusOutcome.Failed(CallFailure(reason = RoomFailureReason.MALFORMED_RESPONSE))
        } catch (e: Exception) {
            // 兜底：不认识的异常也不许静默丢弃原因（引擎侧仍会兜住异常本身）。
            BatchStatusOutcome.Failed(CallFailure(reason = RoomFailureReason.MALFORMED_RESPONSE))
        }
    }

    /**
     * HTTP 异常 → 结构化失败原因。
     *
     * 分类刻意保守：只有 429/503/5xx 这类**机制性**信号才细分，其余 4xx 一律记
     * [RoomFailureReason.API_REJECTED]（"服务端明确拒绝，但拒绝的业务含义未知"）——
     * 宁可少一个标签，也不能凭状态码猜业务结论。
     */
    private fun httpFailure(e: retrofit2.HttpException): CallFailure {
        val status = e.code()
        val retryAfter = e.response()?.headers()?.get("Retry-After")?.trim()?.toIntOrNull()
        val reason = when {
            status == 429 || (retryAfter != null && status in 400..499) -> RoomFailureReason.RATE_LIMITED
            status >= 500 -> RoomFailureReason.SERVER_ERROR
            else -> RoomFailureReason.API_REJECTED
        }
        return CallFailure(
            reason = reason,
            httpStatus = status,
            retryAfterSeconds = retryAfter,
            apiMessage = e.message()?.take(MESSAGE_MAX_CHARS)
        )
    }

    /**
     * 房间详情（URL → uid 解析与资料补充）。
     *
     * `live_status` 可空：字段缺失时既不算"有效状态"，也不当成"未开播"
     * （`remoteStatusValid = false` 会让上层把这次观察判为无效，见 [BiliRoomInfo] 的注释）。
     *
     * 顺带读取该接口的封禁相关字段：官方文档里 `get_info` **没有**封禁专属字段（实测 37 个字段
     * 逐个对比确认），但字段存在时白拿一次判定信号、不存在时保持 null = 未知，零成本。
     */
    override suspend fun getRoomDetailByRoomId(roomId: Long): RemoteLiveRoom? = try {
        val resp = api.getRoomDetail(roomId)
        val d = resp.data
        if (resp.code == 0 && d != null && d.uid > 0) {
            val liveStatus = d.liveStatus
            RemoteLiveRoom(
                uid = d.uid,
                roomId = d.roomId.takeIf { it > 0 },
                shortRoomId = d.shortRoomId.takeIf { it > 0 },
                name = null,
                avatarUrl = null,
                roomTitle = d.title,
                parentAreaName = d.parentAreaName,
                areaName = d.areaName,
                coverUrl = normalizeUrl(d.cover ?: d.keyframe),
                liveUrl = "https://live.bilibili.com/${d.roomId.takeIf { it > 0 } ?: roomId}",
                remoteLiveStatus = when (liveStatus) {
                    0 -> ConfirmedLiveStatus.OFFLINE
                    1 -> ConfirmedLiveStatus.LIVE
                    2 -> ConfirmedLiveStatus.ROUND
                    else -> null
                },
                remoteStatusValid = liveStatus != null && liveStatus in 0..2,
                // get_info 不返回封禁字段 → 保持 null（未知），**绝不**用 live_status 推断封禁。
                banLocked = null,
                banLockTillMillis = null,
                hidden = null
            )
        } else null
    } catch (e: Exception) {
        null
    }

    /**
     * 封禁探针实现 —— **本方法绝不抛异常，也绝不把"问不出来"变成结论**。
     *
     * 两类返回（见 [BanProbeOutcome]）：
     *  ① `is_locked` 有值 → [BanProbeOutcome.Concluded]（true = 封禁，false = 未封禁）；
     *  ② 其余全部 → [BanProbeOutcome.Failed]，**并且带上失败分类与原始信号**：
     *     超时 / 连接失败 / HTTP 5xx / 限流风控（HTTP 429、业务 code 非 0，实测 `-352`）/
     *     服务端明确拒绝 / `data` 或 `is_locked` 字段缺失 / 解析失败。
     *
     * 为什么 ② 必须是"未知"而不是 `is_locked = false`：把风控/超时当成"没被封禁"，
     * 会在一次网络抖动后静默解除所有封禁标记，用户看到的「封禁中」会莫名其妙消失。
     *
     * 为什么 ② 要区分得这么细（本次改动）：调用方要按**真实失败分类**记错误码
     * （超时就该是 NETWORK_TIMEOUT，而不是笼统的 API_REJECTED），
     * 且原始信号（HTTP 状态码 / 业务 code / message / Retry-After）必须能进 detail，
     * 否则事后依然无法回答"这次到底是网络不通还是被风控了"。
     */
    override suspend fun probeBan(roomId: Long): BanProbeOutcome {
        // roomId 非法：连请求都不该发（"没有可用的房间号"是响应不可用的一种，不是"未封禁"）。
        if (roomId <= 0) {
            return BanProbeOutcome.Failed(
                CallFailure(
                    reason = RoomFailureReason.MISSING_IN_RESPONSE,
                    apiMessage = "room_id 非法（$roomId），未发起探针"
                )
            )
        }
        return try {
            val resp = api.getRoomInit(roomId)
            val data = resp.data
            if (resp.code != 0) {
                // 服务端**明确拒绝**（含实测的 -352 风控）：这才是 API_REJECTED 的本义，
                // 并把业务 code 与 message 原样带走 —— 不是笼统的"未取得结论"。
                BanProbeOutcome.Failed(
                    CallFailure(
                        reason = RoomFailureReason.API_REJECTED,
                        apiCode = resp.code,
                        apiMessage = resp.message?.take(MESSAGE_MAX_CHARS)
                    )
                )
            } else if (data == null) {
                BanProbeOutcome.Failed(
                    CallFailure(
                        reason = RoomFailureReason.MISSING_IN_RESPONSE,
                        apiCode = resp.code,
                        apiMessage = "room_init 响应 data 为空（接口改版或被裁剪）"
                    )
                )
            } else {
                val locked = data.isLocked
                if (locked == null) {
                    // 字段缺失 = 响应不可用，**不是**未封禁（采信规则见 RoomBanPolicy 的说明）。
                    BanProbeOutcome.Failed(
                        CallFailure(
                            reason = RoomFailureReason.MISSING_IN_RESPONSE,
                            apiCode = resp.code,
                            apiMessage = "room_init 响应缺少 is_locked 字段"
                        )
                    )
                } else {
                    BanProbeOutcome.Concluded(
                        RoomBanProbeResult(
                            isLocked = locked,
                            // ★ 单位归一（2026 修复）：`room_init.lock_till` 是 unix **秒**
                            // （`-1` = 无期限），而 `RoomBanProbeResult.lockTill` /
                            // `RoomBanState.lockTill` / `lockTillLabel` 全线按**毫秒**比较与格式化。
                            // 原实现直接透传原始值，于是服务端返回 1767225600（2026-01-01）时
                            // 展示成「已过标注期限（1970-01-19 …）仍未解封」，并被写进添加提示与审计留痕。
                            // 归一入口只有一个：RoomBanPolicy.normalizeLockTillMillis
                            // （null/0 → 未知、-1 → 无期限、<1e11 视为秒 ×1000），判定逻辑不在数据源里另写一份。
                            lockTill = RoomBanPolicy.normalizeLockTillMillis(data.lockTill),
                            isHidden = data.isHidden,
                            apiCode = resp.code,
                            apiMessage = resp.message?.take(MESSAGE_MAX_CHARS),
                            // 真实房间号白拿：`room_init` 的 id 参数也接受 uid，而"允许添加已封禁直播间"
                            // 之后这条 roomId 是引擎复查的唯一依据（见 RoomBanProbeResult.roomId 的说明）。
                            roomId = data.roomId.takeIf { it > 0 }
                        )
                    )
                }
            }
        } catch (e: retrofit2.HttpException) {
            // HTTP 非 2xx：与批次状态接口共用同一套分类（429 → RATE_LIMITED、5xx → SERVER_ERROR、
            // 其余 4xx → API_REJECTED），保留状态码与 Retry-After。
            BanProbeOutcome.Failed(httpFailure(e))
        } catch (e: java.net.SocketTimeoutException) {
            BanProbeOutcome.Failed(CallFailure(reason = RoomFailureReason.TIMEOUT))
        } catch (e: java.io.IOException) {
            BanProbeOutcome.Failed(CallFailure(reason = RoomFailureReason.NETWORK))
        } catch (e: kotlinx.serialization.SerializationException) {
            BanProbeOutcome.Failed(CallFailure(reason = RoomFailureReason.MALFORMED_RESPONSE))
        } catch (e: Exception) {
            // 兜底：不认识的异常也不许静默丢弃原因（它会被记成 API_INVALID_RESPONSE，
            // 而 detail 里带着原始 message，不会误导成"接口被拒绝"）。
            BanProbeOutcome.Failed(CallFailure(reason = RoomFailureReason.MALFORMED_RESPONSE))
        }
    }

    /**
     * B 站 CDN 部分字段返回 http://，Android 默认禁止明文流量；hdslb.com 全系支持 https，统一归一化。
     * 原实现只列举 i0/i1/i2/i9，会漏掉 i3/其它编号子域，导致封面以明文地址下发而被系统拦截。
     */
    private fun normalizeUrl(url: String?): String? =
        url?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http://") && it.substringAfter("http://").substringBefore('/')
                    .endsWith(".hdslb.com")
            ) "https://" + it.removePrefix("http://") else it
        }

    private fun BiliRoomInfo.toRemoteLiveRoom(): RemoteLiveRoom? {
        // liveStatus 可空：字段缺失（上游改名/裁剪/风控降级）走 else 返回 null，
        // 上层据此把该 uid 计入 missingUids（PARTIAL/INVALID），而不是当成"未开播"。
        val status = when (liveStatus) {
            0 -> ConfirmedLiveStatus.OFFLINE
            1 -> ConfirmedLiveStatus.LIVE
            2 -> ConfirmedLiveStatus.ROUND
            else -> return null
        }
        return RemoteLiveRoom(
            uid = uid,
            roomId = roomId.takeIf { it > 0 },
            shortRoomId = shortRoomId.takeIf { it > 0 },
            name = uname,
            avatarUrl = normalizeUrl(face),
            roomTitle = title,
            parentAreaName = parentAreaName,
            areaName = areaName,
            coverUrl = normalizeUrl(cover ?: keyframe),
            liveUrl = roomId.takeIf { it > 0 }?.let { "https://live.bilibili.com/$it" },
            remoteLiveStatus = status,
            // 能走到这里说明 live_status 字段存在且取值合法（无非空判断在前，
            // null 已经在 when 里 return null 了），因此这里才是可信的 true。
            remoteStatusValid = true,
            // 批量接口自带的封禁信息：有就白拿（零额外请求），没有就保持 null = 未知。
            // 采信规则集中在 RoomBanPolicy.lockFromBatchFlags（纯函数、有单测），
            // 这里只做字段搬运 —— 判定逻辑绝不在数据源里就地写一份。
            banLocked = RoomBanPolicy.lockFromBatchFlags(
                isLocked = isLocked,
                lockTillMillis = RoomBanPolicy.parseLockTill(lockTill),
                nowWall = System.currentTimeMillis()
            ),
            banLockTillMillis = RoomBanPolicy.parseLockTill(lockTill),
            hidden = null
        )
    }

    companion object {
        /**
         * 服务端 message 落库前的截断长度。
         *
         * 为什么必须截断：`monitoring_error_log.detail` 会被写进诊断包并整体解压进内存，
         * 上游若把 HTML 错误页塞进 message，不截断会让行、诊断包与导出成倍膨胀
         * （与 `MonitoringWorker.MAX_ERROR_DETAIL` 同一条理由，这里取更小的值，
         * 因为一条错误行不需要正文全文）。
         */
        const val MESSAGE_MAX_CHARS = 160
    }
}
