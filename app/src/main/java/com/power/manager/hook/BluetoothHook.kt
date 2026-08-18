package com.power.manager.hook

import android.bluetooth.BluetoothAdapter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.power.manager.core.AppLog
import com.power.manager.core.ConfigChannel
import com.power.manager.core.Const
import com.power.manager.core.SysContext
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 系统层蓝牙禁用与锁定（仅注入 system_server，目标 LSPosed 框架下保留的最小系统层能力）：
 * - 触发式：监听 /config 变化（模板切换）。btPolicy=0 时立即关闭蓝牙，3 秒后复查一次；
 *   此后不再主动动作（无轮询、无反复尝试关闭），依靠 enable/setBluetoothEnabled 拦截永久锁定，
 *   直到切换到不禁用蓝牙的模板（btPolicy != 0 时拦截放行）。
 * - 应用进程内的开启拦截见 AppPolicyHook（hook 各应用 BluetoothAdapter.enable/setBluetoothEnabled）。
 */
object BluetoothHook {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var service: Any? = null

    @Volatile
    private var configObserver: ContentObserver? = null

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
        registerConfigObserver()
    }

    /** 监听配置变化：模板切换触发蓝牙立即关闭（不再轮询）。 */
    private fun registerConfigObserver() {
        try {
            if (configObserver != null) return
            val resolver = SysContext.contentResolver() ?: return
            val obs = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    try {
                        onConfigChanged()
                    } catch (e: Throwable) {
                        AppLog.e(e, "配置变化蓝牙处理异常")
                    }
                }
            }
            configObserver = obs
            resolver.registerContentObserver(Uri.parse(Const.CONFIG_URI), true, obs)
            AppLog.i("蓝牙配置观察者已注册（模板切换触发关闭，无轮询）")
        } catch (e: Throwable) {
            AppLog.w("蓝牙配置观察者注册失败：" + e.message)
        }
    }

    /** 模板切换处理：btPolicy=0 立即关闭，3s 后复查一次，之后靠拦截永久锁定。 */
    private fun onConfigChanged() {
        try {
            ConfigChannel.invalidate()
            val policy = btPolicy()
            AppLog.i("配置变化触发蓝牙检查：btPolicy=" + policy)
            if (policy == 0) {
                AppLog.i("btPolicy=0，立即关闭蓝牙")
                forceDisable()
                mainHandler.postDelayed({
                    AppLog.i("蓝牙关闭 3s 复查：btPolicy=" + btPolicy())
                    if (btPolicy() == 0) {
                        if (isBluetoothOn()) {
                            AppLog.w("蓝牙 3s 复查仍开启，再次关闭（最后一次主动动作，之后靠拦截锁定）")
                            forceDisable()
                        } else {
                            AppLog.i("蓝牙已确认关闭，进入永久锁定（拦截一切开启请求）")
                        }
                    }
                }, 3000)
            } else {
                AppLog.i("btPolicy=" + policy + "，蓝牙解锁（不再拦截开启）")
            }
        } catch (e: Throwable) {
            AppLog.e(e, "配置变化蓝牙处理异常")
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
