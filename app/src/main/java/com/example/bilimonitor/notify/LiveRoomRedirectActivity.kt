package com.example.bilimonitor.notify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.example.bilimonitor.MainActivity

/**
 * 通知点击的跳板（用户要求：点击通知进直播间，且**优先哔哩哔哩客户端**）。
 *
 * ## 为什么需要一个"看不见的 Activity"
 *
 * 通知的 `contentIntent` 必须在**创建通知那一刻**就确定目标，系统不会替我们"先试 A 再退 B"。
 * 于是有三种做法，各自的坑：
 *
 * 1. **创建时用 `resolveActivity` 判断客户端装没装** —— 看着最省事，但它是**静默降级**的温床：
 *    Android 11+ 的包可见性要求 `<queries>` 声明，而 `<queries>` 里的 intent 要按
 *    "intent-filter 匹配规则"去匹配对方应用的过滤器 —— 只要对方把 `bilibili://` 写成
 *    带 host/path 的过滤器，我们这条 scheme-only 的声明就可能匹配不上，`resolveActivity`
 *    恒为 null，结果**永远只跳浏览器，且没有任何报错**。这正是本项目一直在防的那类问题。
 *    它还会在"通知发出后、用户点击前卸载了客户端"时点不动。
 * 2. **直接把 `bilibili://` 当 contentIntent** —— 没装客户端的用户点了没反应（系统层 ActivityNotFound），
 *    比进浏览器更糟。
 * 3. **本类：跳板 Activity** —— 点击时才做判断，`startActivity` 直接试、捕获异常再退下一档。
 *    不依赖包可见性、不依赖对方 manifest 的写法、也不怕中途装卸。
 *
 * 代价是极短暂的一次透明 Activity（`Theme.Translucent.NoTitleBar` 且立刻 finish），
 * 换来的是"三条路径都真的走过去"，而不是"看起来配好了其实只有一条生效"。
 *
 * 跳转优先级：`bilibili://live/<roomId>` → https 直播间 → 应用内页面（[MainActivity]）。
 */
class LiveRoomRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1L).takeIf { it > 0L }
        val httpsUrl = UrlPolicy.validate(intent.getStringExtra(EXTRA_HTTPS_URL))
        val eventKey = intent.getStringExtra(EXTRA_EVENT_KEY).orEmpty()

        // ① 哔哩哔哩客户端（scheme 由对方注册，系统解析；不硬编码包名）
        if (roomId != null) {
            val opened = tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://live/$roomId")))
            if (opened) {
                finish()
                return
            }
        }
        // ② 浏览器（客户端若声明了 App Links，系统也会直接交给它）
        if (httpsUrl != null) {
            val opened = tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(httpsUrl)))
            if (opened) {
                finish()
                return
            }
        }
        // ③ 都没有：至少把用户带进应用（与"查看记录"动作同一个落点）
        tryStart(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_EVENT_KEY, eventKey)
                putExtra(MainActivity.EXTRA_URL, httpsUrl)
            }
        )
        finish()
    }

    /** 尝试启动目标；没有任何应用能接住时返回 false（而不是崩掉或静默什么都不做）。 */
    private fun tryStart(target: Intent): Boolean = try {
        startActivity(target)
        true
    } catch (e: android.content.ActivityNotFoundException) {
        android.util.Log.i("LiveRoomRedirect", "没有应用能打开 ${target.data ?: target.component}：${e.message}")
        false
    } catch (e: Exception) {
        android.util.Log.w("LiveRoomRedirect", "跳转失败：${e.message}", e)
        false
    }

    companion object {
        private const val EXTRA_ROOM_ID = "live_room_id"
        private const val EXTRA_HTTPS_URL = "live_room_https"
        private const val EXTRA_EVENT_KEY = "live_room_event_key"

        /**
         * @param roomId    直播间房间号；为 null 时不会尝试客户端深链
         * @param httpsUrl  已过 [UrlPolicy] 的 https 地址（调用方保证）
         * @param eventKey  兜底打开应用内页面时用于定位主播
         */
        fun intent(context: Context, roomId: Long?, httpsUrl: String?, eventKey: String): Intent =
            Intent(context, LiveRoomRedirectActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId ?: -1L)
                putExtra(EXTRA_HTTPS_URL, httpsUrl)
                putExtra(EXTRA_EVENT_KEY, eventKey)
            }
    }
}
