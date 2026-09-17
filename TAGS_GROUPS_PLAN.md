# 标签 / 分组改造方案（多标签筛选 · 排序 · 删除）

> 本轮为**侦察 + 方案**。硬约束：不跑 Gradle、不碰模拟器。
> 结论先行：**多标签、排序、删除三件事都不需要任何 schema 改动、不需要迁移、不需要升 DB_VERSION。**
> 原因见 §1：多对多关联表与 `sortOrder` 从 `schemas/2.json` 起就已经在库里了。
> 真正缺的只有"一次按多个标签筛"的查询与上层状态类型（纯应用层）。

---

## 1. 现状表（每项给文件:行）

| 项 | 事实 | 位置 |
|---|---|---|
| 标签表 | `tag(id INTEGER PK AUTOINCREMENT, stableId TEXT NOT NULL UNIQUE, name TEXT NOT NULL, colorHex TEXT, sortOrder INTEGER NOT NULL)`；**`name` 没有唯一约束** | `data/local/entity/CoreEntities.kt:74-81`；`app/schemas/.../8.json` |
| 分组表 | `group(id, stableId UNIQUE, name, sortOrder, collapsed)` | `CoreEntities.kt:83-90` |
| 主播–标签关联 | **多对多关联表** `streamer_tag_cross_ref(streamerId, tagId)`，双列主键，两侧 FK **ON DELETE CASCADE** | `CoreEntities.kt:92-101` |
| 主播–分组关联 | 同构 `streamer_group_cross_ref`，两侧 CASCADE | `CoreEntities.kt:103-112` |
| 一个主播能否有多标签 | **能，而且详情页一直可以逐个添加**（不是单列、不是单选） | `ui/detail/StreamerDetailScreen.kt:540-565`（标签卡）、`:144-175`（addTag/removeTag） |
| `@Relation` / `@Junction` | 全项目**没有**用，关联一律手写 JOIN | `data/local/dao/MonitorDaos.kt:114-160` |
| 标签 DAO | `observeTags/listTags/insertTag/assignTag/unassignTag/deleteTag/tagsOfStreamer/findTagByName/findTagByStableId` | `data/local/dao/ConfigDaos.kt:551-600` |
| 分组 DAO | `observeGroups/listGroups/insertGroup/assignGroup/unassignGroup/deleteGroup/groupsOfStreamer/setGroupCollapsed` | `ConfigDaos.kt:557-621` |
| 单标签筛选查询 | `observeByTag`(114-122) / `observeByTagAndQuery`(128-137) | `MonitorDaos.kt` |
| 单分组筛选查询 | `observeByGroup`(140-148) / `observeByGroupAndQuery`(151-160) | `MonitorDaos.kt` |
| 仓储包装 | `observeByTag(tagId)` / `observeByTag(tagId,query)` / `observeByGroup(groupId,query)` | `data/repository/StreamerRepository.kt:51-61` |
| 筛选状态类型 | `tagFilterId: Long?`(:122)、`groupFilterId: Long?`(:124) —— **都是单选**；UI state 字段在 :49 / :51 | `ui/home/HomeViewModel.kt` |
| 筛选组合方式 | `combine(query, filter, tagFilterId, groupFilterId)` 四路(:134-136) → `flatMapLatest` 三选一分支(:140-144)：**tagId 与 groupId 是 when 分支关系，天然互斥** | `HomeViewModel.kt:134-146` |
| 互斥逻辑（必须删） | `setTagFilter` 里 `if (tagId != null) groupFilterId.value = null`(:301)；`setGroupFilter` 里反向清空(:306) | `HomeViewModel.kt:298-307` |
| 筛选 UI | 标签行 146-154、分组行 157-165、收起态摘要 75-80、chip 行渲染 172-211（`selectedId: Long?` **单选**） | `ui/home/HomeFilterCard.kt` |
| 空态文案 | `state.tagFilterId != null -> "该标签下还没有主播"` / 分组同款 | `ui/home/HomeScreen.kt:200-210` |
| **排序字段** | **两表都有 `sortOrder`**；查询一律 `ORDER BY sortOrder, name`（tag: 551/575，group: 557/578/612） | `CoreEntities.kt:80,88` |
| 排序入口 | **零**：DAO 里连一条 `UPDATE ... sortOrder` 都没有（本轮已补），UI 更没有 | — |
| **删除入口** | 仓储有 `deleteTag`(:77-83) / `deleteGroup`(:136-142)，DAO 有对应 DELETE(ConfigDaos.kt:599-600 / 617-618)，但**全项目 UI 无任何调用点**（grep `deleteTag|deleteGroup` 只命中仓储与 DAO） | `data/repository/TaxonomyRepository.kt` |
| 删除时关联行 | FK **CASCADE** → 删标签/分组自动清关联行，**不会被外键卡住** | `CoreEntities.kt:96-98,107-109` |
| 备注：为什么不会被卡 | 本项目 `live_session` / `status_history` 的 FK 是 `NO_ACTION`，所以主播只能软删；标签/分组两侧都是 CASCADE，与此不同 | `CoreEntities.kt:116-133,201-212` |
| 备份格式 | `BackupTag/BackupGroup/BackupTagRef/BackupGroupRef` 已含 `sortOrder`；导出在 273-306 | `data/repository/BackupRepository.kt:56-59,92-101` |
| 恢复路径 | 标签 upsert + **tagRefs 会恢复**(878-882)；**`groupRefs` 只导出、恢复时没有循环 → 分组归属静默丢失**（既有缺陷） | `BackupRepository.kt:303-306` vs `866-892` |
| 迁移/版本 | 7 个迁移，`ALL` 在 277-285；`DB_VERSION = 8` 是唯一来源，备份与诊断包都引用常量 | `data/local/db/AppMigrations.kt`、`AppDatabase.kt:154`、`BackupRepository.kt:1148`、`DiagnosticExporter.kt:310` |
| 测试 | `app/src/test`、`app/src/androidTest` 中**没有任何 tag/group 相关测试**（grep 零命中） | — |
| 镜像树 | `app/` 与 `publish/app/` 逐字节一致；BOM 状态：HomeScreen.kt / StreamerDetailScreen.kt **有 BOM**，其余无 | 本轮已核对 |

