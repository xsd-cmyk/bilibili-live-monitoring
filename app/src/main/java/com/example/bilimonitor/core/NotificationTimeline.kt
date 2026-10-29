package com.example.bilimonitor.core

import com.example.bilimonitor.data.local.entity.NotificationOutboxEntity

/** All persisted delivery deadlines share the wall/elapsed pair recorded on enqueue. */
fun NotificationOutboxEntity.timelineNow(clock: AppClock): Long =
    if (timelineBootId == clock.bootId() && timelineElapsed != null && timelineWall != null)
        timelineWall + (clock.nowElapsed() - timelineElapsed).coerceAtLeast(0L)
    else clock.nowWall()
