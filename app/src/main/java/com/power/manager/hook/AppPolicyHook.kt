package com.power.manager.hook

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.WindowManager
import com.power.manager.core.AppLog
import com.power.manager.core.ConfigChannel
import com.power.manager.core.Protection
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_LoadPackage
import de.robv.android.xposed.XposedBridge

/**
 * 应用进程内策略执行器（注入每个用户应用，不依赖 root）：
 * - 后台跟踪：hook Activity.onResume/onPause 维护前台计数，确认整应用进入后台。
 * - 后台自杀：killDelay 到点后应用自我退出（Process.killProcess），下次启动为系统冷启动。
 * - 后台冻结：cpuThrottle 启用时拒绝 WakeLock.acquire（后台不持锁）。
 * - 亮度钳制：brightnessCap 启用时钳制窗口亮度（WindowManager.LayoutParams.screenBrightness）。
 * - 帧率锁：targetFps（或 cpuThrottle>=2 默认 30）时拉大 Choreographer 帧间隔。
 * - 动画禁用：animOff（或 cpuThrottle>0）时 ValueAnimator/Animation 时长置 0。
 * - GPS 限制：gpsPolicy=0 全拦 / =1 后台拦；请求返回 null/不注册。
 * - 蓝牙锁定：btPolicy=0 时本进程内拦截 BluetoothAdapter.enable/setBluetoothEnabled(true)。
 * 受保护进程（Protection.isProtected）只做蓝牙拦截，跳过破坏性策略。
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
    private var suicideTask: Runnable? = null

    private val backgroundCheck = Runnable {
        if (foregroundActivities <= 0 && !backgroundConfirmed) {
            backgroundConfirmed = true
            onBackgroundConfirmed()
        }
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        packageName = lpparam.packageName
        AppLog.i("AppPolicyHook 初始化：" + packageName + " 受保护=" + Protection.isProtected(packageName))
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
            AppLog.i("应用回到前台，取消后台状态：" + packageName)
            backgroundConfirmed = false
            cancelSuicide()
        }
    }

    private fun onActivityPaused() {
        foregroundActivities--
        if (foregroundActivities < 0) foregroundActivities = 0
        mainHandler.removeCallbacks(backgroundCheck)
        mainHandler.postDelayed(backgroundCheck, 500)
    }

    private fun onBackgroundConfirmed() {
        val cfg = ConfigChannel.config()
        if (cfg == null) {
            AppLog.w("后台确认但配置读取失败，不执行后台策略：" + packageName)
            return
        }
        val tpl = ConfigChannel.activeTemplate()
        val delay = cfg.killDelayFor(packageName, tpl?.killDelay ?: -1)
        AppLog.i("应用进入后台：" + packageName + " killDelay=" + delay + " cpuThrottle=" + (tpl?.cpuThrottle ?: 0) + " gpsPolicy=" + (tpl?.gpsPolicy ?: 2))
        if (delay >= 0) {
            if (delay == 0) {
                AppLog.w("killDelay=0，立即自杀：" + packageName)
                suicide()
            } else {
                AppLog.i("调度后台自杀：" + packageName + " " + delay + "s 后执行")
                scheduleSuicide(delay * 1000L)
            }
        } else {
            AppLog.i("killDelay 未配置，不自杀（后台冻结仍按 cpuThrottle 生效）：" + packageName)
        }
    }

    private fun scheduleSuicide(delayMs: Long) {
        val task = Runnable {
            try {
                if (backgroundConfirmed) {
                    AppLog.i("后台自杀定时器触发：" + packageName + " pid=" + Process.myPid())
                    suicide()
                } else {
                    AppLog.d("自杀定时触发时已回前台，跳过：" + packageName)
                }
            } catch (e: Throwable) {
                AppLog.e(e, "自杀定时器异常")
            }
        }
        suicideTask = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun cancelSuicide() {
        suicideTask?.let { mainHandler.removeCallbacks(it) }
        suicideTask = null
    }

    private fun suicide() {
        try {
            AppLog.w("执行应用自杀（相当于删除后台）：" + packageName)
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
                            AppLog.i("后台冻结：拒绝 WakeLock.acquire：" + packageName)
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
            AppLog.i("窗口亮度钳制：" + packageName + " " + cur + " -> " + target + " (cap=" + cap + ")")
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
                            AppLog.i("GPS 受限：拦截 getLastKnownLocation：" + packageName)
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
                            AppLog.i("GPS 受限：拦截 requestLocationUpdates：" + packageName)
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
                            AppLog.i("蓝牙开启请求被拦截（应用进程）：" + packageName)
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
                            AppLog.i("蓝牙状态切换被拦截（应用进程）：请求开启 -> 阻止")
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