---

## 2. 改动清单（按层）

### A. 数据层 —— ✅ **本轮已完成，且不需要 schema 改动**

**`data/local/dao/MonitorDaos.kt`（StreamerDao，插入在 `observeByGroupAndQuery` 之后，现 :162-241）**

| 新增 | 行 | 说明 |
|---|---|---|
| `observeByAnyTag(tagIds, query)` | :194 | 多标签 **OR**（并集），`IN (:tagIds)` |
| `observeByAllTags(tagIds, tagCount, query)` | :208 | 多标签 **AND**（交集），`GROUP BY s.id HAVING COUNT(DISTINCT r.tagId) = :tagCount` |
| `observeByGroupAndAnyTag(groupId, tagIds, query)` | :221 | 分组 ∧ 多标签（OR） |
| `observeByGroupAndAllTags(groupId, tagIds, tagCount, query)` | :236 | 分组 ∧ 多标签（AND） |

关键词统一写成 `(:query = '' OR s.name LIKE '%'||:query||'%')`：把"空串即不过滤"收进 SQL 常量条件，
4 条查询覆盖既有实现需要 8 条（`xxx` + `xxxAndQuery`）的组合，语义与既有两分支完全一致。

**`data/local/dao/ConfigDaos.kt`（TaxonomyDao，现 :623-687）**

| 新增 | 行 |
|---|---|
| `updateTagSortOrder(tagId, sortOrder)` / `updateGroupSortOrder(...)` | :633 / :636 |
| `assignTags(List<ref>)` / `assignGroups(List<ref>)`（IGNORE） | :641 / :644 |
| `clearTagsOfStreamer` / `clearGroupsOfStreamer` | :647 / :650 |
| `unassignTagFrom(tagId, streamerIds)` / `unassignGroupFrom(...)` | :653 / :656 |
| `countStreamersOfTag` / `countStreamersOfGroup`（只数 `deletedAt IS NULL`） | :670 / :679 |
| `tagRefsOf(streamerIds)` / `groupRefsOf(streamerIds)`（卡片显示用） | :683 / :686 |

### B. 仓储

**已完成：**
- `StreamerRepository.kt:63-97`：`observeByTags(tagIds, query, requireAll)`（:74）+ `observeByGroupAndTags(groupId, tagIds, query, requireAll)`（:87）。
  空列表兜底在这里（Room 对空 `IN` 会生成 `IN ()` → SQLite 语法错；"没选标签"就等于不加条件）。
