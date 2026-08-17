package com.power.manager.hook

import com.power.manager.core.ConfigProvider
import com.power.manager.util.LogUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object BrightnessHook {
    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = Class.forName("com.android.server.power.PowerManagerService", false, lpparam.classLoader)
            val cb = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val cap = currentCap() ?: return
                        val v = param.args.getOrNull(0) as? Int ?: return
                        if (v > cap) param.args[0] = cap
                    } catch (e: Throwable) {
                        LogUtil.e(e, "亮度钳制回调异常")
                    }
                }
            }
            XposedBridge.hookAllMethods(cls, "setBrightness", cb)
            XposedBridge.hookAllMethods(cls, "setTemporaryBrightness", cb)
        } catch (e: Throwable) {
            LogUtil.e(e, "BrightnessHook 注册失败")
        }
    }

    private fun currentCap(): Int? {
        val fg = CurrentApp.foreground ?: return null
        val cfg = ConfigProvider.config() ?: return null
        if (!cfg.isRestricted(fg)) return null
        val tpl = cfg.templates[cfg.currentTemplateId] ?: return null
        val cap = tpl.brightnessCap
        return if (cap in 1..255) cap else null
    }
}