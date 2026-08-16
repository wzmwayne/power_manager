package com.power.manager.util

import android.util.Log
import com.power.manager.core.ModuleFiles
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogUtil {
    private const val MAX_BYTES = 256 * 1024
    private const val TAG = "PowerManager"
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun append(level: String, msg: String) {
        try {
            val f = File(ModuleFiles.logFile())
            f.parentFile?.mkdirs()
            if (f.exists() && f.length() > MAX_BYTES) {
                val lines = f.readLines()
                f.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
            }
            f.appendText("${fmt.format(Date())} [$level] $msg\n")
        } catch (e: Throwable) {
        }
    }

    fun d(msg: String) {
        XposedBridge.log("[PowerManager/D] $msg")
        Log.d(TAG, msg)
        append("D", msg)
    }

    fun i(msg: String) {
        XposedBridge.log("[PowerManager/I] $msg")
        Log.i(TAG, msg)
        append("I", msg)
    }

    fun w(msg: String) {
        XposedBridge.log("[PowerManager/W] $msg")
        Log.w(TAG, msg)
        append("W", msg)
    }

    fun e(t: Throwable, msg: String) {
        XposedBridge.log("[PowerManager/E] $msg")
        XposedBridge.log(t)
        Log.e(TAG, "$msg ${t.message}")
        append("E", "$msg ${t.message}")
    }
}