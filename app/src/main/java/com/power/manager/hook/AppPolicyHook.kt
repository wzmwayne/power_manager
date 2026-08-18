package com.power.manager.hook

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.database.ContentObserver
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.WindowManager
import com.power.manager.core.AppLog
import com.power.manager.core.ConfigChannel
import com.power.manager.core.Const
import com.power.manager.core.Protection
import com.power.manager.core.SysContext
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_LoadPackage
import de.robv.android.xposed.XposedBridge

/**
 * 应用进程内策略执行器（注入每个用户应用，目标 LSPosed 框架，不依赖 root）：
 * - 后台跟踪：hook Activity.onResume/onPause 维护前台计数，确认整应用进入后台（日志）。
 * - 自杀处决：后台期间注册 /bg 指令观察者；system_server（BackgroundKeeper）超时/超限
 *   经模块 App 广播处决通知，本进程收到后查询确认仍被标记则自杀（Process.killProcess）。
 * - 后台冻结：cpuThrottle 启用时拒绝 WakeLock.acquire（后台不持锁）。
 * - 亮度钳制：brightnessCap 启用时钳制窗口亮度（WindowManager.LayoutParams.screenBrightness）。
 * - 帧率锁：targetFps（或 cpuThrottle>=2 默认 30）时拉大 Choreographer 帧间隔。
 * - 动画禁用：animOff（或 cpuThrottle>0）时 ValueAnimator/Animation 时长置 0。
 * - GPS 限制：gpsPolicy=0 全拦 / =1 后台拦；请求返回 null/不注册。
 * - 蓝牙锁定：btPolicy=0 时本进程内拦截 BluetoothAdapter.enable/setBluetoothEnabled(true)。
 * 受保护进程（Protection.isProtected）只做蓝牙拦截，跳过破坏性策略。
 * 日志均标注来源应用（AppLog.setProcess 包名）。
 */
object AppPolicyHook {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var packageName: String = "?"

    @Volatile
    private var foregroundActivities = 0

    @Volatile
    private var backgroundConfirmed = false

    @Volatile
    private var bgObserver: ContentObserver? = null

    private val backgroundCheck = Runnable {
        if (foregroundActivities <= 0 && !backgroundConfirmed) {
            backgroundConfirmed = true
            onBackgroundConfirmed()
        }
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        packageName = lpparam.packageName
        AppLog.setProcess(packageName)
        AppLog.i("应用启动，AppPolicyHook 注入（受保护=" + Protection.isProtected(packageName) + "）")
        hookBluetoothEnable()
        if (Protection.isProtected(packageName)) {
            AppLog.i("受保护进程，仅保留蓝牙拦截，跳过破坏性策略：" + packageName)
            return
        }
        hookActivityLifecycle()
        hookBrightness()
        hookFrameRate()
        hookAnimations()
        hookLocation()
        hookWakeLock()
        AppLog.i("AppPolicyHook 注册完成：" + packageName)
    }

    // ---------- 后台跟踪 ----------