- `TaxonomyRepository.kt:164/176`：`moveTag(fromIndex,toIndex)` / `moveGroup(...)`（整表归一化成 0..n-1，越界 no-op）。
- `TaxonomyRepository.kt:196/208/219`：`setTagsOf(streamerId, tagIds)`（整体替换）、`assignTagTo(streamerIds, tagId)`、`unassignTagFrom(...)`。
- `TaxonomyRepository.kt:228/231`：`streamerCountOfTag/Group`（删除确认文案的影响面）。
- `TaxonomyRepository.kt:234/238`：`tagRefsOf/groupRefsOf`。

**待做（可选）**：删除时**显式**先删关联行（防御历史孤儿行，见 §4.1）。

### C. ViewModel —— 待做（**无 schema 改动**）
`ui/home/HomeViewModel.kt`
1. `:122` `tagFilterId: MutableStateFlow<Long?>` → `MutableStateFlow<Set<Long>>`（**建议 `LinkedHashSet` 或始终保持 `tags` 列表序**，见 §4.3）。
2. `:124` `groupFilterId` **保持 `Long?`**（用户明确：分组不可同级多选）。
3. `:49` / `:51` `HomeUiState.tagFilterId: Long?` → `tagFilterIds: Set<Long>`；`groupFilterId` 不变。
4. `:134-146` 分支改为：
   ```kotlin
   val base = when {
       groupId != null -> streamerRepository.observeByGroupAndTags(groupId, tagIds, q, requireAll)
       tagIds.isNotEmpty() -> streamerRepository.observeByTags(tagIds, q, requireAll)
       else -> streamerRepository.observeStreamers(q)
   }
   ```
   （`requireAll` 就是 §3-Q1 的待定语义开关。）
5. `:298-307`：`setTagFilter(tagId)` → `toggleTagFilter(id)`；**删掉 :301 与 :306 两行互斥**；`setGroupFilter` 变成纯赋值。
   「全部标签」= `clearTagFilter()`（清空集合，不动分组）。
6. `:199-201` 两个字段的透传改名。
7. ⚠️ **combine 路数上限**：`uiState` 已是 5 路 combine（`:159-165`），第 5 路又是 `combine(tags, groups)` 内嵌。
   Kotlin 具名重载到 5 就没了 —— 若还要加"AND/OR 开关"或"卡片标签 map"这类新状态，
   要么并进 `combine(tags, groups)` 那条内嵌流，要么像 `appName`/`navigationStyle`（`:97-117`）那样**单开一条 StateFlow**。
   这是本轮最容易被忽略的结构性约束。
8. `pruneSelection`（`:261-269`）**不用改**：它只依赖最终列表 + `HomeFilter.matches`，与标签个数无关。

### D. UI —— 待做
1. `ui/home/HomeFilterCard.kt`
   - `:59-60` 参数：`tagFilterId: Long?` → `tagFilterIds: Set<Long>`；回调 `(Long) -> Unit`（toggle 语义）+ 一个 `onClearTags`。
   - `:146-154` 标签行：`allSelected = tagFilterIds.isEmpty()`，每项 `selected = item.id in tagFilterIds`。
   - `:157-165` 分组行：**保持单选**。
   - `:181-211` `FilterChipRow` 目前是单选（`selectedId: Long?`）。两种做法：
     (a) 新增 `MultiSelectChipRow`，单选/多选各一个 Composable（**推荐**，语义直白）；
     (b) 把参数改成 `isSelected: (Long) -> Boolean`，一个函数两用（改动小，但单选/多选语义藏进 lambda）。
   - `:75-80` 收起态摘要：`tags.firstOrNull` → `tags.filter { it.id in tagFilterIds }.joinToString("、")`；
     **顺序必须按 `tags` 列表顺序取，不能按 Set 迭代序**，否则摘要文字会跳（`:108-109` 已有 `maxLines=1` + Ellipsis 兜底）。
2. `ui/home/HomeScreen.kt`
   - `:180-187` 传参改名。
   - `:200-210` 空态文案要按 Q1 语义分支，并且**两行同时生效时要说清是哪个条件卡住**（如 `分组「X」+ 标签「A、B」下没有主播`）。
   - `StreamerCard`（`:335-424`）**目前完全不显示标签**。"一个卡片可以支持添加多个标签"若包含"卡片上要看得到"，
     需要在这里加一行 chip；数据用 `taxonomyRepository.tagRefsOf(ids)`（已备好）或给 ViewModel 单开一条 `streamerId -> List<TagEntity>` 流。
   - 多选态顶栏（`:123-140`）可挂"批量打标签"入口，数据层 `assignTagTo` 已备好。
