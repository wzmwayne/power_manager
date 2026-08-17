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
import com.power.manager.core.HardwareProbe
import com.power.manager.data.CpuUtil
import com.power.manager.data.Template
import com.power.manager.ui.AppStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(template: Template, onBack: () -> Unit) {
    val context = LocalContext.current
    val caps = remember { HardwareProbe.load() }
    val cpuOk = caps?.cpuFreqSupported ?: true
    val fpsOk = caps?.fpsSupported ?: true
    val editable = !template.isBuiltin
    var name by remember { mutableStateOf(template.name) }
    var maxBg by remember { mutableStateOf(if (template.maxBg >= 0) template.maxBg.toString() else "") }
    var killDelay by remember { mutableStateOf(if (template.killDelay >= 0) template.killDelay.toString() else "") }
    var fps by remember { mutableStateOf(if (template.targetFps > 0) template.targetFps.toString() else "") }
    var cpu by remember { mutableStateOf(if (template.cpuFreq >= 0) template.cpuFreq.toString() else "") }
    var brightness by remember { mutableStateOf(if (template.brightnessCap >= 0) template.brightnessCap.toString() else "") }
    var animOff by remember { mutableStateOf(template.animOff) }
    var gps by remember { mutableStateOf(template.gpsPolicy) }
    var net by remember { mutableStateOf(template.netPolicy) }
    var bt by remember { mutableStateOf(template.btPolicy) }
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
            LabeledField("后台进程上限（-1 不限）", maxBg, { maxBg = it }, editable, listOf("1", "3", "5", "10"))
            LabeledField("清理倒计时秒（0 即杀）", killDelay, { killDelay = it }, editable, listOf("0", "30", "120", "300"))
            LabeledField("锁定帧率 Hz（空不限）", fps, { fps = it }, editable && fpsOk, listOf("30", "60", "90", "120"))
            if (!fpsOk) {
                Text("设备不支持帧率锁，已自动禁用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            LabeledField("CPU 频率（小数或 KHz，空不限）", cpu, { cpu = it }, editable && cpuOk, listOf("0.4", "0.7", "0.9", "1.0"))
            if (!cpuOk) {
                Text("设备不支持 CPU 调频，已自动禁用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            LabeledField("亮度上限 0-255（空不限）", brightness, { brightness = it }, editable, listOf("10", "30", "80", "255"))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("禁用动画", modifier = Modifier.weight(1f))
                Switch(checked = animOff, onCheckedChange = { animOff = it }, enabled = editable)
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
                        val cpuTxt = cpu.trim()
                        val cf: Int = if (cpuTxt.isEmpty()) {
                            -1
                        } else {
                            val d = cpuTxt.toDoubleOrNull()
                            if (d == null) {
                                toast = "CPU 频率格式无效，请输入小数（如 0.7）或 KHz 数值"
                                return@Button
                            }
                            CpuUtil.convertToKHz(d)
                        }
                        if (cpuTxt.isNotEmpty() && cf == -1 && (cpuTxt.toDoubleOrNull() ?: 0.0) > 0) {
                            toast = "CPU 频率低于 20% 安全线，已自动转为不限"
                        } else {
                            toast = null
                        }
                        val mb = maxBg.trim().toIntOrNull() ?: -1
                        val kd = killDelay.trim().toIntOrNull() ?: -1
                        var fp = fps.trim().toIntOrNull() ?: -1
                        if (fp == 0) fp = -1
                        var br = brightness.trim().toIntOrNull() ?: -1
                        if (br == 0) br = -1
                        val t = Template(template.id, n, mb, kd, fp, cf, br, animOff, gps, net, bt)
                        val cfg = AppStore.load()
                        cfg.templates[t.id] = t
                        AppStore.save(cfg)
                        toast = toast ?: "已保存"
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