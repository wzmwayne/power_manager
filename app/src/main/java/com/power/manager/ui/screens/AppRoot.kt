package com.power.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.power.manager.core.HardwareProbe
import com.power.manager.data.Template
import com.power.manager.ui.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab { HOME, SETTINGS, LOG }

sealed class Route {
    object Tabs : Route()
    data class Edit(val template: Template) : Route()
}

@Composable
fun AppRoot() {
    var consented by remember { mutableStateOf(AppStore.isConsented()) }
    if (!consented) {
        val ctx = LocalContext.current
        ConsentScreen(onAllow = {
            AppStore.setConsented()
            consented = true
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    HardwareProbe.scan(ctx)
                } catch (e: Throwable) {
                }
            }
        })
        return
    }
    var route by remember { mutableStateOf<Route>(Route.Tabs) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    when (val r = route) {
        is Route.Edit -> EditScreen(template = r.template, onBack = { route = Route.Tabs })
        Route.Tabs -> {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == Tab.HOME,
                            onClick = { tab = Tab.HOME },
                            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                            label = { Text("首页") }
                        )
                        NavigationBarItem(
                            selected = tab == Tab.SETTINGS,
                            onClick = { tab = Tab.SETTINGS },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text("设置") }
                        )
                        NavigationBarItem(
                            selected = tab == Tab.LOG,
                            onClick = { tab = Tab.LOG },
                            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                            label = { Text("日志") }
                        )
                    }
                }
            ) { padding ->
                when (tab) {
                    Tab.HOME -> HomeScreen(padding = padding, onEdit = { route = Route.Edit(it) })
                    Tab.SETTINGS -> SettingsScreen(padding = padding, onOpenLog = { tab = Tab.LOG })
                    Tab.LOG -> LogScreen(padding = padding, onBack = { tab = Tab.HOME })
                }
            }
        }
    }
}

@Composable
fun ConsentScreen(onAllow: () -> Unit) {
    var count by remember { mutableStateOf(5) }
    LaunchedEffect(Unit) {
        while (count > 0) {
            delay(1000)
            count--
        }
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("严重警告", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        "Power Manager 是测试软件，会对系统进行深度电源管理干预：强制清理后台进程、限制帧率与 CPU 频率、禁用动画、钳制亮度、关闭蓝牙、限制后台网络与 GPS。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "使用本模块可能导致系统卡顿、应用异常、数据丢失甚至无法开机，后果由使用者自行承担。请勿在主力设备上使用。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (count > 0) "请在阅读并理解风险后，等待倒计时 $count 秒后确认。" else "已了解风险，可确认运行。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onAllow,
                        enabled = count <= 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (count > 0) "允许模块运行（$count）" else "允许模块运行")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "首次确认后不再提示。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
