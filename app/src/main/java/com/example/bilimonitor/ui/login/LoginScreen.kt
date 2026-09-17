package com.example.bilimonitor.ui.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.example.bilimonitor.data.repository.AccountState
import com.example.bilimonitor.data.repository.AuthRepository
import com.example.bilimonitor.data.repository.FollowImportRepository
import com.example.bilimonitor.data.repository.HISTORY_PS_MAX
import com.example.bilimonitor.data.repository.IMPORT_MAX_COUNT
import com.example.bilimonitor.data.repository.IMPORT_PAGE_CAP
import com.example.bilimonitor.data.repository.IMPORT_INTERRUPTED_REASON
import com.example.bilimonitor.data.repository.ImportSource
import com.example.bilimonitor.data.repository.StagedFollow
import com.example.bilimonitor.data.repository.QrLoginState
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 导入是否仍在途中（REQUESTED / FETCHING / APPLYING）。
 * `null` 表示"刚发起、轮询还没读到任务状态"，同样算在途。
 *
 * 为什么抽成一个判据：UI 的按钮置灰与 ViewModel 的重复点击守卫必须口径一致，
 * 否则会出现"按钮可点但点了没反应"（或反过来：守卫不挡，连点起多个任务）。
 * 注意 [LoginUiState.importStage] 在轮询里存的是 `"TASK:<状态>"`，这里去掉前缀再比。
 */
private fun LoginUiState.importInFlight(): Boolean {
    val stage = importStage?.removePrefix("TASK:")
    return importTaskId != null && (stage == null || stage in IN_FLIGHT_IMPORT_STAGES)
}

/** 仍在消耗接口配额、尚未出结果的三个阶段（FollowImportStatus 的三个中间态）。 */
private val IN_FLIGHT_IMPORT_STAGES = setOf("REQUESTED", "FETCHING", "APPLYING")

