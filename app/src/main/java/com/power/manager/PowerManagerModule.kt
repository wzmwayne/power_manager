package com.power.manager

import com.power.manager.core.AppLog
import com.power.manager.core.Const
import com.power.manager.hook.AppPolicyHook
import com.power.manager.hook.BackgroundKeeper
import com.power.manager.hook.BluetoothHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口（目标：LSPosed 框架，不依赖 root）。
 * - android（system_server）：系统层承载——蓝牙触发式锁定（BluetoothHook，无轮询）
 *   + 后台清理全局协调（BackgroundKeeper：前台切换检测/队列/超限处决/超时计时）。
 * - 其他所有进程（用户应用与系统应用）：注入 AppPolicyHook 应用策略执行器，
 *   在应用进程内迫使应用符合当前模板（亮度/帧率/动画/GPS/蓝牙/后台冻结），
 *   并接收处决指令自杀（经 /bg 观察者）。
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
                AppLog.setProcess("system_server")
                AppLog.i("Power Manager 注入 system_server（系统层承载）")
                BluetoothHook.hook()
                BackgroundKeeper.hook()
                return
            }
            AppLog.i("Power Manager 注入应用进程：" + pkg)
            AppPolicyHook.hook(lpparam)
        } catch (e: Throwable) {
            AppLog.e(e, "handleLoadPackage 异常，已防止错误扩散")
        }
    }
}
