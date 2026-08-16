package com.power.manager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import com.power.manager.core.ModuleFiles
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    fun reload() {
        text = readLogFile()
    }
    LaunchedEffect(Unit) {
        while (true) {
            reload()
            delay(3000)
        }
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        TopAppBar(
            title = { Text("模块日志") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            actions = {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Power Manager 日志", text))
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }) { Text("复制日志") }
                TextButton(onClick = { reload() }) { Text("刷新") }
            }
        )
        Text(
            "日志文件：${ModuleFiles.logFile()}（可用 adb logcat -s PowerManager 查看，或 adb shell cat 该路径）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

fun readLogFile(): String {
    return try {
        val f = File(ModuleFiles.logFile())
        if (f.exists()) f.readText() else "（暂无日志，重启或触发策略后生成）"
    } catch (e: Throwable) {
        "（读取日志失败：${e.message}）"
    }
}