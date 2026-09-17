# 只读代码审查报告（Repository / Remote / Converter / Domain）

审查范围（逐行阅读）：`data/repository/*`（Backup、Export、History、Streamer、StreamerInputParser、Auth、FollowImport、Health、Config、Maintenance、Monitor、Stats、StatsSeries）、`data/remote/bilibili/*`、`data/local/convert/DshTypeConverters.kt`、`domain/model/DomainModels.kt`，并交叉核对 `data/local/dao/*`、`data/local/entity/*`、`data/local/db/AppDatabase.kt`、`di/AppModule.kt`。

**未执行任何编译 / gradle 命令，未修改任何文件。** 结论分三档：确定是 bug / 疑似 / 风格。凡标注「已核对」的条目均已用 DAO / 实体 / 枚举的一手定义逐项确认。

---

## 一、确定是 bug

### 1. 【严重】`AppDatabaseVersionProvider.version = 5`，而数据库实际是 6 → 备份清单里的数据库版本永远是错的

`BackupRepository.kt:724-726`

```kotlin
object AppDatabaseVersionProvider {
    const val version = 5      // ❌ 硬编码
}
```

一手证据：`AppDatabase.kt:110` 是 `version = 6`，`AppMigrations.kt:201-213` 有 `V5_6__versionNormalization` 且已注册进 `ALL`。注释自称「编译期由 AppDatabase.version 提供」，实际是一个**与世界脱节的手写常量**。

两处后果：

- **写出方向**（`BackupRepository.kt:222` `roomSchemaVersion = AppDatabaseVersionProvider.version`）：当前 v6 数据库导出的备份，清单里写的是 `roomSchemaVersion = 5`。把这份备份拿到真正 v5 的旧版 App 上导入，`precheck`（`BackupRepository.kt:319`）算 `5 > 5 == false` → **放行**，但备份里可能含 v6 才有的列/表语义 → 静默丢数据。
- **读入方向**（`BackupRepository.kt:319`）：`manifest.roomSchemaVersion > AppDatabaseVersionProvider.version` 这个守卫的上界是 5 而不是 6（更不是未来的 7）。**来自 v7+ 数据库的备份（真实值 7）会被判为「不新于本地 5」而放行**，正是这条守卫想拦的场景。而且 `BackupRepository.kt:320` 的文案「备份来自更新的数据库结构」会永远不触发。

修法：删掉这个常量，`precheck`/`serializeBackup` 直接读 `db.openHelper.readableDatabase.version`（或 `RoomDatabase` 的版本常量），保证单一来源。

---

### 2. 【严重】`applyRestore` 从不获取维护权，但注释与 UI 都声称「已进入维护模式，监控暂停」

`BackupRepository.kt:394-398`（KDoc）、`BackupRepository.kt:398-668`（实现）、对照 `ExportRepository.kt:45`、`MaintenanceRepository.kt:173`

KDoc 明确写着「应用恢复（0.6.26 / 0.6.33.1）：**单维护事务**；APPLYING 是唯一允许写业务表的阶段」。但：

```
grep enterMaintenance 全工程 → 只有 3 处调用点：
  ExportRepository.kt:45        (MaintenanceMode.BACKUP)
  BackupRepository.kt:251       (MaintenanceMode.BACKUP，属 exportBackup)
  MaintenanceRepository.kt:173  (MaintenanceMode.DATABASE_REPAIR)
```

**`applyRestore`（以及 `precheck`）里没有任何 `enterMaintenance` / `exitMaintenance`**。`MaintenanceMode.RESTORE`（`Enums.kt:24`）是个从未被写入的枚举值。

为什么这是真 bug（不是「只是少了把锁」）：维护权的唯一作用就是让监控引擎停写。DAO 层的门是 `MonitorDaos.kt:45-51` 的 `AND maintenanceMode = 'OFF'`（`casConfirmedObservation`）与 `ConfigDaos.kt:146`、`167`（租约获取/续期）。维护权没拿 → 恢复事务（`BackupRepository.kt:426-655`）进行期间监控引擎**仍可**获取租约、仍可通过 `casConfirmedObservation` 写 `streamer`、仍可插 `live_session`。具体可触发的坏事：

