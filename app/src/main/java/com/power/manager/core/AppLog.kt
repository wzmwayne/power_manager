package com.power.manager.core

import android.content.ContentValues
import android.net.Uri
import android.util.Log
import de.robv.android.xposed.XposedBridge

object AppLog {
    private const val TAG = "PowerManager"

    @Volatile
    private var processTag = "?"

    fun setProcess(tag: String) {
        processTag = tag
    }

    private fun write(level: Char, msg: String) {
        val line = "$processTag $msg"
        when (level) {
            'V' -> Log.v(TAG, line)
            'D' -> Log.d(TAG, line)
            'I' -> Log.i(TAG, line)
            'W' -> Log.w(TAG, line)
            else -> Log.e(TAG, line)
        }
        try {
            XposedBridge.log("[PowerManager/$level/$processTag] $msg")
        } catch (e: Throwable) {
        }
        push(level, msg)
    }

    fun v(msg: String) = write('V', msg)
    fun d(msg: String) = write('D', msg)
    fun i(msg: String) = write('I', msg)
    fun w(msg: String) = write('W', msg)
    fun e(t: Throwable, msg: String) {
        val line = "$processTag $msg ${t.message}"
        Log.e(TAG, line)
        try {
            XposedBridge.log("[PowerManager/E/$processTag] $msg")
            XposedBridge.log(t)
        } catch (e: Throwable) {
        }
        push('E', "$msg ${t.message}")
    }

    /** 经 ContentProvider 推送日志行到模块 App，由 App 侧统一落盘（AppLogStore）。 */
    private fun push(level: Char, msg: String) {
        try {
            val resolver = SysContext.contentResolver() ?: return
            val cv = ContentValues()
            cv.put("level", level.toString())
            cv.put("process", processTag)
            cv.put("msg", msg)
            resolver.insert(Uri.parse(Const.LOG_URI), cv)
        } catch (e: Throwable) {
        }
    }
}
