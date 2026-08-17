package com.power.manager.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.power.manager.ui.AppStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(padding: PaddingValues, onOpenLog: () -> Unit, onOpenRules: () -> Unit) {
    val context = LocalContext.current
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("作用域（推荐）", style = MaterialTheme.typography.titleMedium)
            Text(
                "模块注入所有用户应用执行模板策略（后台清理/亮度/帧率/动画/GPS/蓝牙），不依赖 root。请在 LSPosed 管理器为模块勾选全部应用；android（system_server）用于蓝牙锁定。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "全部应用\n（android 系统服务用于蓝牙锁定）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider()

            OutlinedButton(onClick = onOpenRules, modifier = Modifier.fillMaxWidth()) {
                Text("应用单独设置（优先于所有规则）")
            }

            Button(
                onClick = {
                    val next = AppStore.copyOf(AppStore.load())
                    AppStore.resetTemplates(next)
                    toast = "已重置所有模板"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("重置所有模板") }

            OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) { Text("查看日志") }
        }
    }
}
