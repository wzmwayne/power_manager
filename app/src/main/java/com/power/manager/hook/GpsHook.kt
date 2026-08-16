package com.power.manager.hook

import android.location.Location
import com.power.manager.core.ConfigProvider
import com.power.manager.util.LogUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object GpsHook {
    @Volatile
    private var cachedLocation: Location? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = Class.forName("com.android.server.location.LocationManagerService", false, lpparam.classLoader)

            XposedBridge.hookAllMethods(cls, "requestLocationUpdates", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val pkg = extractPackage(param) ?: return
                        if (shouldBlock(pkg)) {
                            LogUtil.i("GPS：拦截 $pkg 的定位请求")
                            param.result = null
                        }
                    } catch (e: Throwable) {
                        LogUtil.e(e, "GPS requestLocationUpdates 回调异常")
                    }
                }
            })

            val getLast = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val pkg = extractPackage(param) ?: return
                        if (shouldBlock(pkg)) {
                            param.result = cachedLocation
                        }
                    } catch (e: Throwable) {
                        LogUtil.e(e, "GPS getLast 回调异常")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val pkg = extractPackage(param) ?: return
                        if (!shouldBlock(pkg)) {
                            val r = param.result as? Location
                            if (r != null) cachedLocation = Location(r)
                        }
                    } catch (e: Throwable) {
                    }
                }
            }
            XposedBridge.hookAllMethods(cls, "getLastLocation", getLast)
            XposedBridge.hookAllMethods(cls, "getLastKnownLocation", getLast)
            XposedBridge.hookAllMethods(cls, "getCurrentLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val pkg = extractPackage(param) ?: return
                        if (shouldBlock(pkg)) {
                            LogUtil.i("GPS：拦截 $pkg 的 getCurrentLocation")
                            param.result = cachedLocation
                        }
                    } catch (e: Throwable) {
                    }
                }
            })
        } catch (e: Throwable) {
            LogUtil.e(e, "GpsHook 注册失败")
        }
    }

    private fun extractPackage(param: XC_MethodHook.MethodHookParam): String? {
        for (a in param.args) {
            if (a is String && a.length > 4 && a.contains('.')) return a
        }
        return null
    }

    private fun shouldBlock(pkg: String): Boolean {
        val cfg = ConfigProvider.config() ?: return false
        val tpl = cfg.templates[cfg.currentTemplateId] ?: return false
        if (!cfg.isRestricted(pkg)) return false
        return when (tpl.gpsPolicy) {
            0 -> true
            1 -> pkg != CurrentApp.foreground
            else -> false
        }
    }
}