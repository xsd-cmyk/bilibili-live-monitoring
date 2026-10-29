package com.example.bilimonitor.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.data.repository.NotificationDeepLinkResolver
import com.example.bilimonitor.ui.common.FloatingTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class NotificationBatchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    resolver: NotificationDeepLinkResolver
) : ViewModel() {
    private val _batch = MutableStateFlow<NotificationDeepLinkResolver.BatchDetails?>(null)
    val batch = _batch.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _batch.value = resolver.batchDetails(savedState.get<String>("eventKey").orEmpty())
                if (_batch.value == null) _message.value = "这条批量通知的记录已清理，可查看通知历史。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "读取通知失败，请返回后重试。"
            }
        }
    }
}

@Composable
fun NotificationBatchScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMember: (NotificationDeepLinkResolver.BatchMember, Boolean) -> Unit,
    viewModel: NotificationBatchViewModel = hiltViewModel()
) {
    val batch by viewModel.batch.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        FloatingTopBar(
            title = { Text(if (batch?.isEnd == true) "本批下播记录" else "本批开播主播") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )
    }) { padding ->
        val current = batch
        if (current == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                if (message != null) {
                    Text(message.orEmpty())
                    TextButton(onClick = onOpenHistory) { Text("打开通知历史") }
                } else CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Text("共 ${current.members.size} 位主播", style = MaterialTheme.typography.bodySmall) }
                items(current.members, key = { it.eventKey }) { member ->
                    Card(onClick = { onOpenMember(member, current.isEnd) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(member.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (member.streamerId == null) "主播记录已删除"
                                else if (current.isEnd) "查看这一场直播记录" else "进入直播间",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
