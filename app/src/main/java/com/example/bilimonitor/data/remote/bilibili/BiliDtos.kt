package com.example.bilimonitor.data.remote.bilibili

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BiliStatusResponse(
    val code: Int = -1,
    val message: String? = null,
    /** key 为主播 UID 字符串（接口以动态键返回）。 */
    val data: Map<String, BiliRoomInfo> = emptyMap()
)

/**
 * get_status_info_by_uids 的单个房间信息。
 *
 * 字段名以实测响应为准（2026-09 核对）：该接口返回 `short_id` / `area_v2_parent_name` / `cover_from_user`，
 * 而非 `short_room_id` / `parent_area_name` / `cover`；`keyframe` 在该接口恒为空串。
 */
@Serializable
data class BiliRoomInfo(
    @SerialName("uid") val uid: Long = 0,
    @SerialName("room_id") val roomId: Long = 0,
    @SerialName("short_id") val shortRoomId: Long = 0,
    /**
     * 0=未开播 1=直播中 2=轮播中。
     *
     * **必须可空**：`= 0` 会把"响应里没有这个字段"（上游改名、裁剪字段、风控降级返回部分字段）
     * 与"合法取值 0（未开播）"合并成同一件事，于是解析层判定 OFFLINE 且认为响应有效 ——
     * 连续两个 Tick 就能把所有主播同时"确认关播"，关掉正在进行的场次（时长/历史写错）
     * 并批量误报"已下播"。可空之后，缺失值会走"结果无效"而不是"未开播"。
     */
    @SerialName("live_status") val liveStatus: Int? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("uname") val uname: String? = null,
    @SerialName("face") val face: String? = null,
    @SerialName("area_v2_parent_name") val parentAreaName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    /** 批量接口用 cover_from_user（无 `cover` 字段）。 */
    @SerialName("cover_from_user") val cover: String? = null,
    @SerialName("keyframe") val keyframe: String? = null,
    /**
     * **直播间封禁信息**（官方文档对该字段的说明）。
     *
     * 取值是**日期时间字符串**（未封禁时为 `"0000-00-00 00:00:00"`），
     * 与 `room_init` 的 `lock_till`（unix 秒、`-1` 表示无期限）**不是同一种编码**，
     * 详见 [com.example.bilimonitor.domain.policy.RoomBanPolicy.parseLockTill]。
     *
     * 可空与保守取舍：这个字段**只在"存在且能解析成一个真实封禁时刻"时才被采信**，
     * 拿不到/解析不出来一律当"未知"，绝不据此判定封禁。
     */
    @SerialName("lock_till") val lockTill: String? = null,
    /** 直播间隐藏信息；与封禁是两回事，仅记录。 */
    @SerialName("hidden_till") val hiddenTill: String? = null,
    /** 房间是否被锁定（该接口并非总是返回；缺失 = 未知）。 */
    @SerialName("is_locked") val isLocked: Boolean? = null
)

/**
 * room/v1/Room/get_info 的响应。
 * 注意：原规范 8.2 引用的 xlive getRoomBaseInfo 已失效（code=0 但 data 为空），
 * 房间详情改用经典接口 get_info（实测可用）。
 * 该接口的封面字段是 `user_cover`（无 `cover`），分区父名是 `parent_area_name`。
 */
@Serializable
data class BiliRoomDetailResponse(
    val code: Int = -1,
    val message: String? = null,
    val data: BiliRoomDetail? = null
)

@Serializable
data class BiliRoomDetail(
    @SerialName("room_id") val roomId: Long = 0,
    @SerialName("short_id") val shortRoomId: Long = 0,
    @SerialName("uid") val uid: Long = 0,
    @SerialName("title") val title: String? = null,
    @SerialName("user_cover") val cover: String? = null,
    @SerialName("keyframe") val keyframe: String? = null,
    @SerialName("parent_area_name") val parentAreaName: String? = null,
    @SerialName("area_name") val areaName: String? = null,
    /** 可空理由同 [BiliRoomInfo.liveStatus]：缺失 ≠ 未开播。 */
    @SerialName("live_status") val liveStatus: Int? = null
)

/**
 * room/v1/Room/room_init 的响应 —— **唯一可辨的「直播间封禁」信号来源**。
 *
 * 为什么单独用这个接口：`get_info`（37 个字段）与批量状态接口对封禁房间的表现
 * 与"普通未开播房间"完全一致（实测），只有 `room_init` 会给出 `is_locked = true`。
 *
 * 全部字段可空：字段缺失（上游改名/裁剪）必须走"未知"，**不得**当成 `false` ——
 * 把"问不出来"当成"没被封禁"会让封禁状态在一次上游改动后静默失灵。
 */
@Serializable
data class BiliRoomInitResponse(
    val code: Int = -1,
    val message: String? = null,
    val data: BiliRoomInit? = null
)

@Serializable
data class BiliRoomInit(
    @SerialName("room_id") val roomId: Long = 0,
    @SerialName("uid") val uid: Long = 0,
    /**
     * **封禁签名**（实测）：
     *  - `true`  → 该直播间当前处于封禁状态（HTTP 200 + code 0，公开接口，免登录）；
     *  - `false` → 未封禁；
     *  - `null`  → 响应里没有这个字段 → 未知，**不判封禁**。
     */
    @SerialName("is_locked") val isLocked: Boolean? = null,
    /**
     * 封禁到期时间戳：`-1` = 无期限封禁；`> 0` = 限时封禁的到期时刻；`0`/null = 未封禁/未知。
     *
     * ★ 单位是 **unix 秒**，**不是**毫秒（2026 修复时补记的依据，用于回答"无法实测单位"这个疑问）：
     *   bilibili-API-collect 在 `xlive/web-room/v2/index/getRoomPlayInfo` 一节的字段表里
     *   把同名字段写得很直白 —— `lock_till` = 「封禁结束时间（秒级时间戳）」
     *   （<https://gitea.s1f.ren/shiran/bilibili-API-collect/raw/branch/master/docs/live/info.md>）；
     *   同一文档的 `room_init` 示例里该字段同样是 `lock_till: 0`，说明这一族接口共用一种编码。
     *   量级上也自洽：1.7e9 当秒是 2023 年，当毫秒却退回 1970 年，两者相差 1000 倍、不会混淆。
     *
     * ⚠️ 因此**不得**在链路上自行换算：归一入口只有
     *   [com.example.bilimonitor.domain.policy.RoomBanPolicy.normalizeLockTillMillis] 一个
     *   （按 <1e11 视为秒并 ×1000，所以即使上游某天改成毫秒也不会被二次放大）。
     */
    @SerialName("lock_till") val lockTill: Long? = null,
    /**
     * 隐藏房间 —— **与封禁是两回事**（实测：封禁与正常房间的该字段取值一致）。
     * 只做记录与展示，**不**参与封禁判定，也不产生第三种状态。
     */
    @SerialName("is_hidden") val isHidden: Boolean? = null,
    /**
     * 隐藏结束时间戳 —— 与 [lockTill] 同族，量级上同样是**秒**。
     *
     * 现状：**全项目零引用**（该字段既不参与判定也不展示，只随 DTO 一起解析）。
     * 因此这里没有"单位归一"的缺陷；留这条注释是为了防止日后有人直接拿它去比较或格式化 ——
     * 那时必须先过 [com.example.bilimonitor.domain.policy.RoomBanPolicy.normalizeLockTillMillis]。
     */
    @SerialName("hidden_till") val hiddenTill: Long? = null,
    @SerialName("live_status") val liveStatus: Int? = null
)
