package com.power.manager.hook

import android.os.Handler
import android.os.HandlerThread
import com.power.manager.core.ApiExecutor
import com.power.manager.core.ConfigProvider
import com.power.manager.core.Protection
import com.power.manager.core.StrategyExecutor
import com.power.manager.util.LogUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object BackgroundKillHook {
    private val thread = HandlerThread("kill-worker").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile
    private var lastForeground: String? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = Class.forName("com.android.server.wm.ActivityTaskManagerService", false, lpparam.classLoader)
            XposedHelpers.hookAllMethods(
                cls,
                "setResumedActivityUncheckLocked",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val pkg = extractPackageName(param) ?: return
                            CurrentApp.foreground = pkg
                            FpsHook.onForegroundChanged(pkg)
                            val prev = lastForeground
                            lastForeground = pkg
                            if (prev != null && prev != pkg) {
                                scheduleKill(prev)
                            }
                        } catch (e: Throwable) {
                            LogUtil.e(e, "setResumedActivity 回调异常")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            LogUtil.e(e, "BackgroundKillHook 注册失败")
        }
    }

    private fun extractPackageName(param: XC_MethodHook.MethodHookParam): String? {
        return try {
            val arg = param.args.getOrNull(0) ?: return null
            try {
                XposedHelpers.getStringField(arg, "packageName")
            } catch (e: Throwable) {
                try {
                    val info = XposedHelpers.getObjectField(arg, "info")
                    XposedHelpers.getStringField(info, "packageName")
                } catch (e2: Throwable) {
                    null
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun scheduleKill(pkg: String) {
        handler.post {
            try {
                val cfg = ConfigProvider.config() ?: return@post
                val tpl = cfg.templates[cfg.currentTemplateId] ?: return@post
                if (!cfg.isRestricted(pkg)) return@post
                if (Protection.isProtected(pkg)) return@post
                if (inCall()) {
                    LogUtil.i("通话中，豁免清理 $pkg")
                    return@post
                }
                val delay = tpl.killDelay
                if (delay <= 0) {
                    LogUtil.i("切后台清理 $pkg（即时）")
                    StrategyExecutor.forceStop(pkg)
                } else {
                    LogUtil.i("切后台清理 $pkg（$delay 秒后）")
                    handler.postDelayed({ tryKill(pkg) }, delay * 1000L)
                }
            } catch (e: Throwable) {
                LogUtil.e(e, "scheduleKill 异常")
            }
        }
    }

    private fun tryKill(pkg: String) {
        try {
            val cfg = ConfigProvider.config() ?: return
            val tpl = cfg.templates[cfg.currentTemplateId] ?: return
            if (!cfg.isRestricted(pkg)) return
            if (Protection.isProtected(pkg)) return
            if (pkg == CurrentApp.foreground) return
            if (inCall()) return
            StrategyExecutor.forceStop(pkg)
        } catch (e: Throwable) {
            LogUtil.e(e, "tryKill 异常")
        }
    }

    private fun inCall(): Boolean {
        return try {
            val ctx = ApiExecutor.systemContext() ?: return false
            val tm = ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            tm != null && tm.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE
        } catch (e: Throwable) {
            false
        }
    }
}