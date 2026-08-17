package com.power.manager.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import com.power.manager.core.ScopeGuard
import com.power.manager.data.AppConfig
import com.power.manager.ui.AppStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(padding: PaddingValues, onOpenLog: () -> Unit, onOpenRules: () -> Unit) {
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(AppStore.load()) }
    var newPkg by remember { mutableStateOf("") }
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
                "模块仅注入 system_server 等系统作用域，绝不 Hook 其他应用。请按以下推荐在 LSPosed 管理器勾选作用域：",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                ScopeGuard.allowedScopes().joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider()

            OutlinedButton(onClick = onOpenRules, modifier = Modifier.fillMaxWidth()) {
                Text("应用单独设置（优先于所有规则）")
            }

            Text("应用名单模式", style = MaterialTheme.typography.titleMedium)
            Text(
                if (cfg.listMode == AppConfig.LIST_MODE_WHITE)
                    "白名单模式：名单内应用豁免，其余应用一律按模板受限。"
                else
                    "黑名单模式：名单内应用按模板受限，其余应用不受影响。",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(
                    cfg.listMode == AppConfig.LIST_MODE_BLACK,
                    {
                        val next = AppStore.copyOf(cfg)
                        next.listMode = AppConfig.LIST_MODE_BLACK
                        AppStore.save(next)
                        cfg = next
                    },
                    "黑名单",
                    true
                )
                ChoiceChip(
                    cfg.listMode == AppConfig.LIST_MODE_WHITE,
                    {
                        val next = AppStore.copyOf(cfg)
                        next.listMode = AppConfig.LIST_MODE_WHITE
                        AppStore.save(next)
                        cfg = next
                    },
                    "白名单",
                    true
                )
            }
            PkgList(cfg.appList.toList(), onRemove = { pkg ->
                val next = AppStore.copyOf(cfg)
                next.appList.remove(pkg)
                AppStore.save(next)
                cfg = next
            })

            OutlinedTextField(
                value = newPkg,
                onValueChange = { newPkg = it },
                label = { Text("应用包名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                val pkg = newPkg.trim()
                if (pkg.isNotEmpty()) {
                    val next = AppStore.copyOf(cfg)
                    next.appList.add(pkg)
                    AppStore.save(next)
                    cfg = next
                    newPkg = ""
                    toast = "已加入名单"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("加入名单") }

            HorizontalDivider()

            Button(onClick = {
                val next = AppStore.copyOf(cfg)
                AppStore.resetTemplates(next)
                cfg = next
                toast = "已重置所有模板"
            }, modifier = Modifier.fillMaxWidth()) { Text("重置所有模板") }

            OutlinedButton(onClick = {
                toast = if (AppStore.clearEmergency()) "已清除异常关机回退" else "当前无异常回退标记"
            }, modifier = Modifier.fillMaxWidth()) { Text("清除异常关机回退") }

            OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) { Text("查看日志") }
        }
    }
}

@Composable
fun PkgList(list: List<String>, onRemove: (String) -> Unit) {
    if (list.isEmpty()) {
        Text("（空）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        return
    }
    list.forEach { pkg ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(pkg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRemove(pkg) }) {
                Icon(Icons.Filled.Delete, contentDescription = "移除", modifier = Modifier.padding(end = 4.dp))
                Text("移除")
            }
        }
    }
}