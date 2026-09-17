package com.example.bilimonitor.data.repository

/** 纯数字输入的识别方式（用户可选）。 */
enum class InputMode(val label: String) {
    /** 默认：把纯数字当作直播间房间号。 */
    ROOM("房间号"),

    /** 把纯数字当作主播 UID。 */
    UID("UID")
}

sealed interface ParsedInput {
    data class Uid(val uid: Long) : ParsedInput
    data class RoomId(val roomId: Long) : ParsedInput
}

/**
 * 添加主播的输入解析（纯函数，不依赖 Android 与数据库，便于单测）。
 *
 * 纯数字的识别方式由用户显式选择：[InputMode.ROOM]（默认）或 [InputMode.UID]。
 * 房间号与 UID 都是纯数字，字面上无法区分 —— 原实现默认按 UID 处理，
 * 使"输入房间号 / 粘贴直播间链接里的数字"这条最常见路径必然失败。
 *
 * URL 形态自带语义，不受模式影响：
 *   `live.bilibili.com/<数字>`  → 房间号（短号同样走该接口，由 B 站自行解析）
 *   `space.bilibili.com/<数字>` → UID
 */
object StreamerInputParser {

    fun parse(input: String, mode: InputMode = InputMode.ROOM): ParsedInput? {
        val text = input.trim()
        if (text.isEmpty()) return null

        // 纯数字：按用户选择的模式识别。
        // 用 toLongOrNull 而非 toLong：超长数字串（如误粘贴的长 ID）会抛
        // NumberFormatException，原实现没有捕获。
        if (text.all { it.isDigit() }) {
            val num = text.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return when (mode) {
                InputMode.ROOM -> ParsedInput.RoomId(num)
                InputMode.UID -> ParsedInput.Uid(num)
            }
        }

        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path?.trimStart('/') ?: return null
        return when {
            host == "live.bilibili.com" -> {
                val first = path.substringBefore('/').substringBefore('?')
                val num = first.toLongOrNull()
                when {
                    num == null -> null
                    first.startsWith("h5") -> null
                    else -> ParsedInput.RoomId(num)
                }
            }
            host == "space.bilibili.com" ->
                path.substringBefore('/').toLongOrNull()?.takeIf { it > 0 }?.let { ParsedInput.Uid(it) }
            else -> null
        }
    }
}
