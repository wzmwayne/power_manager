package com.power.manager.hook

import com.power.manager.core.EmergencyGuard
import com.power.manager.util.LogUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object ShutdownHook {
    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = Class.forName("com.android.server.power.PowerManagerService", false, lpparam.classLoader)
            val cb = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        EmergencyGuard.markGraceful()
                        LogUtil.i("检测到系统关机，标记优雅退出")
                    } catch (e: Throwable) {
                    }
                }
            }
            XposedHelpers.hookAllMethods(cls, "shutdown", cb)
            XposedHelpers.hookAllMethods(cls, "shutdownOrRebootInternal", cb)
        } catch (e: Throwable) {
            LogUtil.e(e, "ShutdownHook 注册失败")
        }
    }
}