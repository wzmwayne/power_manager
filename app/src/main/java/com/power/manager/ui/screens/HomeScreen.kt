package com.power.manager.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.power.manager.core.HardwareCap
import com.power.manager.core.HardwareProbe
import com.power.manager.data.AppConfig
import com.power.manager.data.Template
import com.power.manager.ui.AppStore
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

@Composable
fun HomeScreen(padding: PaddingValues, onEdit: (Template) -> Unit) {
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(AppStore.load()) }
    var rootAvailable by remember { mutableStateOf(AppStore.isRoot()) }
    var statusJson by remember { mutableStateOf(readStatus(context)) }
    var capsInfo by remember { mutableStateOf(readCaps()) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            statusJson = readStatus(context)
            delay(3000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!cfg.authorized) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("模块尚未授权", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "点击「允许模块运行」完成首次授权。需要 Root 权限，将创建 /pmon 授权信标并清除所有 pmoff 禁用文件，重启后生效。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { showAuthDialog = true }, modifier = Modifier.align(Alignment.End)) {
                        Text("允许模块运行")
                    }
                }
            }
        }
        if (!rootAvailable) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("未检测到 Root 权限", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "CPU 调频与部分保底功能将不可用，模块仍可运行（API 模式）。应用模板时 CPU 限制项将被自动转为不限。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        StatusIndicator(statusJson)
        CapabilityCard(capsInfo)
        Text("模板列表", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            val sorted = cfg.templates.values.sortedBy { it.id }
            items(sorted) { tpl ->
                TemplateCard(
                    tpl = tpl,
                    active = tpl.id == cfg.currentTemplateId,
                    onApply = {
                        AppStore.applyTemplate(cfg, tpl)
                        cfg = AppStore.load()
                        toast = "已应用模板：${tpl.name}"
                    },
                    onEdit = { onEdit(tpl) },
                    onDelete = {
                        AppStore.deleteTemplate(cfg, tpl)
                        cfg = AppStore.load()
                        toast = "已删除模板：${tpl.name}"
                    }
                )
            }
        }
        Button(onClick = { showNewDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("新建模板")
        }
    }

    if (showAuthDialog) {
        AuthDialog(
            onConfirm = {
                val cap = HardwareProbe.scan(context)
                capsInfo = readCaps()
                val ok = AppStore.authorize()
                rootAvailable = AppStore.isRoot()
                cfg = AppStore.load()
                val unsupported = cap.unsupportedList()
                toast = if (ok) {
                    val base = "授权成功，已保存硬件基准，重启后生效"
                    if (unsupported.isEmpty()) base else "$base；不支持：${unsupported.joinToString("、")}"
                } else {
                    "授权失败（需要 Root 权限）"
                }
                showAuthDialog = false
            },
            onDismiss = { showAuthDialog = false }
        )
    }
    if (showNewDialog) {
        NewTemplateDialog(
            cfg = cfg,
            onPick = { source, name ->
                val t = AppStore.createTemplate(cfg, source, name)
                cfg.templates[t.id] = t
                AppStore.save(cfg)
                onEdit(t)
                showNewDialog = false
            },
            onDismiss = { showNewDialog = false }
        )
    }
    toast?.let {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun StatusIndicator(json: String) {
    val info = parseStatus(json) ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(10.dp).background(info.color, CircleShape))
        Text(info.text, style = MaterialTheme.typography.bodyMedium)
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
    Column {
        Text("硬件能力（授权时扫描）", style = MaterialTheme.typography.titleSmall)
        Text(
            "CPU 调频：${if (cap.cpuFreqSupported) "支持" else "不支持"} · " +
                "帧率锁：${if (cap.fpsSupported) "支持" else "不支持"} · " +
                "动画：${if (cap.animSupported) "支持" else "不支持"} · " +
                "蓝牙：${if (cap.bluetoothSupported) "支持" else "不支持"} · " +
                "网络限制：${if (cap.networkSupported) "支持" else "不支持"} · " +
                "GPS：${if (cap.gpsSupported) "支持" else "不支持"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "基准：CPU 上限 ${cap.cpuMaxFreqKHz / 1000}MHz · ${cap.cpuCoreCount} 核",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
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
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onApply) { Text("应用") }
                if (!tpl.isBuiltin) {
                    OutlinedButton(onClick = onEdit) { Text("编辑") }
                    OutlinedButton(onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}

@Composable
fun AuthDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var count by remember { mutableStateOf(3) }
    LaunchedEffect(Unit) {
        while (count > 0) {
            delay(1000)
            count--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("允许模块运行") },
        text = {
            Text(
                if (count > 0)
                    "请阅读风险提示：本模块会强制限制系统资源，极端策略可能造成卡顿或异常。倒计时 $count 秒后可确认。"
                else
                    "即将创建 /pmon 授权信标并清除所有 pmoff 禁用文件。确认后请重启手机，模块正式注入生效。"
            )
        },
        confirmButton = {
            Button(enabled = count <= 0, onClick = onConfirm) { Text("确认授权") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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

fun readStatus(context: Context): String {
    return try {
        val uri = Uri.parse("content://com.power.manager.status/status")
        val c = context.contentResolver.query(uri, null, null, null, null) ?: return ""
        try {
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex("json")
                if (idx >= 0) c.getString(idx) else ""
            } else {
                ""
            }
        } finally {
            c.close()
        }
    } catch (e: Throwable) {
        ""
    }
}

fun readCaps(): String? {
    return try {
        val f = File(HardwareCap.capFile())
        if (f.exists()) f.readText() else null
    } catch (e: Throwable) {
        null
    }
}

data class StatusInfo(val text: String, val color: Color)

fun parseStatus(json: String): StatusInfo? {
    if (json.isBlank()) return null
    return try {
        val o = JSONObject(json)
        val fuse = o.optBoolean("fuseTripped", false)
        val root = o.optBoolean("rootAvailable", false)
        val rootCount = o.optInt("rootCount", 0)
        val emergency = o.optBoolean("emergencyFallback", false)
        val bt = o.optBoolean("btDisabledByModule", false)
        val cpu = o.optInt("cpuFreqApplied", -1)
        val sb = StringBuilder()
        val color: Color
        when {
            fuse -> {
                color = Color(0xFFF44336)
                sb.append("物理熔断：模块已停用")
            }
            !root -> {
                color = Color(0xFFFF9800)
                sb.append("警告：Root 缺失，CPU 限制已禁用")
            }
            rootCount > 0 -> {
                color = Color(0xFFFFEB3B)
                sb.append("运行模式：混合（部分功能使用 Root）")
            }
            else -> {
                color = Color(0xFF4CAF50)
                sb.append("运行模式：系统 API（流畅）")
            }
        }
        if (emergency) sb.append(" / 异常关机回退中")
        if (bt) sb.append(" / 蓝牙已由模块关闭")
        if (cpu > 0) sb.append(" / CPU ${cpu / 1000}MHz")
        StatusInfo(sb.toString(), color)
    } catch (e: Throwable) {
        null
    }
}