- `BackupRepository.kt:507` 先快照 `listAll()` 得到 `localSessionStableIds`，随后逐条 insert。引擎在此期间插入新场次 → `live_session.sessionKey`（唯一索引 `CoreEntities.kt:129`）冲突 → 整个恢复事务抛 `SQLiteConstraintException` 回滚，而 `RestoreRunEntity` 已被 `casTransition` 打到 `APPLYING`（`BackupRepository.kt:402`），只有下次冷启动的 `StartupRecovery.kt:57-65` 才把它标 FAILED。
- `BackupRepository.kt:646` `bumpSourceDataVersion` 与引擎并发的版本递增相互覆盖统计缓存代际。

UI 侧的谎报：`DataScreen.kt:200-205` 在 `busy = true` 时固定显示「正在操作（已进入维护模式，监控暂停）…」，而 `applyRestore` 的 `busy` 期间并没有进入维护模式。

---

### 3. `precheck` 把 `sessionsToRestore` 算了两遍（可恢复场次数虚高）

`BackupRepository.kt:349-371`

```kotlin
for (bs in file.body.streamers) {
    ...
    byStable != null && byStable.uid == bs.uid -> {
        sessionsToRestore += file.body.sessions.count { it.streamerStableId == bs.stableId }   // 第 1 次
    }
    ...
}
sessionsToRestore += file.body.sessions.count { s ->                                          // 第 2 次（重叠）
    val local = localByStable[s.streamerStableId]
    val key = "LIVEsession:${s.stableId}"
    (local == null || local.uid == file.body.streamers.firstOrNull { it.stableId == s.streamerStableId }?.uid) &&
        key !in existingSessionKeys && s.streamerStableId.isNotBlank()
}
```

第 2 段的「主播已在本地且 uid 一致」分支与第 1 段统计的是**同一批场次**，因此凡是「主播已存在」的备份，场次数都会被重复加一次。实测口径：备份含 1 个本地已有主播 + 10 场未存在的场次 → `sessionsToRestore = 20`。该值经 `PrecheckResult.Ready`（`BackupRepository.kt:295-301`）透出到数据页确认文案。

顺带两个同处的疑点（同一段代码）：

- `localByStable[s.streamerStableId]` 里的 `local` 取自**本地已有**主播表，用它去和**备份里**的同 stableId 主播比 uid（`file.body.streamers.firstOrNull`），在「stableId 冲突但 uid 不同」这条已由冲突矩阵单独处理的路径上语义是混乱的。
- `s.streamerStableId.isNotBlank()` 两个循环都没校验，而 `applyRestore` 的 `restoreSessionReason`（`BackupRepository.kt:676`）只在 `ownedStableIds` 里找，空串必然被跳过 → 预检说「可恢复」、实际必然跳过。

---

### 4. `FollowImportRepository` 用 `JsonElement.toString()` 解析 JSON 字符串字段 → 导入的昵称带引号 / 数值字段静默丢弃

`FollowImportRepository.kt:110-120`

```kotlin
val mid2 = o["author_mid"]?.toString()?.toLongOrNull()
    ?: o["uid"]?.toString()?.toLongOrNull() ?: return@runCatching
val name = o["author_name"]?.toString() ?: o["uname"]?.toString() ?: ""
val face = o["author_face"]?.toString()
```

kotlinx.serialization 的 `JsonElement.toString()` 返回的是**序列化后的 JSON 文本**，不是内容：字符串型 primitive 会带外层双引号（`JsonPrimitive("张三").toString() == "\"张三\""`），数值型则不带。

后果（按字段类型分叉，两种都是真实缺陷）：

- 若 `author_name` 是字符串（几乎必然）→ 入库 `StreamerEntity.name = "\"张三\""`、`StagedFollow.name` 同理，`db.streamerDao().insert`（`FollowImportRepository.kt:205` / `StreamerRepository.kt:110`）会把带引号的昵称**永久写进业务表**（且 `nameLocked = false`，要等下一次远程刷新才可能被覆盖，而远程 `uname` 为空时又不会覆盖）。
- 若 `author_mid` 是字符串 → `toLongOrNull()` 恒 null，若 `uid` 也不存在则整条 `return@runCatching` **静默丢弃**，用户看到的是「导入成功但少了几个人」。

