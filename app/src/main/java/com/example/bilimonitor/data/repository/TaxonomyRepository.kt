package com.example.bilimonitor.data.repository

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.TaxonomyDao
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.entity.GroupEntity
import com.example.bilimonitor.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class TagWithCount(val id: Long, val name: String, val count: Int)

data class GroupWithCount(val id: Long, val name: String, val count: Int, val collapsed: Boolean)

/**
 * 标签 / 分组（原规范 15 / 16）。
 *
 * 全部引用 Streamer 实体自身，不建第二套身份；两个维度结构同构：
 * 筛选都支持"仅按归属"与"归属 + 关键词"两种查询（后者避免搜索框在筛选下失效）。
 */
@Singleton
class TaxonomyRepository @Inject constructor(
    private val db: AppDatabase,
    private val taxonomyDao: TaxonomyDao,
    private val logDao: LogDao,
    private val clock: AppClock
) {
    // ---- 标签 ----

    fun observeTags(): Flow<List<TagEntity>> = taxonomyDao.observeTags()

    suspend fun tagsWithCount(): List<TagWithCount> {
        val tags = taxonomyDao.listTags()
        val refs = taxonomyDao.listTagRefs()
        return tags.map { t ->
            TagWithCount(t.id, t.name, refs.count { it.tagId == t.id })
        }
    }

    suspend fun tagsOf(streamerId: Long): List<TagEntity> = taxonomyDao.tagsOfStreamer(streamerId)

    /** 创建（或复用同名）标签并关联主播。 */
    suspend fun createAndAssign(name: String, streamerId: Long): TagEntity {
        val now = clock.nowWall()
        val clean = name.trim()
        require(clean.isNotEmpty()) { "标签名不能为空" }
        return db.withTransaction {
            val existing = taxonomyDao.findTagByName(clean)
            val tag: TagEntity = existing ?: run {
                val stableId = Ids.stableId("tag")
                // 新标签排到末尾，与 createGroupAndAssign 保持同构。原来是写死 0：所有新标签
                // 并列 0，"新加的排在最后"根本不成立，排序完全由 name 兜底，也没法把它挪走。
                val sortOrder = (taxonomyDao.listTags().maxOfOrNull { it.sortOrder } ?: 0) + 1
                val newId = taxonomyDao.insertTag(
                    TagEntity(stableId = stableId, name = clean, colorHex = null, sortOrder = sortOrder)
                )
                val created = TagEntity(
                    id = newId, stableId = stableId, name = clean, colorHex = null, sortOrder = sortOrder
                )
                logDao.insertAudit(audit("CREATE_TAG", "tag", stableId, now))
                created
            }
            taxonomyDao.assignTag(
                com.example.bilimonitor.data.local.entity.StreamerTagCrossRefEntity(streamerId, tag.id)
            )
            tag
        }
    }

    suspend fun unassign(tagId: Long, streamerId: Long) = taxonomyDao.unassignTag(streamerId, tagId)

    /** 删除标签本身（关联行随外键级联清理）。 */
    suspend fun deleteTag(tagId: Long) {
        val now = clock.nowWall()
        db.withTransaction {
            val tag = taxonomyDao.listTags().firstOrNull { it.id == tagId } ?: return@withTransaction
            taxonomyDao.deleteTag(tagId)
            logDao.insertAudit(audit("DELETE_TAG", "tag", tag.stableId, now))
        }
    }

    // ---- 分组（原规范 16：此前只有数据层，没有任何应用层能力）----

    fun observeGroups(): Flow<List<GroupEntity>> = taxonomyDao.observeGroups()

    suspend fun groupsWithCount(): List<GroupWithCount> {
        val groups = taxonomyDao.listGroups()
        val refs = taxonomyDao.listGroupRefs()
        return groups.map { g ->
            GroupWithCount(g.id, g.name, refs.count { it.groupId == g.id }, g.collapsed)
        }
    }

    suspend fun groupsOf(streamerId: Long): List<GroupEntity> =
        taxonomyDao.groupsOfStreamer(streamerId)

    /** 创建（或复用同名）分组并关联主播。 */
    suspend fun createGroupAndAssign(name: String, streamerId: Long): GroupEntity {
        val now = clock.nowWall()
        val clean = name.trim()
        require(clean.isNotEmpty()) { "分组名不能为空" }
        return db.withTransaction {
            val existing = taxonomyDao.findGroupByName(clean)
            val group: GroupEntity = existing ?: run {
                val stableId = Ids.stableId("grp")
                val sortOrder = (taxonomyDao.listGroups().maxOfOrNull { it.sortOrder } ?: 0) + 1
                val newId = taxonomyDao.insertGroup(
                    GroupEntity(stableId = stableId, name = clean, sortOrder = sortOrder, collapsed = false)
                )
                val created = GroupEntity(
                    id = newId, stableId = stableId, name = clean, sortOrder = sortOrder, collapsed = false
                )
                logDao.insertAudit(audit("CREATE_GROUP", "group", stableId, now))
                created
            }
            taxonomyDao.assignGroup(
                com.example.bilimonitor.data.local.entity.StreamerGroupCrossRefEntity(streamerId, group.id)
            )
            group
        }
    }

    suspend fun assignGroup(groupId: Long, streamerId: Long) {
        taxonomyDao.assignGroup(
            com.example.bilimonitor.data.local.entity.StreamerGroupCrossRefEntity(streamerId, groupId)
        )
    }

    suspend fun unassignGroup(groupId: Long, streamerId: Long) =
        taxonomyDao.unassignGroup(streamerId, groupId)

    suspend fun deleteGroup(groupId: Long) {
        val now = clock.nowWall()
        db.withTransaction {
            val group = taxonomyDao.listGroups().firstOrNull { it.id == groupId } ?: return@withTransaction
            taxonomyDao.deleteGroup(groupId)
            logDao.insertAudit(audit("DELETE_GROUP", "group", group.stableId, now))
        }
    }

    /** 折叠状态（用于分组视图）。 */
    suspend fun setGroupCollapsed(groupId: Long, collapsed: Boolean) =
        taxonomyDao.setGroupCollapsed(groupId, collapsed)

    // ---- 排序（用户要求：标签/分组支持排序）----

    /**
     * 把标签从第 [fromIndex] 位移到第 [toIndex] 位（都是**当前展示顺序**里的下标，0 起）。
     *
     * 实现是"整表重排成 0..n-1"而不是只交换两行：`createAndAssign` 过去给每个新标签都写
     * `sortOrder = 0`，库里可能有一堆并列 0 的行，此时顺序完全由 `ORDER BY sortOrder, name`
     * 的 name 兜底 —— 只交换两行的话用户会看到"挪了没反应"。整表重排顺带把这些历史遗留的
     * 并列值归一化，并且**只更新 tag 行、不碰任何表结构**（sortOrder 从 schemas/2.json 起
     * 就是建表字段，所以排序功能不需要迁移）。
     *
     * 越界或原地移动一律 no-op，不抛异常：下标来自界面，而列表是异步流的产物，
     * 界面完全可能在数据刚刷新的一瞬间传进一个已经不存在的下标，为此崩掉整页不值得。
     */
    suspend fun moveTag(fromIndex: Int, toIndex: Int) {
        db.withTransaction {
            val ordered = taxonomyDao.listTags().toMutableList()
            if (fromIndex !in ordered.indices || toIndex !in ordered.indices || fromIndex == toIndex) {
                return@withTransaction
            }
            ordered.add(toIndex, ordered.removeAt(fromIndex))
            ordered.forEachIndexed { index, tag -> taxonomyDao.updateTagSortOrder(tag.id, index) }
        }
    }

    /** 分组排序；与 [moveTag] 同构（与"分组不可同级多选"无关，排序是独立能力）。 */
    suspend fun moveGroup(fromIndex: Int, toIndex: Int) {
        db.withTransaction {
            val ordered = taxonomyDao.listGroups().toMutableList()
            if (fromIndex !in ordered.indices || toIndex !in ordered.indices || fromIndex == toIndex) {
                return@withTransaction
            }
            ordered.add(toIndex, ordered.removeAt(fromIndex))
            ordered.forEachIndexed { index, group -> taxonomyDao.updateGroupSortOrder(group.id, index) }
        }
    }

    // ---- 多个主播的标签批量关联（首页多选态、详情页整体编辑）----

    /**
     * 把某主播的标签**整体替换**成 [tagIds]（不是增量添加）。
     *
     * 之所以要"整体替换"这个原语：多标签之后，界面上更自然的编辑方式是"勾选一组标签后确定"，
     * 而增量接口无法表达"取消勾选"—— 调用方就得自己 diff，迟早diff错。
     * 重复 id 在这里去重（PK 是 (streamerId, tagId)，重复插入本来也会被 IGNORE 掉）。
     */
    suspend fun setTagsOf(streamerId: Long, tagIds: List<Long>) {
        db.withTransaction {
            taxonomyDao.clearTagsOfStreamer(streamerId)
            tagIds.distinct().forEach { tagId ->
                taxonomyDao.assignTag(
                    com.example.bilimonitor.data.local.entity.StreamerTagCrossRefEntity(streamerId, tagId)
                )
            }
        }
    }

    /** 给多个主播批量打同一个标签（已经打过的靠主键冲突 + IGNORE 变成 no-op）。 */
    suspend fun assignTagTo(streamerIds: List<Long>, tagId: Long) {
        val ids = streamerIds.distinct()
        if (ids.isEmpty()) return
        db.withTransaction {
            taxonomyDao.assignTags(
                ids.map { com.example.bilimonitor.data.local.entity.StreamerTagCrossRefEntity(it, tagId) }
            )
        }
    }

    /** 从多个主播批量移除同一个标签。 */
    suspend fun unassignTagFrom(streamerIds: List<Long>, tagId: Long) {
        val ids = streamerIds.distinct()
        if (ids.isEmpty()) return
        taxonomyDao.unassignTagFrom(tagId, ids)
    }

    // ---- 删除前的影响面（二次确认文案要用）----

    /** 这个标签挂在多少位**未删除**的主播上（软删主播不计，与首页可见口径一致）。 */
    suspend fun streamerCountOfTag(tagId: Long): Int = taxonomyDao.countStreamersOfTag(tagId)

    /** 这个分组里有多少位**未删除**的主播（软删主播不计）。 */
    suspend fun streamerCountOfGroup(groupId: Long): Int = taxonomyDao.countStreamersOfGroup(groupId)

    /** 首页卡片显示标签用：一次取回这批主播的关联行，由上层按 streamerId 分组去取标签名。 */
    suspend fun tagRefsOf(streamerIds: List<Long>): List<com.example.bilimonitor.data.local.entity.StreamerTagCrossRefEntity> =
        if (streamerIds.isEmpty()) emptyList() else taxonomyDao.tagRefsOf(streamerIds.distinct())

    /** 卡片上要显示分组归属时用（与 [tagRefsOf] 同构）。 */
    suspend fun groupRefsOf(streamerIds: List<Long>): List<com.example.bilimonitor.data.local.entity.StreamerGroupCrossRefEntity> =
        if (streamerIds.isEmpty()) emptyList() else taxonomyDao.groupRefsOf(streamerIds.distinct())

    private fun audit(action: String, targetType: String, stableId: String, now: Long) = AuditLogEntity(
        auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
        action = action, targetType = targetType, targetStableId = stableId,
        occurredAt = now, detailJson = null
    )
}
