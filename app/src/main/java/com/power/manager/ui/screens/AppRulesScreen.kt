package com.power.manager.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.power.manager.data.AppRule
import com.power.manager.ui.AppStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRulesScreen(onBack: () -> Unit) {
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
        topBar = {
            TopAppBar(
                title = { Text("应用单独设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "单独应用设置优先于黑白名单与全局模板。可在该页为指定应用单独配置后台杀死时间与前台/后台启用状态。",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = newPkg,
                onValueChange = { newPkg = it },
                label = { Text("应用包名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val pkg = newPkg.trim()
                    if (pkg.isEmpty()) {
                        toast = "请输入应用包名"
                        return@Button
                    }
                    if (cfg.rules.containsKey(pkg)) {
                        toast = "该应用已有单独设置，可直接编辑下方规则"
                        return@Button
                    }
                    val next = AppStore.copyOf(cfg)
                    next.rules[pkg] = AppRule()
                    AppStore.save(next)
                    cfg = next
                    newPkg = ""
                    toast = "已添加：$pkg"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("添加单独设置") }

            Text("规则列表", style = MaterialTheme.typography.titleMedium)
            if (cfg.rules.isEmpty()) {
                Text("（空）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            cfg.rules.toSortedMap().forEach { (pkg, rule) ->
                RuleCard(
                    pkg = pkg,
                    rule = rule,
                    onChange = { updated ->
                        val next = AppStore.copyOf(cfg)
                        next.rules[pkg] = updated
                        AppStore.save(next)
                        cfg = next
                    },
                    onDelete = {
                        val next = AppStore.copyOf(cfg)
                        next.rules.remove(pkg)
                        AppStore.save(next)
                        cfg = next
                        toast = "已移除：$pkg"
                    }
                )
            }
        }
    }
}

@Composable
fun RuleCard(pkg: String, rule: AppRule, onChange: (AppRule) -> Unit, onDelete: () -> Unit) {
    var killDelay by remember { mutableStateOf(if (rule.killDelay >= 0) rule.killDelay.toString() else "") }
    var enabledFg by remember { mutableStateOf(rule.enabledFg) }
    var enabledBg by remember { mutableStateOf(rule.enabledBg) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pkg, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("前台启用", modifier = Modifier.weight(1f))
                Switch(checked = enabledFg, onCheckedChange = {
                    enabledFg = it
                    onChange(rule.copy(enabledFg = it))
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("后台启用", modifier = Modifier.weight(1f))
                Switch(checked = enabledBg, onCheckedChange = {
                    enabledBg = it
                    onChange(rule.copy(enabledBg = it))
                })
            }
            OutlinedTextField(
                value = killDelay,
                onValueChange = { killDelay = it },
                label = { Text("后台杀死时间（秒，0 即杀，空跟随模板）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("0", "30", "120", "300").forEach { q ->
                    OutlinedButton(onClick = { killDelay = q }) { Text(q) }
                }
            }
            OutlinedButton(
                onClick = {
                    val d = killDelay.trim().toIntOrNull() ?: -1
                    onChange(rule.copy(killDelay = d))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("应用后台杀死时间") }
        }
    }
}