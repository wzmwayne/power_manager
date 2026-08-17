package com.power.manager.core

import android.content.ContentResolver
import android.content.Context
import de.robv.android.xposed.XposedHelpers

/** 在任意进程（system_server / 应用进程 / 模块自身）中获取可用的 Context 与 ContentResolver。 */
object SysContext {
    @Volatile
    private var cached: Context? = null

    fun context(): Context? {
        cached?.let { return it }
        return try {
            val at = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"),
                "currentActivityThread"
            ) ?: return null
            val app = XposedHelpers.callMethod(at, "getSystemContext") as? Context
            if (app != null) cached = app
            app
        } catch (e: Throwable) {
            null
        }
    }

    fun contentResolver(): ContentResolver? = context()?.contentResolver
}
