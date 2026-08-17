package com.power.manager.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.power.manager.core.HardwareCap
import com.power.manager.data.AppConfig
import com.power.manager.data.Template
import com.power.manager.ui.AppStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(padding: PaddingValues, onEdit: (Template) -> Unit) {
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(AppStore.load()) }
    var rootAvailable by remember { mutableStateOf(AppStore.isRoot()) }
    var capsInfo by remember { mutableStateOf(readCaps()) }
    var showNewDialog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            rootAvailable = withContext(Dispatchers.IO) { AppStore.isRoot() }
            delay(3000)
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("电源管理") }) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!rootAvailable) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("未检测到 Root 权限，CPU 调频不可用，模块仍以系统 API 模式运行。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            CapabilityCard(capsInfo)
            Text("模板列表", style = MaterialTheme.typography.titleMedium)
            val sorted = remember(cfg) { cfg.templates.values.sortedBy { it.id } }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(sorted, key = { it.id }) { tpl ->
                    TemplateCard(
                        tpl = tpl,
                        active = tpl.id == cfg.currentTemplateId,
                        onApply = {
                            val next = AppStore.copyOf(cfg)
                            AppStore.applyTemplate(next, tpl)
                            cfg = next
                            toast = "已应用模板：${tpl.name}"
                        },
                        onEdit = { onEdit(tpl) },
                        onDelete = {
                            val next = AppStore.copyOf(cfg)
                            AppStore.deleteTemplate(next, tpl)
                            cfg = next
                            toast = "已删除模板：${tpl.name}"
                        }
                    )
                }
            }
            Button(onClick = { showNewDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("新建模板")
            }
        }
    }

    if (showNewDialog) {
        NewTemplateDialog(
            cfg = cfg,
            onPick = { source, name ->
                val next = AppStore.copyOf(cfg)
                val t = AppStore.createTemplate(next, source, name)
                next.templates[t.id] = t
                AppStore.save(next)
                cfg = next
                onEdit(t)
                showNewDialog = false
            },
            onDismiss = { showNewDialog = false }
        )
    }
}

@Composable
fun CapabilityCard(json: String?) {
    if (json.isNullOrBlank()) return
    val cap = try {
        HardwareCap.fromJson(JSONObject(json))
    } catch (e: Throwable) {
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("硬件能力（授权时扫描）", style = MaterialTheme.typography.titleSmall)
            AssistChip(onClick = {}, label = { Text("CPU 调频：${if (cap.cpuFreqSupported) "支持" else "不支持"}") })
            AssistChip(onClick = {}, label = { Text("帧率锁：${if (cap.fpsSupported) "支持" else "不支持"}") })
            AssistChip(onClick = {}, label = { Text("动画：${if (cap.animSupported) "支持" else "不支持"}") })
            AssistChip(onClick = {}, label = { Text("蓝牙：${if (cap.bluetoothSupported) "支持" else "不支持"}") })
            AssistChip(onClick = {}, label = { Text("网络限制：${if (cap.networkSupported) "支持" else "不支持"}") })
            AssistChip(onClick = {}, label = { Text("GPS：${if (cap.gpsSupported) "支持" else "不支持"}") })
            Text(
                "基准：CPU 上限 ${cap.cpuMaxFreqKHz / 1000}MHz · ${cap.cpuCoreCount} 核",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun TemplateCard(
    tpl: Template,
    active: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tpl.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (active) Text(
                    "已激活",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (tpl.isBuiltin) Text(
                    "只读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                templateSummary(tpl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onApply) { Text("应用") }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!tpl.isBuiltin) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NewTemplateDialog(
    cfg: AppConfig,
    onPick: (Template, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建模板") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("模板名称（留空自动命名）") },
                    singleLine = true
                )
                Text("选择来源（复制其参数）：", style = MaterialTheme.typography.bodySmall)
                val sources = cfg.templates.values.sortedBy { it.id }
                sources.forEach { s ->
                    OutlinedButton(
                        onClick = {
                            val finalName = if (name.isBlank()) "${s.name}-副本" else name.trim()
                            onPick(s, finalName)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("复制：${s.name}")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

fun templateSummary(tpl: Template): String {
    val parts = mutableListOf<String>()
    if (tpl.maxBg >= 0) parts.add("后台进程≤${tpl.maxBg}")
    if (tpl.killDelay >= 0) parts.add(if (tpl.killDelay == 0) "切后台即杀" else "${tpl.killDelay}秒后清理")
    if (tpl.targetFps > 0) parts.add("帧率${tpl.targetFps}Hz")
    if (tpl.cpuFreq != -1) parts.add("CPU限制")
    if (tpl.brightnessCap in 1..255) parts.add("亮度≤${tpl.brightnessCap}")
    if (tpl.animOff) parts.add("无动画")
    parts.add(when (tpl.gpsPolicy) {
        0 -> "GPS禁用"
        1 -> "后台GPS禁"
        else -> "GPS放行"
    })
    parts.add(if (tpl.netPolicy == 0) "后台禁网" else "网络放行")
    parts.add(if (tpl.btPolicy == 0) "蓝牙关闭" else "蓝牙保持")
    return parts.joinToString(" · ")
}

fun readCaps(): String? {
    return try {
        val f = File(HardwareCap.capFile())
        if (f.exists()) f.readText() else null
    } catch (e: Throwable) {
        null
    }
}