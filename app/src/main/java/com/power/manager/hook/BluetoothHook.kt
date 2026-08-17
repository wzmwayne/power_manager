package com.power.manager.hook

import android.bluetooth.BluetoothAdapter
import com.power.manager.core.AppLog
import com.power.manager.core.ConfigChannel
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 系统层蓝牙禁用与锁定（仅注入 system_server，目标 LSPosed 框架下保留的最小系统层能力）：
 * 1. 主动关闭蓝牙连接：模板 btPolicy=0 时，周期任务发现蓝牙开启即强制 disable。
 * 2. 锁定禁用：拦截 BluetoothManagerService 的 enable/enableNoAutoConnect/setBluetoothEnabled(true)，
 *    任何应用或用户都无法重新开启蓝牙；btPolicy=1 时停止拦截与强关（还原用户自由）。
 * 应用进程内的开启拦截见 AppPolicyHook（hook 各应用 BluetoothAdapter.enable/setBluetoothEnabled）。
 */
object BluetoothHook {

    @Volatile
    private var service: Any? = null

    @Volatile
    private var lastPolicy: Int = -1

    fun hook() {
        val candidates = listOf(
            "com.android.server.bluetooth.BluetoothManagerService",
            "com.android.server.BluetoothManagerService"
        )
        for (cn in candidates) {
            try {
                val clazz = Class.forName(cn)
                hookEnable(clazz)
                hookEnableNoAutoConnect(clazz)
                hookSetBluetoothEnabled(clazz)
                hookDisable(clazz)
                AppLog.i("BluetoothHook 已注册：" + cn)
            } catch (e: ClassNotFoundException) {
                AppLog.d("BluetoothHook 跳过（类不存在）：" + cn)
            } catch (e: Throwable) {
                AppLog.w("BluetoothHook 注册失败：" + cn + " " + e.message)
            }
        }
    }

    /** 拦截所有 enable 重载：锁定中一律返回 false。 */
    private fun hookEnable(clazz: Class<*>) {
        XposedBridge.hookAllMethods(clazz, "enable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    service = param.thisObject
                    val policy = btPolicy()
                    if (policy == 0) {
                        AppLog.i("蓝牙开启请求被拦截（btPolicy=0 锁定禁用）：阻止开启")
                        param.result = false
                    } else {
                        AppLog.i("蓝牙开启请求放行（btPolicy=" + policy + "）")
                    }
                } catch (e: Throwable) {
                    AppLog.e(e, "enable 拦截异常")
                }
            }
        })
    }

    /** 拦截系统自动重连入口。 */
    private fun hookEnableNoAutoConnect(clazz: Class<*>) {
        XposedBridge.hookAllMethods(clazz, "enableNoAutoConnect", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    service = param.thisObject
                    val policy = btPolicy()
                    if (policy == 0) {
                        AppLog.i("蓝牙自动连接请求被拦截（btPolicy=0 锁定禁用）：阻止开启")
                        param.result = false
                    } else {
                        AppLog.i("蓝牙自动连接请求放行（btPolicy=" + policy + "）")
                    }
                } catch (e: Throwable) {
                    AppLog.e(e, "enableNoAutoConnect 拦截异常")
                }
            }
        })
    }

    /** 拦截 setBluetoothEnabled(boolean)：锁定中禁止置为开启。 */
    private fun hookSetBluetoothEnabled(clazz: Class<*>) {
        XposedBridge.hookAllMethods(clazz, "setBluetoothEnabled", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    service = param.thisObject
                    val enabled = param.args?.firstOrNull() as? Boolean ?: return
                    val policy = btPolicy()
                    if (policy == 0 && enabled) {
                        AppLog.i("蓝牙状态切换请求被拦截（btPolicy=0 锁定禁用）：请求开启 -> 阻止")
                        param.result = false
                    } else if (policy == 0) {
                        AppLog.i("蓝牙状态切换请求放行：请求关闭（锁定保持）")
                    } else {
                        AppLog.i("蓝牙状态切换请求放行（btPolicy=" + policy + "）：enabled=" + enabled)
                    }
                } catch (e: Throwable) {
                    AppLog.e(e, "setBluetoothEnabled 拦截异常")
                }
            }
        })
    }

    /** disable 一律放行（用于主动关闭蓝牙）。 */
    private fun hookDisable(clazz: Class<*>) {
        XposedBridge.hookAllMethods(clazz, "disable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                service = param.thisObject
                AppLog.i("蓝牙关闭请求到达：放行")
            }
        })
    }

    /** 周期检查（SystemScheduler 每 30s 调用）：刷新配置，锁定中强制关闭蓝牙。 */
    fun periodicCheck() {
        try {
            ConfigChannel.invalidate()
            val policy = btPolicy()
            if (policy != lastPolicy) {
                AppLog.i("蓝牙策略变更：" + lastPolicy + " -> " + policy)
                lastPolicy = policy
            }
            if (policy == 0) {
                val on = isBluetoothOn()
                AppLog.i("蓝牙锁定周期检查：btPolicy=0，蓝牙当前" + if (on) "开启" else "关闭")
                if (on) {
                    AppLog.i("蓝牙仍处于开启状态，强制执行关闭")
                    forceDisable()
                }
            } else {
                AppLog.d("蓝牙周期检查：btPolicy=" + policy + "（未锁定，不干预）")
            }
        } catch (e: Throwable) {
            AppLog.e(e, "蓝牙周期检查异常")
        }
    }

    /** 当前激活模板的蓝牙策略：0=关闭锁定，1=保持放行。 */
    private fun btPolicy(): Int {
        return try {
            ConfigChannel.activeTemplate()?.btPolicy ?: 1
        } catch (e: Throwable) {
            1
        }
    }

    private fun isBluetoothOn(): Boolean {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled ?: false
        } catch (e: Throwable) {
            AppLog.w("蓝牙状态检测失败：" + e.message)
            false
        }
    }

    private fun forceDisable() {
        service?.let {
            try {
                val r = XposedHelpers.callMethod(it, "disable")
                AppLog.i("BluetoothManagerService.disable() 调用成功：" + r)
                return
            } catch (e: Throwable) {
                AppLog.w("蓝牙服务实例 disable 调用失败：" + e.message)
            }
        }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val r = adapter?.disable()
            AppLog.i("BluetoothAdapter.disable() 调用完成：" + r)
        } catch (e: Throwable) {
            AppLog.e(e, "BluetoothAdapter.disable() 调用失败")
        }
    }
}