同文件包内的 `BiliAccountParsers` 用的是正确写法（`BiliAccountApi.kt:87-89` 的 `o["mid"]?.jsonPrimitive?.content`），说明这是漏改而不是风格选择。

顺带：`BackupRepository.kt:264` 的 `detailJson = "{\"sessions\":${file.manifest.recordCounts["sessions"]}}"` 在 `recordCounts["sessions"]` 为 null 时会写出非法 JSON `{"sessions":null}` —— 这个键在 `serializeBackup` 里恒存在，所以当前不可达（`recordCounts` 的 `Map<String, Long>` 值类型也使 `?.toString()` 是多余的）。

---

### 5. `FollowImportRepository` 的抓取/落暂存没有异常兜底 → 任务永久停在 REQUESTED/FETCHING

`FollowImportRepository.kt:74-83`、`85-89`、`152-168`

```kotlin
scope.launch {
    when (source) {
        ImportSource.LIVE_WATCH_HISTORY -> fetchLiveHistory(taskId, generation, account.mid, capped)
        ...
    }
}
```

`startImport` 先用 `FollowImportStatus.REQUESTED` 落库（`:63-73`），随后把全部后续工作丢进 `scope.launch`。`scope` 是 `@Named("appScope")` 的 `SupervisorJob`（`AppModule.kt:50`），**没有 CoroutineExceptionHandler**。而 `fetchLiveHistory` / `fetchFollowings` 只包住了网络调用（`:97-99`、`:136-138`），下半段的：

- `fail()` 自身是 `scope.launch { updateTask(...) }`（`:86-88`）——另一条无兜底的协程，失败也没有任何记录；
- `insertStaging` → `db.withTransaction { insertStaging(rows) }` + `updateTask(..., PREVIEW, ...)`（`:166-167`）完全裸露。

于是 `SQLiteConstraintException`（例如 `FollowImportStagingEntity` 主键冲突，`ConfigEntities.kt:134`）、`IllegalStateException`（数据库关闭）等任一异常都会让协程静默死掉，任务**永远停在 FETCHING**，UI 侧「正在获取列表」的进度对话框永远转圈 —— 而台账 B5 的修复目标恰恰是「只要发起过导入就给出明确反馈，失败/超时要有重试入口」，这里给它留了一个无出口的分支。

代码里已经有一只脚踩进去了：`:102-107` 用了 `try/catch`，`:136-140` 用 `runCatching`，但 `fail()` 与 `insertStaging()` 都没管。

---

### 6. 三处「同一实体的两条路径行为不一致」

**(a) 恢复软删主播时漏掉 `deletePendingTransition`**

- `StreamerRepository.kt:79-90`（`addStreamer` 的恢复分支）：`restore` + `bumpStreamerMonitorGeneration` + **`deletePendingTransition`** + 审计。
- `StreamerRepository.kt:242-246`（`importStreamer` 的恢复分支）：只有 `restore` + `bumpStreamerMonitorGeneration`，**没有 `deletePendingTransition`**，也没有审计。

`deletePendingTransition`（`StreamerDao`，定义于 `MonitorDaos.kt:244`）删除的是 `streamer_pending_transition` 行；而代际已经 `bump` 过，遗留的挂起行（holding 旧 `monitorGeneration`/`streamerMonitorGeneration`/`confirmationCount`）不会被清理，一旦该主播重新进入监控，确认计数会带着上一个代际的残留（台账批次 7 刚修过同类的「方向切换带走计数」问题）。这条路径当前是**半死代码**（`importStreamer` 全工程无调用点，见 §9），但既然保留了就必须与 `addStreamer` 同口径。

**(b) 直接落库路径不写 placeholderText，写死字面量**

`StreamerRepository.kt:252`、`FollowImportRepository.kt:205` 都用 `name.ifBlank { "[待获取]" }`，而配置里 `placeholderText` 是可配的（`MonitoringConfigSnapshot.placeholderText`，`DomainModels.kt:87`），并且 `addStreamer`（`StreamerRepository.kt:100-102`）走的就是 `db.configDao().getConfig()?.placeholderText ?: "[待获取]"`。用户改了占位文案后，导入路径仍写默认文案。

**(c) 备份清单里的 appVersionName 写死**

