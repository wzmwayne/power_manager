package com.power.manager.ui

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * App 侧统一日志落盘：接收各进程经 ContentProvider（/log）推送的日志行，写入内部文件。
 * 单线程顺序写，按行数截断防膨胀；日志页读取展示并可复制。
 */
object AppLogStore {
    private const val FILE_NAME = "power_manager.log"
    private const val MAX_LINES = 2000

    private lateinit var file: File
    private val writer = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, FILE_NAME)
    }

    /** 追加一行日志（level/process/msg 来自 provider insert 的 ContentValues）。 */
    fun append(level: String, process: String, msg: String) {
        if (!::file.isInitialized) return
        writer.execute {
            try {
                val line = timeFormat.format(Date()) + " [$level/$process] " + msg
                val existing = if (file.exists()) file.readText() else ""
                val all = (existing.split("\n".toRegex()) + line).takeLast(MAX_LINES)
                file.writeText(all.joinToString("\n"))
            } catch (e: Throwable) {
            }
        }
    }

    fun read(): String {
        if (!::file.isInitialized) return "（日志存储未初始化）"
        return try {
            if (file.exists()) file.readText() else "（暂无日志，重启或触发策略后生成）"
        } catch (e: Throwable) {
            "（读取日志失败：" + e.message + "）"
        }
    }

    fun logFile(): String = if (::file.isInitialized) file.absolutePath else ""
}
