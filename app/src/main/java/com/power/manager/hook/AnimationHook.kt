package com.power.manager.hook

import com.power.manager.core.ConfigProvider
import com.power.manager.util.LogUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object AnimationHook {
    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = Class.forName("com.android.server.wm.WindowManagerService", false, lpparam.classLoader)
            XposedBridge.hookAllMethods(
                cls,
                "setAnimationScales",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val cfg = ConfigProvider.config() ?: return
                            val tpl = cfg.templates[cfg.currentTemplateId] ?: return
                            if (!tpl.animOff) return
                            val scales = param.args.getOrNull(0) as? FloatArray ?: return
                            for (i in scales.indices) scales[i] = 0f
                        } catch (e: Throwable) {
                            LogUtil.e(e, "动画钳制回调异常")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            LogUtil.e(e, "AnimationHook 注册失败")
        }
    }
}