`BackupRepository.kt:238` `appVersionName = "1.0"`，而 `app/build.gradle.kts:21` 是 `versionName = "1.1"`。backupId/时间/设备都在动态取值，唯独版本号是常量 → 之后排查「某个备份来自哪一版」时直接失效。

---

### 7. 导出状态机：维护权提前释放 + 异常路径把快照永久留在 CONSUMING

`ExportRepository.kt:88-110`

```kotlin
        } finally {
            runtimeLockRepository.exitMaintenance(MaintenanceMode.BACKUP)   // :89 释放维护权
        }

        // 6-7. 分页读取临时表并写出
        val exportDao = db.exportDao()
        check(exportDao.casStatus(snapshotId, READY, CONSUMING) > 0) { ... }  // :96-98
        val rows = exportDao.listSessionRows(snapshotId)                     // :99 无锁读快照
        val html = buildHtml(...)
        writeToDownloads(fileName, html, "text/html")                        // :106
        check(exportDao.casStatus(snapshotId, CONSUMING, COMPLETED) > 0) { ... } // :108-110
        logDao.insertAudit(...)
```

- **维护权窗口不覆盖整个导出**：快照内容的复制在事务里完成（`:58-85`），但 `listSessionRows` 读取、HTML 构建与文件写出（`:99-106`）都在释放维护权之后。4-4 的两处 `check` 本身不会误伤正常路径（`@Query UPDATE ... WHERE status = :expectedCurrent` 返回受影响行数，`ConfigDaos.kt:575-576` 定义正确；`READY→CONSUMING`、`CONSUMING→COMPLETED` 与 `:84` 的 `BUILDING→READY` 构成闭环，已核对 `ExportSnapshotStatus` 枚举 `Enums.kt:79`），但窗口之外仍可能被 `MaintenanceRepository.runCleanup`（`:279` `exportDao.deleteExpired`）、`StartupRecovery.kt:53-54`（把 `findUnfinished` 的快照整批 CAS 成 `CANCELLED`）介入 —— 后者尤其致命：本次 `exportHtml` 只要是在 App 冷启动竞态里跑的，`READY→CONSUMING` 会返回 0，用户看到的是「导出失败」而不是「被回收」，而 `finally` 已经把维护权交还。
- **CONSUMING 是唯一没人回收的状态**：若在 `:96-98` 成功之后、`:108-110` 之前抛异常（`listSessionRows` 抛错、`writeToDownloads` 的 `IllegalStateException`、协程取消），快照就停在 `CONSUMING`。`StartupRecovery` 只取消**带行**的快照（`findUnfinished` 后 `casStatus(..., CANCELLED)` 对空快照才有效），`deleteExpired`（`ConfigDaos.kt:590-591`）只按 `expiresAt` 删，而本次导出的 `expiresAt = now + 24h`（`:52`）—— 这个孤儿行在最坏情况下要挂 24 小时，期间诊断包 `migration_info` 会持续暴露「状态机与真实进度不一致」，正是这次改动想消除的现象。

---

## 二、疑似（有据但依赖运行时/外部事实，未实测）

### 8. `HealthRepository` 用「盲区原因」的函数去渲染「问题类型」，且丢弃了问题的 scope

`HealthRepository.kt:101-102`

```kotlin
problems.isNotEmpty() ->
    MonitorHealthStatus.DEGRADED to "存在问题：${gapReasonLabel(problems.first().problemKey.substringAfter(':').substringBefore(':'))}"
```

已核对的 `problemKey` 构造规则（`AppClock.kt:57-60`）是 `"{component}:{problemType}:{scopeKey}[:{episodeId}]"`，实际写入点：

- `MonitoringEngine.kt:392,396`：`MONITORING:NETWORK_UNAVAILABLE:SYSTEM:<episodeId>`
- `MonitorRepository.kt:453`：`MONITORING:UNKNOWN:<streamerStableId>`
- `MaintenanceRepository.kt:62,127`：`STORAGE:CAPACITY:SESSIONS:<episodeId>`

