package com.example.bilimonitor.data.local.dao

data class NameTarget(
    val id: Long, val stableId: String, val uid: Long, val nameLocked: Boolean,
    val streamerMonitorGeneration: Long, val nameRevision: Long,
    val nameAttemptWall: Long?, val nameAttemptElapsed: Long?, val nameAttemptBoot: String?,
    val nameAttemptSucceeded: Boolean
)

internal const val NAME_TARGETS = """SELECT id, stableId, uid, nameLocked, streamerMonitorGeneration, nameRevision,
    nameAttemptWall, nameAttemptElapsed, nameAttemptBoot, nameAttemptSucceeded FROM streamer WHERE deletedAt IS NULL ORDER BY id"""