data class LoginUiState(
    val account: AccountState = AccountState(),
    val qrState: QrLoginState = QrLoginState.Idle,
    val qrBitmap: Bitmap? = null,
    val importSource: ImportSource = ImportSource.FOLLOWINGS,
    val importCount: Int = 50,
    /** 全部获取：忽略自定义数量，翻页直到接口没有更多数据（或触到上限）。 */
    val importAll: Boolean = false,
    /** 导入条数上限，可在本页修改（默认 500）。 */
    val importCap: Int = IMPORT_MAX_COUNT,
    val importTaskId: String? = null,
    val importStage: String? = null,
    /** 抓取阶段已获取的条目数（分批翻页时用它显示进度）。 */
    val importFetched: Int = 0,
    val importPreview: List<StagedFollow> = emptyList(),
    /**
     * 预览对应的**那一份**任务 id（缺陷 5）：applyImport 必须用它。
     * `importTaskId` 会被后续的导入覆盖，用它去读暂存行等于把"当前任务"的数据
     * 当成"用户看到的那一份"写进库。
     */
    val previewTaskId: String? = null,
    val previewQuery: String = "",
    val selectedUids: Set<Long> = emptySet(),
    val importResult: String? = null,
    /**
     * 预览是"部分结果"的原因（缺陷 1/2）：抓取中途被服务端拒绝（如风控 code=-352）或
     * 触到分页上限时，任务表里的原因会原样搬到这里，必须在预览弹窗里显示出来 ——
     * 用户要 500 条只拿到 30 条时，不能再看到"导入完成"。
     */
    val importWarning: String? = null,
    /**
     * "上次导入没跑完"的常驻提示（点"知道了"才消失）。
     *
     * 为什么要有它：冷启动结算（StartupRecovery.settleInterrupted）只把结果写进数据库与
     * 诊断包，用户不进导入流程就永远看不到 —— 违反"任何降级都要让用户知道"的硬规则。
     * 文案自带"上次导入…"前缀（见 LoginViewModel.unfinishedImportNotice），
     * 界面不再叠一层，否则冷启动写进库的那句原因会变成"上次导入未完成：导入未完成：…"。
     */
    val lastImportNotice: String? = null,
    val error: String? = null
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val followImportRepository: FollowImportRepository,
    private val importSettings: com.example.bilimonitor.data.repository.ImportSettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    init {
        viewModelScope.launch { authRepository.refreshAccount() }
        viewModelScope.launch {
            // ★ 进页面时读一次"最近一次导入任务"：冷启动会把上次被杀留下的在途任务结算成
            //   FAILED（见 StartupRecovery.settleInterrupted），但那条原因原先只有走进导入
            //   轮询才看得到 —— 用户不进导入流程就完全不知道"上次的导入根本没跑完"。
            val last = runCatching { followImportRepository.latestTask() }.getOrNull()
            val notice = last?.let { unfinishedImportNotice(it) }
            if (notice != null) {
                // ★ 用 update 做**原子**的条件写入：本页 init 里有 5 条协程都在写 _state，
                //   而 `_state.value = _state.value.copy(...)` 是读-改-写，两条协程交错时
                //   后写的会把先写的字段覆盖掉。这里的两个条件也必须与写入同一次 CAS 完成：
                //   - importInFlight()：读库期间用户可能已经点了"重新导入"，那一开始就把旧提示
                //     清掉了，此时不能再把"上次没跑完"塞回来；
                //   - lastImportNotice == null：用户已经点过"知道了"就别再弹。
                _state.update { st ->
                    if (st.importInFlight() || st.lastImportNotice != null) st
                    else st.copy(lastImportNotice = notice)
                }
            }
        }
        viewModelScope.launch {
            importSettings.maxCount.collect { cap -> _state.value = _state.value.copy(importCap = cap) }
        }
        viewModelScope.launch {
            authRepository.account.collect { _state.value = _state.value.copy(account = it) }
        }
        viewModelScope.launch {
            authRepository.qrState.collect { qr ->
                // 位图必须**持久化**：只保留最近一次成功生成的那张，直到登录成功或过期才清除。
                //
                // 原实现是 `(qr as? QrReady)?.let { qrBitmap(it) }` —— 只要状态不是 QrReady
                // 就把 qrBitmap 置为 null。而生成二维码后立即开始轮询，状态立刻变成 WaitingScan，
                // 于是位图被清空、二维码从屏幕上消失（实测日志：QrReady 后 100ms 就 WaitingScan）。
                // 用户看到的就是"二维码只闪一下"。
                val keepExisting = _state.value.qrBitmap
                val bmp = when (qr) {
                    // 位图生成放到 Default 线程：ZXing 编码 560×560 + IntArray(313600)
                    // + ARGB_8888 位图（约 1.25MB 分配）都在这里，留在主线程会掉帧。
                    // withContext 返回后仍回到原来的 Main，下面的状态写入不受影响。
                    is QrLoginState.QrReady ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            qrBitmap(qr.qrUrl)
                        }
                    // 终态：清除位图
                    is QrLoginState.Confirmed, QrLoginState.Expired, QrLoginState.Idle -> null
                    is QrLoginState.Failed -> null
                    // WaitingScan / ScannedWaitingConfirm：沿用已生成的那张
                    else -> keepExisting
                }
                android.util.Log.i(
                    "QrLogin",
                    "qrState=${qr::class.java.simpleName} → qrBitmap=${if (bmp == null) "null" else "有图"}" +
                        "（沿用旧图=${bmp != null && qr !is QrLoginState.QrReady}）"
                )
                _state.value = _state.value.copy(qrState = qr, qrBitmap = bmp)
            }
        }
    }

    /**
     * 当前进行中的二维码登录协程。
     *
     * 存在的意义：`startQrLogin()` 在点击回调里被调用，而点击可能连续触发
     * （实测日志里 1 秒内触发了 5 次 `generateQr`）。原实现每次调用都
     * **重新生成二维码并起一个新的 90 轮轮询协程** ——
     * 后果是二维码被反复替换（视觉上就是"闪一下"）、旧的轮询协程泄漏、
     * 多个协程同时 poll 同一个会话。
     * 这里用 Job 做幂等保护：已有进行中的登录就不重复发起。
     */
    private var qrLoginJob: kotlinx.coroutines.Job? = null

    /**
     * 把"最近一次导入任务的收尾状态"翻译成一句给用户看的话；不需要提示时返回 null。
     *
     * 只报**没跑完**的情况，且刻意不报"普通的导入失败"：
     * - 普通失败（如风控 -352）用户当场就看到错误横幅了，而任务行会保留 30 天 ——
     *   每次进页面都再弹一遍只是噪音，且没有任何新信息。
     * - 只有"进程被杀、由冷启动结算"这一种失败是用户**从没被告知过**的，按常量
     *   [IMPORT_INTERRUPTED_REASON] 精确识别（写入方与识别方必须是同一个常量）。
     */
    private fun unfinishedImportNotice(
        task: com.example.bilimonitor.data.local.entity.FollowImportTaskEntity
    ): String? = when {
        task.status == com.example.bilimonitor.data.local.FollowImportStatus.COMPLETED -> null
        // 冷启动结算出来的失败：原因文案本身就是写给用户看的，直接用
        task.status == com.example.bilimonitor.data.local.FollowImportStatus.FAILED &&
            task.error == IMPORT_INTERRUPTED_REASON -> task.error
        // 结算还没来得及跑（或没跑到）时可能读到的中间态：如实说停在哪个阶段
        task.status.name in IN_FLIGHT_IMPORT_STAGES ->
            "上次导入没有跑完：任务停在 ${task.status.name} 阶段（进程可能被系统结束），请重新导入"
        // 预览只活在内存里，进程一死那份预览就再也够不到了（MoreDaos 把 PREVIEW 也算在途）
        task.status == com.example.bilimonitor.data.local.FollowImportStatus.PREVIEW ->
            "上次导入只到预览这一步：已获取 ${task.stagingCount} 位主播，但没有导入任何一位，请重新导入"
        else -> null
    }

    /** 用户确认看到"上次导入没跑完"之后关掉它（本轮会话内不再出现）。 */
    fun dismissLastImportNotice() {
        _state.value = _state.value.copy(lastImportNotice = null)
    }

    fun startQrLogin() {
        if (qrLoginJob?.isActive == true) {
            android.util.Log.i("QrLogin", "已有进行中的二维码登录，忽略重复点击")
            return
        }
        qrLoginJob = viewModelScope.launch {
            _state.value = _state.value.copy(error = null, importResult = null)
            if (authRepository.generateQr()) {
                val key = (authRepository.qrState.value as? QrLoginState.QrReady)?.key ?: return@launch
                for (i in 1..90) {
                    val done = authRepository.pollQrOnce(key)
                    if (done) break
                    delay(2000)
                }
            }
        }
    }

    fun setImportSource(source: ImportSource) {
        _state.value = _state.value.copy(importSource = source)
    }

    fun setImportCount(count: Int) {
        _state.value = _state.value.copy(importCount = count.coerceIn(1, IMPORT_MAX_COUNT))
    }

    fun setImportAll(all: Boolean) {
        _state.value = _state.value.copy(importAll = all)
    }

    /** 修改导入上限（默认 500）。自定义数量同样受它约束。 */
    fun setImportCap(cap: Int) {
        val v = cap.coerceIn(1, com.example.bilimonitor.data.repository.ImportSettingsRepository.ABSOLUTE_MAX)
        viewModelScope.launch { importSettings.setMaxCount(v) }
    }

    /**
     * 进行中的导入协程。
     *
     * 与 [qrLoginJob] 同一套路：`startFollowImport()` 在按钮回调里被调用，而按钮没有忙状态，
     * 连点 N 次就会 `insertTask` N 次、起 N 条轮询协程、并发跑 N 路分页抓取
     * （"全部获取"最多 17 页/人）——上游限流、暂存行堆积、界面状态互相覆盖。
     *
     * ★ 但这个 job 自己**挡不住连点**（缺陷 4）：`pollImportTask` 是另起的协程，
     *   外层 job 立刻结束。真正的守卫是下面 `importInFlight()` 那个在途任务判断。
     */
    private var importJob: kotlinx.coroutines.Job? = null

    fun startFollowImport() {
        if (importJob?.isActive == true) return
        // ★ 上面这个 job 守卫**实际挡不住连点**（缺陷 4）：协程进入 pollImportTask 就立刻
        //   结束了（轮询是另起的协程），所以"任务还在抓取"期间 importJob.isActive 已是 false。
        //   真正的判据是**在途任务**：它还在请求/写入时直接拒绝，否则连点与"重试"会起多个
        //   并发任务 —— 上游限流、暂存行堆积、界面状态互相覆盖。
        val st = _state.value
        if (st.importInFlight()) {
            val where = st.importStage?.removePrefix("TASK:") ?: "刚发起"
            android.util.Log.i("FollowImport", "已有在途导入任务（${where}），忽略重复点击")
            // 不静默忽略：如实告诉用户这次点击没有生效，以及为什么
            _state.value = st.copy(error = "已有导入正在进行（${where}），已忽略这次点击")
            return
        }
        val source = _state.value.importSource
        val count = _state.value.importCount
        val all = _state.value.importAll
        importJob = viewModelScope.launch {
            val taskId = followImportRepository.startImport(source, count, all)
            if (taskId == null) {
                _state.value = _state.value.copy(error = "请先登录")
            } else {
                _state.value = _state.value.copy(
                    importTaskId = taskId, importResult = null, importFetched = 0,
                    importStage = null, importPreview = emptyList(), selectedUids = emptySet(),
                    // 新一次导入开始：旧的预览快照与"部分结果"提示都不再适用（缺陷 5）
                    previewTaskId = null, importWarning = null,
                    // 新的一轮开始了，"上次未完成"的旧提示不再适用
                    lastImportNotice = null
                )
                pollImportTask(taskId)
            }
        }
    }

    private fun pollImportTask(taskId: String) {
        viewModelScope.launch {
            for (i in 1..60) {
                val task = runCatching { followImportRepository.latestTask() }.getOrNull()
                if (task?.importTaskId == taskId) {
                    // 把任务真实状态暴露到 UI：卡在 REQUESTED/FETCHING 时用户能直接看到，
                    // 而不是只看到一个永远转圈的"正在获取列表"。
                    _state.value = _state.value.copy(
                        importStage = "TASK:" + task.status.name,
                        importFetched = task.stagingCount.toInt()
                    )
                    when (task.status) {
                        com.example.bilimonitor.data.local.FollowImportStatus.PREVIEW -> {
                            val preview = followImportRepository.listStaging(taskId)
                            _state.value = _state.value.copy(
                                importStage = "PREVIEW", importPreview = preview,
                                // 快照这一份预览属于哪个任务：applyImport 只能用它（缺陷 5）
                                previewTaskId = taskId,
                                // task.error 非空 = 这次是**部分结果**（翻页被拒 / 触到分页上限）。
                                // 原先它被 insertStaging 擦成 null，用户看到的是"导入完成"（缺陷 1/2）。
                                importWarning = task.error,
                                selectedUids = preview.map { it.uid }.toSet()
                            )
                            return@launch
                        }
                        com.example.bilimonitor.data.local.FollowImportStatus.FAILED,
                        com.example.bilimonitor.data.local.FollowImportStatus.CANCELLED -> {
                            _state.value = _state.value.copy(
                                importStage = "FAILED",
                                // 任务失败：上一份预览与它的"部分结果"提示都不再适用 ——
                                // 弹窗分派先看 importPreview 是否为空，不清就会被预览弹窗盖住失败原因。
                                importPreview = emptyList(), selectedUids = emptySet(),
                                previewTaskId = null, importWarning = null,
                                error = task.error ?: "导入失败（无详细信息）"
                            )
                            return@launch
                        }
                        com.example.bilimonitor.data.local.FollowImportStatus.COMPLETED -> {
                            val preview = followImportRepository.listStaging(taskId)
                            _state.value = _state.value.copy(
                                importStage = if (preview.isEmpty()) "FAILED" else "PREVIEW",
                                importPreview = preview,
                                // 没有可应用的暂存行就不留快照，免得 applyImport 拿空列表去写库（缺陷 5）
                                previewTaskId = if (preview.isEmpty()) null else taskId,
                                selectedUids = preview.map { it.uid }.toSet(),
                                error = if (preview.isEmpty()) "没有获取到可导入的主播" else null
                            )
                            return@launch
                        }
                        else -> Unit // REQUESTED / FETCHING / APPLYING：继续等
                    }
                }
                delay(1500)
            }
            // ★ 放弃轮询之前再复核一次（复查发现的缺陷）：抓取可能刚好在这之后完成 ——
            //   数据已经在暂存表里，用户却既看不到预览、也无法重试，只能杀应用重开。
            //   这里若发现任务已经到 PREVIEW，就把那一份正常交给用户（并恢复它的快照 id）。
            val late = runCatching { followImportRepository.latestTask() }.getOrNull()
            if (late?.importTaskId == taskId &&
                late.status == com.example.bilimonitor.data.local.FollowImportStatus.PREVIEW
            ) {
                val preview = followImportRepository.listStaging(taskId)
                if (preview.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        importStage = "PREVIEW", importPreview = preview, previewTaskId = taskId,
                        importWarning = late.error, error = null,
                        selectedUids = preview.map { it.uid }.toSet()
                    )
                    return@launch
                }
            }
            _state.value = _state.value.copy(
                importStage = "TIMEOUT",
                // 轮询放弃了，但后台任务可能还在跑：这一份预览快照就此作废，
                // 避免用一个"没被确认过的"taskId 去应用数据（缺陷 5）
                previewTaskId = null, importWarning = null,
                error = "导入超时（任务长时间未完成，可能网络异常）"
            )
        }
    }

    fun setPreviewQuery(q: String) {
        _state.value = _state.value.copy(previewQuery = q)
    }

    fun toggleSelect(uid: Long) {
        val cur = _state.value.selectedUids
        _state.value = _state.value.copy(selectedUids = if (uid in cur) cur - uid else cur + uid)
    }

    fun applyImport() {
        // ★ 必须用**预览那一份**的任务 id（缺陷 5）：`importTaskId` 会被后续导入覆盖，
        //   用它去读暂存行等于把"当前任务"的数据当成"用户看到的那一份"写进库。
        val previewTaskId = _state.value.previewTaskId
        if (previewTaskId == null) {
            _state.value = _state.value.copy(
                importStage = "EXPIRED", importPreview = emptyList(), selectedUids = emptySet(),
                error = "预览已失效，请重新导入"
            )
            return
        }
        if (previewTaskId != _state.value.importTaskId) {
            // 如实拒绝，且**不写任何数据**：宁可让用户重来一次，也不能写错一份列表。
            // 同时清掉这份过期预览，让弹窗切到"导入未完成"分支把原因显示出来。
            _state.value = _state.value.copy(
                importStage = "EXPIRED", importPreview = emptyList(), selectedUids = emptySet(),
                previewTaskId = null, importWarning = null,
                error = "预览已过期，请重新导入"
            )
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                followImportRepository.applyImport(previewTaskId, _state.value.selectedUids)
            }.getOrElse {
                _state.value = _state.value.copy(error = "导入失败：${it.message}")
                return@launch
            }
            val (added, existed, restored) = result
            // ★ "这次不是完整结果"的提示不能在应用之后消失（复查发现的缺陷）：
            //   原先预览阶段看得到、一点"导入所选"就被清成 null，主卡片只剩"导入完成" ——
            //   用户要 2000 条却因分页上限/风控只拿到一部分时，最终看到的仍是成功。
            val partial = _state.value.importWarning
            _state.value = _state.value.copy(
                importStage = "DONE", importPreview = emptyList(), selectedUids = emptySet(),
                previewTaskId = null, importWarning = partial,
                importResult = buildString {
                    append("导入完成：新增 ${added}，恢复 ${restored}，已存在 ${existed}")
                    if (!partial.isNullOrBlank()) append("；注意：本次不是完整结果 —— ${partial}")
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    /**
     * 用浏览器复制的 Cookie 登录。
     * 比扫码更可靠：扫码只拿到基础凭证，而观看历史这类主站接口还需要
     * 设备标识与风控票据（buvid3 / bili_ticket），浏览器会话里才是完整的。
     */
    fun loginWithCookie(raw: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null, importResult = null)
            val failure = authRepository.loginWithCookie(raw)
            if (failure != null) {
                _state.value = _state.value.copy(error = failure)
            } else {
                val acc = authRepository.account.value
                _state.value = _state.value.copy(
                    importResult = "Cookie 登录成功：${acc.uname}（UID ${acc.mid}）"
                )
            }
        }
    }

    private fun qrBitmap(content: String, size: Int = 560): Bitmap {
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i ->
            if (bits.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}

@Composable
fun LoginScreen(onBack: () -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 导入是否在途：**两处**都要用它 —— 登录卡片的「开始导入」与失败弹窗的「重试」。
    // 判据与 ViewModel 的 startFollowImport 守卫共用 importInFlight()，
    // 避免"按钮可点但点击被忽略"这种两套口径（缺陷 4）。
    val importRunning = state.importInFlight()
    var showImportPreview by remember { mutableStateOf(false) }
    var showCookieDialog by remember { mutableStateOf(false) }
    var countText by remember { mutableStateOf("50") }
    // 上限输入框：跟随配置值刷新，但用户正在编辑时不要被覆盖（用 remember 记录是否已同步过）
    var capText by remember { mutableStateOf(state.importCap.toString()) }
    LaunchedEffect(state.importCap) {
        if (capText.toIntOrNull() != state.importCap) capText = state.importCap.toString()
    }

    // 错误 3 秒后自动清除 —— 但**对话框打开期间除外**：Cookie 登录失败的原因就地显示在
    // 对话框里（那里专门留了显示位置），预期留到用户改完重试；无条件 clearError()
    // 会让提示还没读完就消失。对话框关闭后这里再照常按 3 秒清。
    LaunchedEffect(state.error, showCookieDialog) {
        if (state.error != null && !showCookieDialog) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            FloatingTopBar(
                title = { Text("B 站账号（可选）") },
                navigationIcon = {
                    // 与全项目其它页面统一：返回用箭头图标，而不是中文「返回」文字按钮
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("登录仅用于「关注导入」；核心监控不需要登录。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    if (state.account.checking) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    } else if (state.account.loggedIn) {
                        Text("已登录：${state.account.uname}", fontWeight = FontWeight.SemiBold)
                        Text("UID ${state.account.mid}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        // 导入进行中（任务在 REQUESTED/FETCHING/APPLYING）时置灰按钮：连点会产生
                        // 多个任务与多条轮询协程。判据在 LoginScreen 顶部算好（重试按钮共用同一个），
                        // 到达 PREVIEW/FAILED/TIMEOUT 后即恢复可点。
                        Button(
                            onClick = {
                                viewModel.startFollowImport()
                                showImportPreview = true
                            },
                            enabled = !importRunning
                        ) { Text(if (importRunning) "正在导入…" else "开始导入") }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { viewModel.logout() }) { Text("退出登录") }
                    } else {
                        Button(onClick = { viewModel.startQrLogin() }) { Text("扫码登录") }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { showCookieDialog = true }) { Text("用 Cookie 登录") }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "两种方式都可导入（实测只需 SESSDATA + buvid3）。" +
                                "扫码走不通时，用 Cookie 登录把浏览器里那串 Cookie 整段粘进来即可。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    state.importResult?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.primary)
                    }
                    // 部分结果要留在页面上（缺陷 1/2）：弹窗被关掉之后用户仍然必须能看到
                    // "这次不是全部"。它放在 importWarning 里，不受 error 的 3 秒自动清除影响。
                    state.importWarning?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "部分结果：$it",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    // "上次导入未完成"（冷启动结算出来的）：必须让用户看见，而不是只躺在数据库里。
                    // 它是常驻提示，点"知道了"才消失（与 3 秒自动清除的 error 区分开）。
                    state.lastImportNotice?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            // 文案自带"上次导入…"前缀（见 unfinishedImportNotice）：
                            // 冷启动写进库的那句原因本身就以"导入未完成："开头，再套前缀会重复
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { viewModel.dismissLastImportNotice() }) { Text("知道了") }
                    }
                }
            }

            if (state.account.loggedIn) {
                // 导入来源 + 数量设置
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("导入设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))

                        ImportSource.entries.forEach { source ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { viewModel.setImportSource(source) }
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = state.importSource == source,
                                    onClick = { viewModel.setImportSource(source) }
                                )
                                Text(source.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (state.importSource) {
                                ImportSource.LIVE_WATCH_HISTORY ->
                                    "从观看历史中的直播分类，获取最近看过的主播（接口单次最多 30 条）"
                                ImportSource.FOLLOWINGS ->
                                    "从关注列表获取（分批获取，不全量拉取）"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "直播观看历史导入依赖「你曾在 B 站看过直播」。若你的观看记录里只有视频，" +
                                "该来源会没有内容 —— 此时请改用「按关注导入」。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("获取数量：", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = countText,
                                onValueChange = { input ->
                                    countText = input.filter { ch -> ch.isDigit() }.take(4)
                                    countText.toIntOrNull()?.let { viewModel.setImportCount(it) }
                                },
                                modifier = Modifier.width(100.dp),
                                singleLine = true,
                                enabled = !state.importAll,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        // 「全部获取」：接口单页最多 30 条，超过就自动分批翻页，
                        // 直到没有更多数据（或触到可配置的上限）。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.setImportAll(!state.importAll)
                            }
                        ) {
                            Checkbox(
                                checked = state.importAll,
                                onCheckedChange = { viewModel.setImportAll(it) }
                            )
                            Text("全部获取", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "（翻页直到取完）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("上限：", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = capText,
                                onValueChange = { input ->
                                    capText = input.filter { ch -> ch.isDigit() }.take(6)
                                    capText.toIntOrNull()?.let { viewModel.setImportCap(it) }
                                },
                                modifier = Modifier.width(110.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "条（自定义数量与「全部获取」共用）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 这句以前写的是"最终仍取到你填的条数"——那是假的：分页上限
                        // IMPORT_PAGE_CAP × HISTORY_PS_MAX = 1200 条就是天花板（缺陷 2）。
                        Text(
                            "接口单页最多 ${HISTORY_PS_MAX} 条，超过会自动分批翻页；" +
                                "但单次导入最多翻 ${IMPORT_PAGE_CAP} 页（即 ${IMPORT_PAGE_CAP * HISTORY_PS_MAX} 条），" +
                                "要更多时只能拿到这么多 —— 届时任务里会写明「已达单次分页上限」。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 二维码区域。
            // 关键：**只要还有二维码图片就显示它**，状态变化只改下面的提示文案。
            // 原实现把整张卡片放在 `is QrLoginState.QrReady ->` 分支里，而轮询一旦开始
            // 状态就变成 WaitingScan / ScannedWaitingConfirm，卡片随之消失 ——
            // 表现就是用户看到的"二维码只弹一下就没了"（其实图还在内存里）。
            val qrBitmap = state.qrBitmap
            android.util.Log.i(
                "QrLogin",
                "渲染：qrState=${state.qrState::class.java.simpleName}，qrBitmap=${if (qrBitmap == null) "null" else "有图"}" +
                    " → 显示二维码=${qrBitmap != null && state.qrState !is QrLoginState.Confirmed}"
            )
            if (qrBitmap != null && state.qrState !is QrLoginState.Confirmed) {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "登录二维码",
                            modifier = Modifier.size(220.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        // 状态文案（与二维码同时存在）
                        when (val qr = state.qrState) {
                            QrLoginState.ScannedWaitingConfirm ->
                                Text("已扫码，请在手机上确认…", fontWeight = FontWeight.Medium)
                            QrLoginState.WaitingScan ->
                                Text("请用哔哩哔哩 App 扫码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            QrLoginState.Expired ->
                                Text("二维码已过期，请重新生成", color = MaterialTheme.colorScheme.error)
                            is QrLoginState.Failed ->
                                Text(qr.message, color = MaterialTheme.colorScheme.error)
                            else ->
                                Text("用哔哩哔哩 App 扫码登录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 无二维码时的提示 / 操作
            if (qrBitmap == null || state.qrState is QrLoginState.Confirmed) {
                when (val qr = state.qrState) {
                    QrLoginState.Expired -> {
                        Text("二维码已过期", color = MaterialTheme.colorScheme.error)
                        Button(onClick = { viewModel.startQrLogin() }) { Text("重新生成") }
                    }
                    is QrLoginState.Confirmed ->
                        Text("登录成功：${qr.account.uname}", color = MaterialTheme.colorScheme.primary)
                    is QrLoginState.Failed ->
                        Text(qr.message, color = MaterialTheme.colorScheme.error)
                    // Idle 同时出现在两种场合：未登录（该提示"去扫码"）与已登录后的初始态
                    // （此时再说"尚未登录"就是明显的错误信息，曾真的把用户绕晕）。
                    QrLoginState.Idle -> if (!state.account.loggedIn) {
                        Text(
                            "尚未登录。点击上方「扫码登录」获取二维码。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    // Cookie 登录对话框
    if (showCookieDialog) {
        var cookieText by remember { mutableStateOf("") }
        // 默认遮挡：SESSDATA 等同于账号密码，明文显示在屏幕上（截屏/录屏/旁人）就是泄露面。
        // 但粘贴后往往需要核对，所以留一个显式开关，而不是一律隐藏。
        var cookieVisible by remember { mutableStateOf(false) }
        // 本次对话框会话内是否已提交过。
        // 只在"提交过且拿到成功结果"后才关闭并清空；失败则保留输入（用户改一下就重试）。
        // 用对话框内的 remember 而不是 VM 标志：重新打开对话框时它天然为 false，
        // 不会被上一次的成功结果误触发。
        var cookieSubmitted by remember { mutableStateOf(false) }
        val cookieLoginOk = state.importResult?.contains("Cookie 登录成功") == true
        LaunchedEffect(cookieSubmitted, cookieLoginOk) {
            if (cookieSubmitted && cookieLoginOk) {
                cookieText = ""
                showCookieDialog = false
            }
        }
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text("用 Cookie 登录") },
            text = {
                Column {
                    Text(
                        "在电脑浏览器登录 bilibili.com 后，按 F12 → Network → 任选一个 api.bilibili.com 请求，" +
                            "复制请求头里的 Cookie 值，粘贴到下面。\n\n" +
                            "内容仅保存在本机（加密存储），不会上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cookieText,
                        onValueChange = { cookieText = it },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        placeholder = { Text("SESSDATA=...; bili_jct=...; DedeUserID=...") },
                        label = { Text("Cookie") },
                        visualTransformation = if (cookieVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { cookieVisible = !cookieVisible }) {
                                Text(if (cookieVisible) "隐藏" else "显示")
                            }
                        }
                    )
                    // 失败原因就地显示：对话框现在会留在屏幕上（不再一点"登录"就关），
                    // 不该让用户去被遮住的页面里找提示。
                    if (cookieSubmitted) {
                        state.error?.let { err ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.loginWithCookie(cookieText)
                        // 这里不清空、不关闭：成功才关闭并清空，失败保留输入待用户修改重试
                        // （否则失败后用户得回浏览器重新复制整串 Cookie）。
                        cookieSubmitted = true
                    },
                    enabled = cookieText.isNotBlank()
                ) { Text("登录") }
            },
            dismissButton = {
                TextButton(onClick = { showCookieDialog = false }) { Text("取消") }
            }
        )
    }

    // 导入预览对话框。
    // 原实现条件为 `showImportPreview && importStage == "PREVIEW"`，而"开始导入"会立刻把
    // showImportPreview 置 true、importStage 却要等轮询成功才置位；一旦导入失败或超时
    // （importStage 永远不是 PREVIEW），弹窗永远不出现，用户看不到任何结果也没有重试入口。
    // 现在改为：只要发起过导入就给出明确反馈，成功才弹预览。
    if (showImportPreview) {
        when {
            state.importStage == "PREVIEW" || state.importPreview.isNotEmpty() -> {
                ImportPreviewDialog(
                    state = state,
                    onToggle = { viewModel.toggleSelect(it) },
                    onQuery = { viewModel.setPreviewQuery(it) },
                    onApply = {
                        viewModel.applyImport()
                        showImportPreview = false
                    },
                    onDismiss = { showImportPreview = false }
                )
            }
            // 轮询期间：显示**任务真实状态**（而不是无信息的转圈），可取消
            state.importStage == null || state.importStage!!.startsWith("TASK:") -> {
                AlertDialog(
                    onDismissRequest = { showImportPreview = false },
                    title = { Text("正在获取列表") },
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("正在获取主播列表，请稍候…")
                            }
                            val stage = state.importStage?.removePrefix("TASK:")
                            if (stage != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "任务状态：$stage" + when (stage) {
                                        "REQUESTED" -> "（已请求）"
                                        "FETCHING" -> "（正在拉取" +
                                            (if (state.importFetched > 0) "，已获取 ${state.importFetched} 个" else "") + "）"
                                        "APPLYING" -> "（正在写入）"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    // 这里只是**隐藏**对话框，导入任务仍在后台继续（取消一次导入需要仓库层
                    // 支持：把任务置 CANCELLED 并清掉暂存行，UI 层做不到）。
                    // 原按钮写的是「取消」，用户会以为抓取停了 —— 实际后台还在翻页，
                    // 于是再点一次导入就会有两个任务同时写暂存。文案如实说明现状。
                    dismissButton = {
                        TextButton(onClick = { showImportPreview = false }) { Text("隐藏（后台继续）") }
                    }
                )
            }
            // 失败 / 超时：给出结果并提供重试入口
            else -> {
                AlertDialog(
                    onDismissRequest = { showImportPreview = false },
                    title = { Text("导入未完成") },
                    text = { Text(state.error ?: "没有获取到可导入的主播") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.clearError()
                                viewModel.startFollowImport()
                            },
                            // 在途任务没结束时不允许再点（缺陷 4）：连点会起多个并发任务 ——
                            // 上游限流、暂存行堆积、界面状态互相覆盖。
                            enabled = !importRunning
                        ) { Text("重试") }
                    },
                    dismissButton = { TextButton(onClick = { showImportPreview = false }) { Text("关闭") } }
                )
            }
        }
    }
}

@Composable
private fun ImportPreviewDialog(
    state: LoginUiState,
    onToggle: (Long) -> Unit,
    onQuery: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val filtered = if (state.previewQuery.isBlank()) state.importPreview
    else state.importPreview.filter { it.name.contains(state.previewQuery.trim()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要导入的主播（${state.selectedUids.size}/${state.importPreview.size}）") },
        text = {
            Column {
                // ★ 部分结果必须显式告知（缺陷 1/2）：抓取中途被服务端拒绝（如风控 code=-352）
                //   或触到分页上限时，任务是带着原因走到 PREVIEW 的 —— 用户要 500 条只拿到
                //   30 条却看到"导入完成"，缺的就是这一行。
                state.importWarning?.let { warn ->
                    Text(
                        "注意：这不是完整结果 —— $warn",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // 搜索
                OutlinedTextField(
                    value = state.previewQuery,
                    onValueChange = { onQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索主播名") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    // 原实现在这里显示加载圈：搜索无匹配时表现为无限转圈。
                    // 预览数据已就绪才可能进入本对话框，因此空结果就是"没有匹配"。
                    Text(
                        if (state.previewQuery.isBlank()) "没有可导入的主播"
                        else "没有匹配「${state.previewQuery.trim()}」的主播",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // heightIn(max) 而不是写死高度：AlertDialog 的 text 槽本身不滚动，
                    // 横屏/小屏/大字体下固定 360dp 会把列表底部和确认按钮一起顶出可视区；
                    // 现在只设上限（同时仍保证约束有界，LazyColumn 不会因无限高约束崩溃），
                    // 内容少时也不会白留一大片空白。
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.uid }) { f ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onToggle(f.uid) }
                            ) {
                                Checkbox(checked = f.uid in state.selectedUids, onCheckedChange = { onToggle(f.uid) })
                                AsyncImage(
                                    model = f.avatarUrl, contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                Spacer(Modifier.width(8.dp))
                                // 名字必须自己占住剩余宽度并单行截断：原来的 Text(f.name) 既没有
                                // weight 也没有 maxLines，长名字会把右侧的 UID 直接挤出屏幕。
                                Text(
                                    f.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(f.uid.toString(), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = state.selectedUids.isNotEmpty()) { Text("导入所选") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