`substringAfter(':').substringBefore(':')` 取到的是 **problemType**（`NETWORK_UNAVAILABLE` / `UNKNOWN` / `CAPACITY`），丢掉 component 与 scopeKey。而 `gapReasonLabel`（`:132-140`）的映射表是给 `GapReason` 用的（`NETWORK_UNAVAILABLE`/`API_UNAVAILABLE`/`DATABASE_BLOCKED`/`MONITORING_STOPPED`/`RUNTIME_CRASH`/`UNKNOWN`）。两者的字面量**恰好有交集**，所以文案看起来「对」，但语义是借的；且 `problems.first()` 只显示一条，同时存在多个问题（网络 + 容量）时用户看不到全部，与「任何降级不静默」的注释（`:20`）相悖。

### 9. `StreamerRepository.addStreamer` 的双向回退可能解析出「另一个主播」

`StreamerRepository.kt:374-388`

```kotlin
private suspend fun resolveUid(parsed: ParsedInput): Long? = when (parsed) {
    is ParsedInput.Uid -> resolveByUid(parsed.uid) ?: resolveByRoomId(parsed.uid)
    is ParsedInput.RoomId -> resolveByRoomId(parsed.roomId) ?: resolveByUid(parsed.roomId)
}
```

- **没有无限递归**：`resolveByUid` / `resolveByRoomId` 是叶子函数，只各自打一次网络，互相不调用；两个分支各自最多 2 次请求 + `addStreamer` 里再取一次 `getRoomStatus`（`:97`）= 每次添加 2-3 次请求。这一条可以排除。
- **「用户选错模式」兜底的副作用**：房间号与 UID 都是纯数字，回退**不做任何形态校验**。用户把 UID 填进房间号模式时，`get_info?room_id=<UID>` 若在别的语义下命中（B 站 `get_info` 对不存在的 room_id 返回 code=1，台账已实测这一半），则会把**另一个人**当成本次目标加进监控；用户输入 `99999999` 这类既非本人房间也非本人 UID 的数字时同理。`StreamerRepository.kt:70-73` 的 NotFound 文案已经承诺了「切换模式再试」，说明主路径是可靠的 —— 建议把回退限制为「主路径明确判定为不存在」时（例如 `get_info` 返回 code≠0）才启用，并在回退成功时用 `AddStreamerResult` 把实际命中的 uid/房间号回显给用户，让「加错了」可见。当前实现拿到什么就静默写库。
- 同处：`:378-388` 的 `resolveByUid` 依赖 `resp.resultsByUid[uid]?.uid` 非 0；而 `BiliLiveApi.kt:45` 的过滤是 `it.uid == 0L || it.uid == uid`，即**允许 uid=0 的响应进入结果**，此时 `takeIf { it > 0 }` 会让解析失败（对 uid=0 的响应退化为「找不到」）。当前 B 站实测返回真实 uid（台账 A1 证据），所以只是脆弱点。

### 10. `DomainModels.effectiveQuietHoursRange` 是死字段

`DomainModels.kt:100-101`、`117-123`；唯一填充点 `ConfigRepository.kt:107-111` 只写了 `effectiveConfirmationCounts` / `effectiveQuietHours` / `effectiveNotifyPolicy`。`quietHoursRangeFor` 的 `effectiveQuietHoursRange[streamerStableId]` 恒 null，永远落到 `(quietHoursStartMinutes to quietHoursEndMinutes)`。数据层本身也没有主播级时段字段（`StreamerMonitorPolicyEntity.overrideQuietHours` 只是 `Boolean?`，`ConfigEntities.kt:116`），所以**当前无功能损失**，但 `MonitoringEngine.kt:271` 读的是这条永远走全局的路径，将来加主播级时段会踩空。

### 11. 网络层字段名与 URL 归一化：与台账实测一致，但有两个已知边界

已逐字核对 `BiliDtos.kt`：

