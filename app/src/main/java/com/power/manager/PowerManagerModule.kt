package com.power.manager

import com.power.manager.core.AppLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口：仅对声明的作用域包执行注入检查。
 * 当前重构目标态：配置/日志通道（ContentProvider）由 App 侧承载，system_server
 * 侧暂不注册 Hook（Hook 面精简重构中），入口仅保留作用域白名单与日志。
 */
class PowerManagerModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (!ALLOWED_SCOPES.contains(lpparam.packageName)) {
                AppLog.d("跳过非作用域进程：" + lpparam.packageName)
                return
            }
            if (lpparam.packageName != "android") {
                AppLog.d("作用域包暂不注册 Hook：" + lpparam.packageName)
                return
            }
            AppLog.i("Power Manager 注入 system_server")
        } catch (e: Throwable) {
            AppLog.e(e, "handleLoadPackage 异常，已防止错误扩散")
        }
    }

    companion object {
        /** 与 AndroidManifest xposedscope 一致的作用域白名单。 */
        private val ALLOWED_SCOPES = setOf(
            "android",
            "com.android.providers.settings",
            "com.android.phone"
        )
    }
}
