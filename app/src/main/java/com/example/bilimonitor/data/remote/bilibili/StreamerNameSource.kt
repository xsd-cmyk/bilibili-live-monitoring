package com.example.bilimonitor.data.remote.bilibili

import javax.inject.Inject

class StreamerNameSource @Inject constructor(private val api: BiliLiveApi) {
    suspend fun fetch(uids: List<Long>): Map<Long, String> {
        val response = api.getRoomStatusByUids(uids)
        if (response.code != 0) return emptyMap()
        return buildMap {
            for (uid in uids) {
                val info = response.data[uid.toString()] ?: continue
                val name = info.uname?.trim()
                if (info.uid == uid && !name.isNullOrEmpty()) put(uid, name)
            }
        }
    }
}