- `BiliRoomInfo`（`:21-35`）：`short_id`（`:24`）、`area_v2_parent_name`（`:30`）、`cover_from_user`（`:33`）、`keyframe`（`:34`）—— 与 `DEFECT_LEDGER.md:53-56` 记录的实测响应一致；`area_name`、`uname`、`face`、`live_status`、`title` 亦为该接口真实字段。
- `BiliRoomDetail`（`:51-61`）：`user_cover`（`:56`）、`parent_area_name`（`:58`）、`keyframe`（`:57`）—— 与 `get_info` 口径一致。
- `BiliLiveApi.normalizeUrl`（`:114-119`）：`http://` + host 以 `.hdslb.com` 结尾 → 升级 https。已核对逻辑：`substringAfter("http://").substringBefore('/')` 对**无路径**的 `http://i0.hdslb.com` 也成立，对 `http://i0.hdslb.com/...` 取到裸主机名，因此旧白名单漏掉的 `i3`/其它编号子域都被覆盖。**唯一未覆盖的是大写 scheme**（`HTTP://i0.hdslb.com/...` 不匹配 `startsWith("http://")`）—— 建议 `startsWith("http://", ignoreCase = true)` 并保留大小写安全的替换，但这属加固而非现有缺陷（B 站 CDN 下发为小写）。
- `getRoomDetailByRoomId`（`:82-108`）用 `catch (e: Exception) → null` 吞掉**所有**异常（含 `CancellationException`），协程取消时会被当作「解析失败」继续走，建议改 `catch (e: java.io.IOException)` + `retrofit2.HttpException`，放开取消。
- `BiliLiveApi.kt:13` 的 `@Query("uids[]")` 在 Retrofit 2.11（`libs.versions.toml:15`）下会被转义成 `uids%5B%5D=`（Retrofit 2.6 起不再跳过方括号编码）。台账记录该接口实测返回 code=0，故**不作为缺陷**，但这是请求可用性的单点，值得加一条注释说明「已实测可接受」。

### 12. `HealthRepository` 的两条计算与 `OverallHealth` 字段的关系

按审查要求逐项核对 `HealthRepository.kt:88-127` 与 `OverallHealth`（`:22-33`）：

- **`notificationsOn`（`:80`）只参与判定、不进 `OverallHealth`**：`OverallHealth` 没有「通知权限」字段，用户消息里提到「需要通知权限才能后台监控」但界面无法进一步展示细节。判定顺序上 `:95-98`（`FOREGROUND_NOTIFICATION && !serviceRunning && !notificationsOn`）在 `:103-104`（`FOREGROUND_NOTIFICATION && !serviceRunning`）之前 —— 逻辑上无重复命中，`notificationsOn` 这个分支不会「永远为真/假」。
- **`quietHours` / 无障碍状态不进 `OverallHealth`**：无障碍状态被折进 `status` + `userMessage`（`:93-94`）**是**自洽的，但 `OverallHealth` 里没有单独的 `accessible` / `quietHoursActive` 字段，健康中心页无法把「现在正处于免打扰时段」显示出来 —— 免打扰抑制的是通知，用户会把它当成「监控坏了」。这是**字段与需求不一致**，不是计算错误；`clock`（`:43`）与 import `RecoverySessionDao`（`:9`）在该文件中未被使用。
- `:73` `streamerDao.listMonitorable()` 在 `collect` 内执行，每次上游 emit 都会打一次 DB（含权限变化这种高频源）。功能正确，成本略高。

### 13. `precheck` 的校验和依赖「重新序列化后逐字节一致」

`BackupRepository.kt:322-325` 用**解析后的对象重新序列化**再算 SHA-256 与 `manifest.checksum` 比对。当前 `serializeBackup`（`:217`）与 `precheck` 共用同一个 `json`（`:142`，`ignoreUnknownKeys = true; encodeDefaults = true`），字段顺序由 `BackupBody` 声明顺序决定，所以现在**能对上**。但它把「校验和」变成了对 DTO 声明顺序与 `Json` 配置的隐式依赖：将来给 `BackupBody` 加一个带默认值的字段、或调整字段顺序，**所有历史备份都会被判为「内容校验和不匹配，文件可能已损坏」**。建议改为对原始 JSON 文本（或对规范化后的字段子集）校验，或在 `formatVersion` 提升时同时升级校验口径。

---

## 三、风格 / 低危（不影响正确性，但建议收拾）