    private fun hookActivityLifecycle() {
        try {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        onActivityResumed()
                    } catch (e: Throwable) {
                        AppLog.e(e, "onResume 回调异常")
                    }
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "onPause", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        onActivityPaused()
                    } catch (e: Throwable) {
                        AppLog.e(e, "onPause 回调异常")
                    }
                }
            })
            AppLog.i("Activity 生命周期 Hook 已注册（前后台跟踪）")
        } catch (e: Throwable) {
            AppLog.e(e, "Activity 生命周期 Hook 注册失败")
        }
    }

    private fun onActivityResumed() {
        foregroundActivities++
        mainHandler.removeCallbacks(backgroundCheck)
        if (backgroundConfirmed) {
            AppLog.i("应用返回前台，取消后台状态：" + packageName)
            backgroundConfirmed = false
            unregisterBgObserver()
        }
    }

    private fun onActivityPaused() {
        foregroundActivities--
        if (foregroundActivities < 0) foregroundActivities = 0
        mainHandler.removeCallbacks(backgroundCheck)
        mainHandler.postDelayed(backgroundCheck, 500)
    }

    private fun onBackgroundConfirmed() {
        AppLog.i("应用进入后台（确认）：" + packageName + "，注册处决指令观察者")
        registerBgObserver()
    }

    // ---------- 处决指令接收（自杀） ----------

    private fun registerBgObserver() {
        try {
            if (bgObserver != null) return
            val resolver = SysContext.contentResolver() ?: return
            val obs = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    try {
                        onBgCommandNotify()
                    } catch (e: Throwable) {
                        AppLog.e(e, "处决指令观察者回调异常")
                    }
                }
            }
            bgObserver = obs
            resolver.registerContentObserver(Uri.parse(Const.BG_URI), true, obs)
            AppLog.i("已注册处决指令观察者（/bg）：" + packageName)
        } catch (e: Throwable) {
            AppLog.w("处决指令观察者注册失败：" + packageName + " " + e.message)
        }
    }

    private fun unregisterBgObserver() {
        try {
            bgObserver?.let {
                SysContext.contentResolver()?.unregisterContentObserver(it)
                AppLog.d("注销处决指令观察者：" + packageName)
            }
            bgObserver = null
        } catch (e: Throwable) {
            AppLog.w("处决指令观察者注销失败：" + e.message)
        }
    }

    /** 收到广播（/bg notifyChange）：自查仍在后台，再向模块 App 查询处决状态。 */
    private fun onBgCommandNotify() {
        AppLog.i("收到处决广播（/bg onChange）：" + packageName + "，后台状态=" + backgroundConfirmed)
        if (!backgroundConfirmed) {
            AppLog.i("已回前台，忽略处决广播：" + packageName)
            return
        }
        val kill = queryKillStatus()
        AppLog.i("处决查询结果（" + packageName + "）：kill=" + kill)
        if (kill) {
            AppLog.w("收到处决指令，执行自杀：" + packageName)
            suicide()
        }
    }

    /** 向模块 App 查询：本应用是否被标记处决。 */
    private fun queryKillStatus(): Boolean {
        return try {
            val resolver = SysContext.contentResolver() ?: return false
            val uri = Uri.parse(Const.BG_URI + "?action=check&pkg=" + Uri.encode(packageName))
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getInt(0) == 1
                } else {
                    AppLog.w("处决查询无应答（" + packageName + "）")
                    false
                }
            } ?: false
        } catch (e: Throwable) {
            AppLog.w("处决查询异常（" + packageName + "）：" + e.message)
            false
        }
    }

    private fun suicide() {
        unregisterBgObserver()
        try {
            AppLog.w("执行应用自杀（相当于删除后台）：" + packageName + " pid=" + Process.myPid())
            Process.killProcess(Process.myPid())
        } catch (e: Throwable) {
            AppLog.e(e, "自杀失败（killProcess）")
        }
        try {
            System.exit(0)
        } catch (e: Throwable) {
        }
    }

    // ---------- 后台冻结（拒绝 WakeLock） ----------

    private fun hookWakeLock() {
        try {
            val wl = Class.forName("android.os.PowerManager\$WakeLock")
            XposedBridge.hookAllMethods(wl, "acquire", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (backgroundConfirmed && freezeEnabled()) {
                            AppLog.i("后台冻结：拒绝 WakeLock.acquire（" + packageName + "）")
                            param.result = null
                        }
                    } catch (e: Throwable) {
                    }
                }
            })
            XposedBridge.hookAllMethods(wl, "release", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                }
            })
            AppLog.i("WakeLock 冻结 Hook 已注册")
        } catch (e: Throwable) {
            AppLog.e(e, "WakeLock Hook 注册失败")
        }
    }

    // ---------- 亮度钳制 ----------

    private fun hookBrightness() {
        try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            XposedBridge.hookAllMethods(wmg, "addView", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        clampWindowBrightness(param.args?.getOrNull(1))
                    } catch (e: Throwable) {
                    }
                }
            })
            XposedBridge.hookAllMethods(wmg, "updateViewLayout", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        clampWindowBrightness(param.args?.getOrNull(1))
                    } catch (e: Throwable) {
                    }
                }
            })
            AppLog.i("窗口亮度钳制 Hook 已注册")
        } catch (e: Throwable) {
            AppLog.e(e, "窗口亮度 Hook 注册失败")
        }
    }

    private fun clampWindowBrightness(params: Any?) {
        if (params !is WindowManager.LayoutParams) return
        val cap = brightnessCap()
        if (cap <= 0 || cap > 255) return
        val target = cap / 255f
        val cur = params.screenBrightness
        if (cur < 0f || cur > target) {
            params.screenBrightness = target
            AppLog.i("亮度被调整并钳制（" + packageName + "）：" + cur + " -> " + target + " (cap=" + cap + ")")
        }
    }

    // ---------- 帧率锁 ----------

    private fun hookFrameRate() {
        try {
            val cho = Class.forName("android.view.Choreographer")
            XposedBridge.hookAllMethods(cho, "getFrameIntervalNanos", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val fps = targetFps()
                        if (fps > 0) {
                            val interval = 1_000_000_000L / fps
                            val cur = param.result as? Long
                            if (cur == null || cur < interval) {
                                param.result = interval
                            }
                        }
                    } catch (e: Throwable) {
                    }
                }
            })
            AppLog.i("帧率锁 Hook 已注册")
        } catch (e: Throwable) {
            AppLog.e(e, "帧率锁 Hook 注册失败")
        }
    }

    // ---------- 动画禁用 ----------

    private fun hookAnimations() {
        try {
            XposedBridge.hookAllMethods(Class.forName("android.animation.ValueAnimator"), "getDuration", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (animOff()) {
                            param.result = 0L
                        }
                    } catch (e: Throwable) {
                    }
                }
            })
            XposedBridge.hookAllMethods(Class.forName("android.view.animation.Animation"), "getDuration", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (animOff()) {
                            param.result = 0L
                        }
                    } catch (e: Throwable) {
                    }
                }
            })
            AppLog.i("动画禁用 Hook 已注册")
        } catch (e: Throwable) {
            AppLog.e(e, "动画 Hook 注册失败")
        }
    }

    // ---------- GPS 限制 ----------

    private fun hookLocation() {
        try {
            XposedBridge.hookAllMethods(LocationManager::class.java, "getLastKnownLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (locationBlocked()) {
                            AppLog.i("GPS 受限：拦截 getLastKnownLocation（" + packageName + "）")
                            param.result = null
                        }
                    } catch (e: Throwable) {
                    }
                }
            })
            XposedBridge.hookAllMethods(LocationManager::class.java, "requestLocationUpdates", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (locationBlocked()) {
                            AppLog.i("GPS 受限：拦截 requestLocationUpdates（" + packageName + "）")
                            param.result = null
                        }
                    } catch (e: Throwable) {
                    }
                }
            })
            AppLog.i("GPS 策略 Hook 已注册")
        } catch (e: Throwable) {
            AppLog.e(e, "GPS Hook 注册失败")
        }
    }

    // ---------- 蓝牙开启拦截（应用进程） ----------

    private fun hookBluetoothEnable() {
        try {
            XposedBridge.hookAllMethods(BluetoothAdapter::class.java, "enable", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (btLocked()) {
                            AppLog.i("蓝牙开启请求被拦截（应用进程 " + packageName + "）")
                            param.result = false
                        }
                    } catch (e: Throwable) {
                    }
                }
            })
            XposedBridge.hookAllMethods(BluetoothAdapter::class.java, "setBluetoothEnabled", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val enabled = param.args?.firstOrNull() as? Boolean ?: return
                        if (btLocked() && enabled) {
                            AppLog.i("蓝牙状态切换被拦截（应用进程 " + packageName + "）：请求开启 -> 阻止")
                            param.result = false
                        }
                    } catch (e: Throwable) {
                    }
                }
            })
            AppLog.i("蓝牙开启拦截 Hook 已注册（应用进程）")
        } catch (e: Throwable) {
            AppLog.e(e, "蓝牙应用进程 Hook 注册失败")
        }
    }

    // ---------- 模板读取 ----------

    private fun freezeEnabled(): Boolean {
        return try {
            (ConfigChannel.activeTemplate()?.cpuThrottle ?: 0) > 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun brightnessCap(): Int {
        return try {
            ConfigChannel.activeTemplate()?.brightnessCap ?: -1
        } catch (e: Throwable) {
            -1
        }
    }

    private fun targetFps(): Int {
        return try {
            val tpl = ConfigChannel.activeTemplate() ?: return -1
            if (tpl.targetFps > 0) tpl.targetFps
            else if (tpl.cpuThrottle >= 2) 30
            else -1
        } catch (e: Throwable) {
            -1
        }
    }

    private fun animOff(): Boolean {
        return try {
            val t = ConfigChannel.activeTemplate() ?: return false
            t.animOff || t.cpuThrottle > 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun locationBlocked(): Boolean {
        return try {
            when (ConfigChannel.activeTemplate()?.gpsPolicy ?: 2) {
                0 -> true
                1 -> backgroundConfirmed
                else -> false
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun btLocked(): Boolean {
        return try {
            (ConfigChannel.activeTemplate()?.btPolicy ?: 1) == 0
        } catch (e: Throwable) {
            false
        }
    }
}
