package com.power.manager.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.power.manager.core.AppLog
import com.power.manager.core.Const

/**
 * 模块 App 暴露的配置/日志通道：
 * - query /config：返回当前配置 JSON（system_server 与各应用进程读取）。
 * - insert /log：接收各进程推送的日志行，统一落盘到 AppLogStore。
 */
class AppConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let {
            AppStore.init(it)
            AppLogStore.init(it)
        }
        AppLog.setProcess("provider")
        AppLog.v("AppConfigProvider 启动")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.path != "/config") return null
        return try {
            val cfg = AppStore.load().toJson()
            MatrixCursor(arrayOf("config")).apply { addRow(arrayOf(cfg)) }
        } catch (e: Throwable) {
            AppLog.e(e, "配置查询异常")
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uri.path != "/log") return null
        val level = values?.getAsString("level") ?: "I"
        val process = values?.getAsString("process") ?: "?"
        val msg = values?.getAsString("msg") ?: ""
        AppLogStore.append(level, process, msg)
        return uri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String? = null
}