| 位置 | 问题 |
|---|---|
| `HistoryRepository.kt:235-238` | `reconcileDuration` 全工程零调用（死代码，台账 E4 已记录过一次，仍未删）。 |
| `FollowImportRepository.kt:93` | `fetchLiveHistory(taskId, generation, mid, count)` 的 `mid` 参数从未使用（历史接口不需要 mid）。 |
| `FollowImportRepository.kt:243` | 应用导入后把任务置 COMPLETED，但 `follow_import_staging` 行不清（`clearStaging` 无调用点），靠 30 天保留策略（`MoreDaos.kt:44-56`）兜底。 |
| `BackupRepository.kt:682-684` | `decideAll` 丢弃 `restoreDao.decideAll` 的 `Int` 返回值（影响行数 0 时（冲突已被决策过）调用方无从知晓）。 |
| `BackupRepository.kt:259-270` | `logDao.insertAudit(...)` 与 `finally { exitMaintenance }` 的返回值同样被丢弃；`exitMaintenance` 返回 false（维护权泄漏）时没有任何日志。 |
| `BackupRepository.kt:249-253` | `check(enterMaintenance(...))` 位于 `runCatching` 内、`try` 之前 —— **这一条是正确的**（异常会被 `runCatching` 转成 `Result.failure`，且 `finally` 不会执行，`exitMaintenance` 不会误释放别人的锁，因为 `ConfigDaos.kt:197` 的 `WHERE maintenanceMode = :mode` 也做了模式核对）。此处仅记录「已核对，无缺陷」。 |
| `BackupRepository.kt:515` | `sidToInt[bs.streamerStableId]!!` 的非空断言安全性依赖上一行 `restoreSessionReason` 的返回值约定（null=可恢复 / ""=静默跳过 / 非空=跳过）。当前逻辑成立，但两个函数的契约是隐式的，建议把 `!!` 换成 `?: continue`。 |
| `HealthRepository.kt:9,43` | 未使用的 import `RecoverySessionDao` 与未使用的构造参数 `clock`。 |
| `HistoryRepository.kt:102-119` | 形参 `correction: ManualCorrection` 与返回值类型 `CorrectionResult`、方法名 `applyManualCorrection` 三者相邻，可读性差；`correction.note` 为 null 时经 `COALESCE` 不写库（不会清空备注），与「可改备注」的 UI 期待有落差。 |
| `ExportRepository.kt:35` | `private val json` 只用于快照行的 `Map<String, String?>` 编解码，`ignoreUnknownKeys`/`encodeDefaults` 均为无用配置。 |
| `ExportRepository.kt:62-80` | 快照行先组 `Map<String, String?>` 再 `mapValues { it.value?.toString() }` 再序列化；`durationSeconds`（Long?）走的是 `Long.toString()`，`buildHtml` 再 `toLongOrNull()` 解回来，可读性与健壮性都不如直接放强类型 DTO。 |
| `FollowImportRepository.kt:101,139` | `obj["code"]?.toString()?.toIntOrNull()`：若 `code` 是字符串 primitive 会得到 `-1`（同上 §4 的 `toString()` 陷阱），此处应统一用 `jsonPrimitive.content`。 |
| `DshTypeConverters.kt:45-159` | 30 组枚举转换器逐个手写、无遗漏（已与 `Enums.kt`/`CoreEntities.kt`/`ConfigEntities.kt` 使用到的枚举对照）；`Boolean<->Int`（`:158-159`）与 DDL `DEFAULT 0/1` 一致。无缺陷，仅提示：新增枚举列时必须同步补转换器，否则 Room 编译期才报错。 |

---

## 四、针对审查要点的明确回答（含「不是问题」的结论）

