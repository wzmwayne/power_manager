package com.power.manager

import com.power.manager.core.AppLog
import com.power.manager.core.Const
import com.power.manager.hook.AppPolicyHook
import com.power.manager.hook.BluetoothHook
import com.power.manager.hook.SystemScheduler
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口（目标：LSPosed 框架，不依赖 root）。
 * - android（system_server）：仅保留最小系统层能力——蓝牙强制关闭与周期检查。
 * - 其他所有进程（用户应用与系统应用）：注入 AppPolicyHook 应用策略执行器，
 *   在应用进程内迫使应用符合当前模板（亮度/帧率/动画/GPS/蓝牙/后台冻结与自杀）。
 * - 模块自身进程跳过。
 */
class PowerManagerModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pkg = lpparam.packageName
            if (pkg == Const.PACKAGE) {
                AppLog.d("跳过模块自身进程：" + pkg)
                return
            }
            if (pkg == "android") {
                AppLog.i("Power Manager 注入 system_server（系统层蓝牙锁定）")
                BluetoothHook.hook()
                SystemScheduler.start()
                return
            }
            AppLog.i("Power Manager 注入应用进程：" + pkg)
            AppPolicyHook.hook(lpparam)
        } catch (e: Throwable) {
            AppLog.e(e, "handleLoadPackage 异常，已防止错误扩散")
        }
    }
}
