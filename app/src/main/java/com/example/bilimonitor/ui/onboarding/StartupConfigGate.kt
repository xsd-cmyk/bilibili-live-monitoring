package com.example.bilimonitor.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.repository.ConfigRepository
import com.example.bilimonitor.ui.nav.Routes
import kotlinx.coroutines.CancellationException

@Composable
internal fun StartupConfigGate(repository: ConfigRepository, content: @Composable (String) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var destination by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        try {
            destination = if (repository.startupChoice() == BackgroundKeepAliveChoice.UNSET) Routes.ONBOARDING else Routes.HOME
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.e("StartupConfig", "启动设置读取失败：${error.javaClass.simpleName}")
            failed = true
        }
    }
    val ready = destination
    if (ready != null) content(ready) else Surface {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (failed) {
                Text("无法读取原有设置")
                Text("尚未进入首次设置。请保留应用数据，不要卸载或清除存储，然后重试。")
                Button(onClick = { failed = false; attempt++ }) { Text("重新读取") }
            } else {
                CircularProgressIndicator()
                Text("正在读取设置…")
            }
        }
    }
}
