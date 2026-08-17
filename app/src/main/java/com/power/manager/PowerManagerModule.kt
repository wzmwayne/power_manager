package com.power.manager

import com.power.manager.core.ConfigProvider
import com.power.manager.core.EmergencyGuard
import com.power.manager.core.ModuleScheduler
import com.power.manager.core.PhysicalFuse
import com.power.manager.core.RootExecutor
import com.power.manager.core.ScopeGuard
import com.power.manager.hook.AnimationHook
import com.power.manager.hook.BackgroundKillHook
import com.power.manager.hook.BrightnessHook
import com.power.manager.hook.GpsHook
import com.power.manager.hook.ShutdownHook
import com.power.manager.util.LogUtil
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class PowerManagerModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 物理熔断：注入前轮询，任一命中即静默退出并强制恢复 CPU
            if (PhysicalFuse.isTripped()) {
                LogUtil.w("物理熔断命中或未授权，模块静默退出（不注入）")
                RootExecutor.restoreCpu()
                return
            }

            // 作用域白名单防护：仅允许注入声明的系统作用域，绝不 Hook 其他应用
            if (!ScopeGuard.isAllowed(lpparam.packageName)) {
                LogUtil.d("跳过非作用域进程：${lpparam.packageName}")
                return
            }

            // 仅对 system_server 注册 Hook（其余作用域包暂不注册，仅为兼容保留）
            if (lpparam.packageName != "android") return
            LogUtil.i("Power Manager 注入 system_server")

            ConfigProvider.load()
            EmergencyGuard.checkAndReset()

            BackgroundKillHook.hook(lpparam)
            BrightnessHook.hook(lpparam)
            AnimationHook.hook(lpparam)
            GpsHook.hook(lpparam)
            ShutdownHook.hook(lpparam)

            ModuleScheduler.start()
        } catch (e: Throwable) {
            LogUtil.e(e, "handleLoadPackage 异常，已防止错误扩散")
        }
    }
}