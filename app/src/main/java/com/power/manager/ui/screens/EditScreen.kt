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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.power.manager.data.Template
import com.power.manager.ui.AppStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(template: Template, onBack: () -> Unit) {
    val context = LocalContext.current
    val editable = !template.isBuiltin
    var name by remember { mutableStateOf(template.name) }
    var killDelay by remember { mutableStateOf(if (template.killDelay >= 0) template.killDelay.toString() else "") }
    var fps by remember { mutableStateOf(if (template.targetFps > 0) template.targetFps.toString() else "") }
    var throttle by remember { mutableStateOf(template.cpuThrottle) }
    var brightness by remember { mutableStateOf(if (template.brightnessCap >= 0) template.brightnessCap.toString() else "") }
    var animOff by remember { mutableStateOf(template.animOff) }
    var gps by remember { mutableStateOf(template.gpsPolicy) }
    var net by remember { mutableStateOf(template.netPolicy) }
    var bt by remember { mutableStateOf(template.btPolicy) }
    var batterySaver by remember { mutableStateOf(template.batterySaver) }
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
                title = { Text(if (template.isBuiltin) "查看模板" else "编辑模板") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("模板名称") },
                enabled = editable,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            LabeledField("清理倒计时秒（0 即杀）", killDelay, { killDelay = it }, editable, listOf("0", "30", "120", "300"))
            LabeledField("锁定帧率 Hz（空不限）", fps, { fps = it }, editable, listOf("30", "60", "90", "120"))

            Text("CPU 节流", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(throttle == 0, { throttle = 0 }, "不限", editable)
                ChoiceChip(throttle == 1, { throttle = 1 }, "省电", editable)
                ChoiceChip(throttle == 2, { throttle = 2 }, "极限", editable)
            }
            Text(
                "省电为温和限频，极限为激进限频；执行由 system_server 侧策略完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            LabeledField("亮度上限 0-255（空不限）", brightness, { brightness = it }, editable, listOf("10", "30", "80", "255"))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("禁用动画", modifier = Modifier.weight(1f))
                Switch(checked = animOff, onCheckedChange = { animOff = it }, enabled = editable)
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("启用系统省电模式", modifier = Modifier.weight(1f))
                Switch(checked = batterySaver, onCheckedChange = { batterySaver = it }, enabled = editable)
            }

            Text("GPS 策略", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(gps == 0, { gps = 0 }, "禁用", editable)
                ChoiceChip(gps == 1, { gps = 1 }, "后台禁用", editable)
                ChoiceChip(gps == 2, { gps = 2 }, "放行", editable)
            }

            Text("后台网络", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(net == 0, { net = 0 }, "禁止", editable)
                ChoiceChip(net == 1, { net = 1 }, "放行", editable)
            }

            Text("蓝牙策略", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(bt == 0, { bt = 0 }, "关闭", editable)
                ChoiceChip(bt == 1, { bt = 1 }, "保持", editable)
            }

            if (editable) {
                Button(
                    onClick = {
                        val n = name.trim()
                        if (n.isBlank()) {
                            toast = "名称不能为空"
                            return@Button
                        }
                        val kd = killDelay.trim().toIntOrNull() ?: -1
                        var fp = fps.trim().toIntOrNull() ?: -1
                        if (fp == 0) fp = -1
                        var br = brightness.trim().toIntOrNull() ?: -1
                        if (br == 0) br = -1
                        val t = Template(
                            id = template.id,
                            name = n,
                            killDelay = kd,
                            targetFps = fp,
                            cpuThrottle = throttle,
                            brightnessCap = br,
                            animOff = animOff,
                            gpsPolicy = gps,
                            netPolicy = net,
                            btPolicy = bt,
                            batterySaver = batterySaver
                        )
                        val cfg = AppStore.load()
                        cfg.templates[t.id] = t
                        AppStore.save(cfg)
                        toast = "已保存"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    quick: List<String>
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (enabled && quick.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { q ->
                    OutlinedButton(onClick = { onValueChange(q) }) { Text(q) }
                }
            }
        }
    }
}

@Composable
fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: String, enabled: Boolean) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) }
    )
}
