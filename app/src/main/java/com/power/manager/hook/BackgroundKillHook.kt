package com.power.manager.hook

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.power.manager.core.ApiExecutor
import com.power.manager.core.ConfigProvider
import com.power.manager.core.Protection
import com.power.manager.core.StrategyExecutor
import com.power.manager.util.LogUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object BackgroundKillHook {
    private const val ENFORCE_INTERVAL_MS = 15_000L
    private val thread = HandlerThread("kill-worker").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile
    private var lastForeground: String? = null
    private var enforceRunning = false

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cls = Class.forName("com.android.server.wm.ActivityTaskManagerService", false, lpparam.classLoader)
            XposedBridge.hookAllMethods(
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
            startEnforceLoop()
        } catch (e: Throwable) {
            LogUtil.e(e, "BackgroundKillHook 注册失败")
        }
    }

    private fun startEnforceLoop() {
        if (enforceRunning) return
        enforceRunning = true
        handler.postDelayed(enforceLoop, ENFORCE_INTERVAL_MS)
    }

    private val enforceLoop = object : Runnable {
        override fun run() {
            try {
                enforceMaxBg()
            } catch (e: Throwable) {
                LogUtil.e(e, "maxBg 检查异常")
            }
            handler.postDelayed(this, ENFORCE_INTERVAL_MS)
        }
    }

    /** max_bg：后台受限进程数超限时，清理最不重要（缓存后台）的进程。 */
    private fun enforceMaxBg() {
        val cfg = ConfigProvider.config() ?: return
        val tpl = cfg.templates[cfg.currentTemplateId] ?: return
        if (tpl.maxBg < 0) return
        val ctx = ApiExecutor.systemContext() ?: return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val procs = am.runningAppProcesses ?: return
        val candidates = mutableListOf<Pair<String, Int>>()
        for (p in procs) {
            if (p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) continue
            val pkg = p.pkgList.firstOrNull { it.isNotBlank() && cfg.isRestricted(it) && !Protection.isProtected(it) }
                ?: continue
            if (pkg == CurrentApp.foreground) continue
            candidates += pkg to p.importance
        }
        if (candidates.size <= tpl.maxBg) return
        val sorted = candidates.sortedByDescending { it.second }
        val overflow = candidates.size - tpl.maxBg
        for (i in 0 until overflow) {
            val pkg = sorted[i].first
            LogUtil.i("后台受限进程超限（上限 ${tpl.maxBg}），清理：$pkg")
            StrategyExecutor.forceStop(pkg)
        }
    }

    private fun extractPackageName(param: XC_MethodHook.MethodHookParam): String? {
        return try {
            val arg = param.args.getOrNull(0) ?: return null
            try {
                XposedHelpers.getObjectField(arg, "packageName") as? String
            } catch (e: Throwable) {
                try {
                    val info = XposedHelpers.getObjectField(arg, "info")
                    XposedHelpers.getObjectField(info, "packageName") as? String
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