1. **`InputMode` / `ParsedInput` 顶层化后的引用**：`StreamerInputParser.kt:4-15` 已是顶层声明；`StreamerRepository.kt:67,69,72,366,374-376` 走同包直接解析，`HomeViewModel.kt:13`、`HomeScreen.kt:62` 有显式 import，`ParseStreamerInputTest.kt:18-91` 亦然。**全工程 0 处 `StreamerRepository.InputMode` / `StreamerRepository.ParsedInput` 写法**（已用正则全库检索）。`HomeScreen.kt:384-389`、`HomeViewModel.kt:161-167` 的 `when` 对 `InputMode`（2 值）与 `AddStreamerResult`（5 值）均穷尽。Kotlin 2.0.21（`libs.versions.toml:3`）支持 `InputMode.entries`。→ **无编译错误**。
2. **`StatsRepository` 新增 `statisticsSnapshotDao`**：构造参数（`StatsRepository.kt:70`）由 `AppModule.kt:134` 提供，`AppDatabase.kt:136` 暴露 DAO；所用方法 `findValid(sdv, fingerprint, now)`（`ConfigDaos.kt:475`）、`insert`、`trimTo`（`:489`）、`recent`（`:478`）、`deleteExpired`（`:492`）签名与调用点（`StatsRepository.kt:84,168,183,190,194`）逐一对齐。→ **无编译错误**。
3. **`ExportRepository` 的 `check(casStatus(...) > 0)`**：**不会误伤正常路径**（状态闭环完整，`casStatus` 返回受影响行数）；但如 §7 所述，异常/竞态下会把快照留在 `CONSUMING`，且维护权在 `casStatus` 之前就已释放。
4. **`BackupRepository.exportBackup` 的 `check(enterMaintenance(...))`**：**`finally` 仍会正确执行、`check` 异常会被 `runCatching` 正确转成 `Result.failure`**（见 §三 对应行）；真正的问题不在 `exportBackup`，而在同文件的 `applyRestore` 从不取锁（§2）。
5. **`addStreamer` 的回退**：**不会递归**（叶子函数），但存在「回退命中另一个人」与「uid=0 响应被判为不存在」两个脆弱点（§9）。
6. **`HealthRepository` 的 `quietHours` / `notificationsOn` / 无障碍与 `OverallHealth`**：三者都不在 `OverallHealth` 里（§12），无障碍被折进 `status`/`userMessage` 是自洽的，`notificationsOn` 判定分支无重复命中；问题在于「免打扰/容量问题」的用户可见表达缺失，以及问题文案借用了盲区的术语表。
7. **`BiliDtos` 字段名与 `normalizeUrl`**：与实测/台账一致，无缺陷；仅 `normalizeUrl` 的 scheme 大小写与 `catch (e: Exception)` 吞取消两点建议加固（§11）。
8. **被忽略的返回值 / 恒真恒假条件 / 死代码**：见 §三表格与 §10、§二·13；未发现「永远为真/假」的条件分支（`HealthRepository` 的 6 个降级分支互不覆盖且各自可达；`StreamerInputParser.kt:52-56` 的 `first.startsWith("h5")` 在 `num == null` 之后判断，`h5` 分支实际不可达 —— 但 `"h5".toLongOrNull() == null` 已经先返回 null，**功能上无差异**，属冗余判断）。

---

### 附：本次审查中「已核对且无缺陷」的高风险点（供回归参考）

- `BackupRepository.serializeBackup`：`BackupStreamer`(18 字段)/`BackupSession`(27 字段)/`BackupEvent`/`BackupTitle`/`BackupCorrection`/`BackupPolicy` 的**位置参数顺序与字段类型**已与实体定义逐项比对，全部对齐；`BackupBody.titles` 有默认值、不破坏 `precheck` 的反序列化。
- `BackupRepository.applyRestore`：`LiveSessionEntity`(30+ 字段)、`LiveEventEntity`、`LiveSessionCorrectionEntity`、`LiveSessionTitleEntity`、`TagEntity`/`StreamerTagCrossRefEntity`/`GroupEntity`、`StreamerMonitorPolicyEntity` 的构造参数全部与实体定义一致；终态不可逆的 `casTransition` 前置检查（`:401-405`）与 `StartupRecovery.kt:57-65` 的 APPLYING 兜底构成闭环。
- `HistoryRepository.applyManualCorrection` / `createManualSession`：「三项填二补全第三」的两条实现与 `casManualCorrect` 的 `COALESCE` 语义（`MonitorDaos.kt:332-360`）配套正确；`provided >= 2` 的判定、`end < start` 校验、`confirmedOfflineAt` 只在「本次新补出下播时间」时赋值（`:100`）均正确。
- `AuthRepository.logout`：`currentAuthGeneration() = MAX(authGeneration)`（`MoreDaos.kt:112-113`）与「落一条 generation+1 的已登出会话行」配合，确实让在途导入任务的代际校验立即失效（C8 的修复有效）。
- `DshTypeConverters`：枚举集合无遗漏，`Boolean<->Int` 与 DDL 默认值一致。