3. 管理页（删除 + 排序）——**当前完全不存在**
   - 建议新增 `ui/settings/TaxonomyManageScreen.kt`（列表 + 上移/下移或拖拽 + 删除按钮 + 二次确认）。
   - 入口：`ui/settings/SettingsScreen.kt:368` 附近加一张 `SettingSectionCard("标签与分组")`（组件在 `:575`）。
   - ⚠️ 路由那一行要加在 `ui/nav/AppNavHost.kt`（`Routes` 在 :81-96，`composable(...)` 在 :483-547）——
     **该文件是本轮禁改文件**，必须由协调者本人或另一个获准的代理补。
4. `ui/detail/StreamerDetailScreen.kt`（**已支持多标签，改动最小**）
   - 标签卡 `:540-565`、分组卡 `:496-531` 已经能逐个加/删（`:144-175`）。
   - 若要做"勾选式编辑"，换成 `setTagsOf`（已备好）；自由文本对话框 `:656-710` 建议保留（输入即新建，路径最短）。

### E. 迁移 —— **本轮结论：不需要**（若将来需要，写法如下）
- **不需要**的前提：不改表结构、不加列、不加唯一索引 → `DB_VERSION` 保持 8，`7.json` 之后不需要新的 `9.json`。
- **什么情况才需要 8→9**：① 想用 DB 唯一索引保证"标签名唯一"；② 想给 tag/group 加新字段（如 `updatedAt`）；③ 想用迁移清理历史孤儿关联行。
- 写法（沿用既有风格）：
  1. `AppMigrations.kt` 加 `val V8_9__xxx = object : Migration(8, 9) { override fun migrate(db) { dropPartialIndexes(db); ... } }`；
     **第一句必须 `dropPartialIndexes(db)`** —— 这是本文件的契约（`:13-30`）：Room 迁移后做全量 schema 校验，
     而带 `WHERE` 的部分唯一索引无法在 `@Entity` 里声明，只能先摘掉、由 `onOpen` 的 `createPartialIndexes` 幂等重建（`AppDatabase.kt:194-207`）。
  2. `AppDatabase.kt:154` `DB_VERSION = 8` → `9`（其余引用点全部走常量，不用逐个改：`BackupRepository.kt:1148`、`DiagnosticExporter.kt:310`）。
  3. `AppMigrations.kt:277-285` 的 `ALL` 数组追加。
  4. `app/schemas/.../9.json` **由 Room 在编译期导出**（`exportSchema = true`，`AppDatabase.kt:112`）——
     **只有跑一次 Gradle 才会生成**。本轮不跑 Gradle，所以"需要升版"的方案在本轮硬约束下**做不了**，必须排进一次构建。
  5. 老安装影响：`tag`/`group`/两张关联表的数据不动；若加**唯一索引**，必须先 `DELETE` 去重
     （`findTagByName` 只在应用层防重，历史数据与备份恢复都可能已产生同名多行），
     否则 `CREATE UNIQUE INDEX` 会抛错、被 `AppMigrations.kt:62-66` 的 `runCatching` 吞成"失败索引名"→ 唯一性静默失效。

### F. 备份 / 恢复
- **格式不用改**：`BackupTag`/`BackupGroup` 已含 `sortOrder`；`BackupBody` 加字段会破坏旧备份 checksum
  （`:144-149` 明确写了"能给默认值就别往 body 里塞"）。
- **但要修一个既有缺陷**：`groupRefs` 导出（`:303-306`）却从不恢复（`:866-892` 只有 tagRefs 的循环）→
  **恢复备份后分组归属全丢**。补 3 行 `for (r in file.body.groupRefs) { ... assignGroup ... }` 即可，属恢复路径修复、与格式无关。
- 若新增"标签颜色"等字段，才需要同步 `BackupTag`（并注意 checksum 兼容策略）。

---

## 3. 两个语义问题（**待用户确认，本文不拍板**）

### Q1：标签多选 = AND（交集）还是 OR（并集）？
两种实现**代价几乎相同**（都已在 DAO 备好、都是零 schema、零迁移），差别只在产品语义与空态文案：

