package com.power.manager.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import com.power.manager.core.AppLog
import com.power.manager.core.Const

/**
 * 模块 App 暴露的配置/日志/后台指令通道：
 * - query /config：返回当前配置 JSON（system_server 与各应用进程读取）。
 * - insert /log：接收各进程推送的日志行，统一落盘到 AppLogStore。
 * - insert /bg（action=kill&pkg=）：system_server 下发处决命令 -> BackgroundManager。
 * - query /bg（action=check&pkg=）：应用进程查询是否被处决。
 * 通讯日志标注 Binder 调用方（来源进程），便于跨进程排查。
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
        return try {
            when (uri.path) {
                "/config" -> {
                    val cfg = AppStore.load().toJson()
                    AppLog.d("配置查询应答（来源=" + callerOf() + "）")
                    MatrixCursor(arrayOf("config")).apply { addRow(arrayOf(cfg)) }
                }
                "/bg" -> {
                    val action = uri.getQueryParameter("action")
                    val pkg = uri.getQueryParameter("pkg")
                    if (action == "check" && !pkg.isNullOrBlank()) {
                        val kill = BackgroundManager.onCheck(pkg, callerOf())
                        MatrixCursor(arrayOf("kill")).apply { addRow(arrayOf(if (kill) 1 else 0)) }
                    } else {
                        AppLog.w("后台指令格式错误（来源=" + callerOf() + "）：action=" + action + " pkg=" + pkg)
                        null
                    }
                }
                else -> null
            }
        } catch (e: Throwable) {
            AppLog.e(e, "query 异常（来源=" + callerOf() + "）：" + uri.path)
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return try {
            when (uri.path) {
                "/log" -> {
                    val level = values?.getAsString("level") ?: "I"
                    val process = values?.getAsString("process") ?: "?"
                    val msg = values?.getAsString("msg") ?: ""
                    AppLogStore.append(level, process, msg)
                    uri
                }
                "/bg" -> {
                    val action = values?.getAsString("action")
                    val pkg = values?.getAsString("pkg")
                    if (action == "kill" && !pkg.isNullOrBlank()) {
                        AppLog.i("收到处决命令（insert /bg，来源=" + callerOf() + "）：" + pkg)
                        BackgroundManager.onKillCommand(pkg, callerOf())
                        uri
                    } else {
                        AppLog.w("后台命令格式错误（来源=" + callerOf() + "）：action=" + action + " pkg=" + pkg)
                        null
                    }
                }
                else -> null
            }
        } catch (e: Throwable) {
            AppLog.e(e, "insert 异常（来源=" + callerOf() + "）：" + uri.path)
            null
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String? = null

    /** Binder 调用方 UID -> 包名（如 system_server 显示为 system/android）。 */
    private fun callerOf(): String {
        return try {
            val uid = Binder.getCallingUid()
            context?.packageManager?.getNameForUid(uid) ?: ("uid:" + uid)
        } catch (e: Throwable) {
            "unknown"
        }
    }
}
