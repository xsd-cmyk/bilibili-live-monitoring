package com.example.bilimonitor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.data.repository.NotificationDeliverySettingsRepository
import com.example.bilimonitor.domain.policy.NotificationTimelinessSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NotificationDeliverySettingsViewModel @Inject constructor(
    private val repository: NotificationDeliverySettingsRepository
) : ViewModel() {
    val settings = repository.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val message = MutableStateFlow<String?>(null)
    fun save(value: NotificationTimelinessSettings) {
        viewModelScope.launch {
            try {
                repository.set(value)
                message.value = "通知有效期已保存"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "保存失败：${e.message}" }
        }
    }
}

@Composable
fun NotificationDeliverySettingsCard(viewModel: NotificationDeliverySettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saved = settings ?: return
    var live by remember(saved) { mutableStateOf(saved.liveMinutes.toString()) }
    var end by remember(saved) { mutableStateOf(saved.endMinutes.toString()) }
    var change by remember(saved) { mutableStateOf(saved.changeMinutes.toString()) }
    fun valid(text: String) = text.toIntOrNull()?.let { it in 1..1440 } == true
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("通知有效期", style = MaterialTheme.typography.titleMedium)
            Text("从确认变化时开始计时。超过有效期不再弹出提醒，历史记录仍保留。",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(live, { live = it }, label = { Text("开播提醒有效期（分钟）") }, isError = !valid(live))
            OutlinedTextField(end, { end = it }, label = { Text("下播提醒有效期（分钟）") }, isError = !valid(end))
            OutlinedTextField(change, { change = it }, label = { Text("标题／分区变化有效期（分钟）") }, isError = !valid(change))
            Text("允许 1–1440 分钟；系统状态提醒有效期 5 分钟，封禁变化 10 分钟。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = valid(live) && valid(end) && valid(change), onClick = {
                viewModel.save(NotificationTimelinessSettings(live.toInt(), end.toInt(), change.toInt()))
            }) { Text("保存有效期") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