| | OR（并集，命中任一） | AND（交集，必须全部具备） |
|---|---|---|
| DAO | `observeByAnyTag`(:194) / `observeByGroupAndAnyTag`(:221) | `observeByAllTags`(:208) / `observeByGroupAndAllTags`(:236) |
| SQL | `WHERE r.tagId IN (:tagIds)` | 加 `GROUP BY s.id HAVING COUNT(DISTINCT r.tagId) = :tagCount` |
| 代价 | 走 `index_streamer_tag_cross_ref_tagId` 一次扫描，无聚合 | 多一次分组聚合（本项目量级可忽略）；`tagCount` 必须 = `tagIds.size`（仓储已自动传 `tagIds.size`） |
| 使用体验 | "这几个标签里都有谁"，点得越多结果越多 | 点第 2 个标签常常**直接空列表**，容易以为坏了；空态文案必须解释"同时具备" |
| 空态文案 | `没有带「A」或「B」的主播` | `没有同时具备「A、B」的主播` |

**第三种可选**：给用户一个「任一 / 同时」切换。代价不在数据层，而在 ViewModel ——
`uiState` 已经是 5 路 combine 的上限（`:159-165`），这个开关必须先并进 `combine(tags, groups)` 内嵌流，或单开一条 StateFlow（`:97-117` 有先例）。请用户先定 Q1。

### Q2：分组与标签同时生效 = AND 吗？
- **现状是互斥**（`HomeViewModel.kt:300-301,305-306`；`HomeFilterCard.kt:156` 注释写着"避免两个归属维度同时约束导致空结果难以解释"）。
- 用户要求改成"选了分组同时也能选标签" → **必须删掉那 4 行互斥逻辑**，否则点标签会把分组清掉（这是最容易漏的一处）。
- 本方案**按 AND 设计**：`分组 ∧ 标签条件 ∧ 关键词 ∧ 状态筛选`，查询已备好（`observeByGroupAndTags`）。**请用户确认按 AND。**
- 副作用：两行同时生效时空结果概率明显上升，空态文案必须能指出是哪个条件卡住的。

---

## 4. 风险与不确定项

1. **外键 / 孤儿行**：tag/group 两侧都是 CASCADE（`CoreEntities.kt:96-98,107-109`），删标签不会像 `live_event` 那样被卡住。
   但 `PRAGMA foreign_keys = ON` 只在 `onConfigure/onOpen` 设置（`AppDatabase.kt:187,196`）——
   历史上若有版本在 FK 关闭的窗口删过 tag，就会留下 `tagId` 指向不存在 tag 的孤儿关联行。
   建议删除路径**显式**先 `DELETE FROM streamer_tag_cross_ref WHERE tagId = ?`（幂等、防御性，不必升版）；
   彻底清理孤儿行才需要迁移。
2. **既有筛选口径**：`observeByTag/observeByGroup` 只过滤 `s.deletedAt IS NULL`，**不过滤 `monitoringEnabled`**
   （与 `observeActive()` 一致）→ 首页列表**包含已暂停监控的主播**。新的多标签查询已按同一口径写，别顺手多加条件造成口径漂移。
3. **状态机复杂度（同级多选 + 单选混排）**：
   - `pruneSelection`（`HomeViewModel.kt:261-269`）在"列表到达 → 写回选中"这条异步链上，与多选态批量选择耦合；
     它现在是幂等的，标签多选不会破坏它，但 **`Set<Long>` 的顺序**要小心：Kotlin `Set` 的 `equals` 与顺序无关（不会引起多余重发），
     但**摘要文案的顺序**必须按 `tags` 列表顺序渲染，否则用户看到的标签名会跳。
   - 两个"多选"是不同的东西：**首页批量选择主播**（`selectedIds`）与**标签行多选**（`tagFilterIds`）。
     命名要区分清楚，否则后续极易互相污染（建议 `tagFilterIds` / `selectedIds` 保持不变，别叫 `selectedTags`）。
4. **`IN ()` 空列表**：只在 `StreamerRepository` 兜底（`:74-80, :87-97`）；**任何绕过仓储直接调新 DAO 方法的调用点都会 SQL 语法错**
   （该警告已写在 DAO 注释里）。
5. **备份恢复丢分组归属**（既有缺陷，§2-F）。
6. **标签同名**：`tag.name` 无唯一索引；应用层只在"创建"路径防重（`findTagByName`），
   备份恢复按 `stableId` upsert → 可能出现同名多行，管理页会显示两个同名标签。要不要加唯一索引 = 要不要迁移，属于需用户拍板项。
7. **`colorHex` 从未使用**：`TagEntity.colorHex` 一直被写成 `null`、从未展示；
   项目里的 `ColorHex` 工具目前只服务外观设置（`ColorHexTest.kt`）。"标签配色"是纯 UI 工作，不涉及数据层。
8. **并发编辑面**：本轮改动的 4 个文件（`MonitorDaos.kt` / `ConfigDaos.kt` / `StreamerRepository.kt` / `TaxonomyRepository.kt`）
   是公共数据层，别的代理也可能碰。若有并发改动，需以最新版本重新核对再合并。

---

## 5. 实施批次建议（便于并行拆给多个代理）

| 批次 | 内容 | 依赖 | 建议 |
|---|---|---|---|
| **1** | 备份恢复补 `groupRefs`（`BackupRepository.kt:883-892`）；卡片显示标签（`HomeScreen.kt:335-424` + ViewModel 一条 map 流） | 无 | **可立刻并行**，与 Q1/Q2 无关 |
| **2** | `HomeViewModel` 状态类型 + combine 分支 + 删互斥（`:49/:51/:122/:134-146/:199-201/:298-307`） | **需先定 Q1** | 核心，单人做，别拆 |
| **3** | `HomeFilterCard` 标签行多选 / 分组行保持单选 / 摘要 / 空态文案（+ `HomeScreen.kt:180-210`） | 依赖批次 2 的 UI 契约 | 紧随批次 2 |
| **4** | 标签/分组管理页（新增文件）+ 设置入口（`SettingsScreen.kt:368`）+ 删除二次确认 + 排序交互 | 数据层已备好，**与 2/3 无关** | **可并行**；⚠️ `AppNavHost.kt` 是禁改文件，路由那一行须由协调者或获准代理补 |
| **5** | （仅当决定要唯一索引/新字段）`DB_VERSION 8→9` + `V8_9` 迁移 + `9.json` | **必须跑一次 Gradle** | 本轮硬约束下不做 |

---

## 6. 本轮实际改了什么（4 文件 × 2 棵树；除 1 处外全部为新增）

镜像树 `app/` 与 `publish/app/` **逐字节一致**，4 个文件原本都**无 BOM**、改后仍无 BOM（已字节级核对）。

| 文件（两棵树同改） | 改动 |
|---|---|
| `data/local/dao/MonitorDaos.kt` | **新增** 4 条多标签/分组∧标签筛选查询（现 :162-241），含 AND/OR 两套语义 |
| `data/local/dao/ConfigDaos.kt` | **新增** `TaxonomyDao` 的排序写入、批量关联/清除、影响面计数、卡片关联行查询（现 :623-687） |
| `data/repository/StreamerRepository.kt` | **新增** `observeByTags`(:74) / `observeByGroupAndTags`(:87)，含空列表兜底与 `requireAll` 语义开关 |
| `data/repository/TaxonomyRepository.kt` | **新增** `moveTag`(:164) / `moveGroup`(:176) / `setTagsOf`(:196) / `assignTagTo`(:208) / `unassignTagFrom`(:219) / `streamerCountOfTag`(:228) / `streamerCountOfGroup`(:231) / `tagRefsOf`(:234) / `groupRefsOf`(:238)；**行为改动 1 处**：`createAndAssign` 新标签 `sortOrder` 由写死 `0` 改成 `max+1`（与 `createGroupAndAssign` 同构，是"排序功能"能生效的前提；原来所有新标签并列 0，顺序完全由 name 兜底） |

**未改**：任何实体/表结构、任何迁移、`DB_VERSION`、schema JSON、任何 UI/ViewModel 文件、`AppNavHost.kt`、`notify/*`、`ui/history/*`、`ui/stats/*`、`ui/common/SessionSort.kt`。

**未验证**：本轮不跑 Gradle，所以 4 个文件的 Kotlin/Room 编译（尤其 Room 注解处理器的 SQL 校验）**未经构建验证**；
SQL 已按现有查询的写法逐条比对，`GROUP BY s.id HAVING ...` 与 `IN (:list)`（`ExportDao.deleteSnapshots` 有同款先例）均为 Room 支持形式。
