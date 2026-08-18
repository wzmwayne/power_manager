package com.power.manager.hook

import android.content.ContentValues
import android.net.Uri
import com.power.manager.core.AppLog
import com.power.manager.core.ConfigChannel
import com.power.manager.core.Const
import com.power.manager.core.Protection
import com.power.manager.core.SysContext
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 后台清理全局协调器（system_server 侧，目标 LSPosed 框架的系统层承载，永不挂）：
 * - Hook setResumedActivityUncheckLocked 事件驱动检测前台切换（无轮询）。
 * - 维护后台队列（保序）；应用进后台入队，回前台出队并取消计时。
 * - 超限：后台数超过 maxBg 上限时，立即处决最早进入后台的应用。
 * - 超时：按 killDelay 计时（Handler/ScheduledExecutor，到点触发，非持续计算），到点处决。
 * - 处决：经模块 App ContentProvider（insert /bg action=kill）下发命令，目标应用进程
 *   内观察者收到后自杀（Process.killProcess）；模块 App 被杀不影响本协调器状态。
 */
object BackgroundKeeper {

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pm-bg-keeper").apply { isDaemon = true }
    }
    private val lock = Any()
    private val queue = LinkedHashMap<String, Long>()
    private val timers = ConcurrentHashMap<String, ScheduledFuture<*>>()

    fun hook() {
        val candidates = listOf(
            "com.android.server.wm.ActivityTaskManagerService",
            "com.android.server.am.ActivityManagerService"
        )
        for (cn in candidates) {
            try {
                val clazz = Class.forName(cn)
                XposedBridge.hookAllMethods(clazz, "setResumedActivityUncheckLocked", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            onResumedChanged(param)
                        } catch (e: Throwable) {
                            AppLog.e(e, "setResumedActivityUncheckLocked 回调异常")
                        }
                    }
                })
                AppLog.i("BackgroundKeeper 已尝试注册：" + cn)
            } catch (e: ClassNotFoundException) {
                AppLog.d("BackgroundKeeper 跳过（类不存在）：" + cn)
            } catch (e: Throwable) {
                AppLog.w("BackgroundKeeper 注册失败：" + cn + " " + e.message)
            }
        }
    }

    private fun onResumedChanged(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        if (args.size < 2) {
            AppLog.d("setResumedActivityUncheckLocked 参数不足：" + args.size)
            return
        }
        val newPkg = pkgOf(args[0])
        val prevPkg = pkgOf(args[1])
        if (newPkg == prevPkg) return
        if (!newPkg.isNullOrBlank()) onForeground(newPkg)
        if (!prevPkg.isNullOrBlank()) onBackground(prevPkg)
    }

    private fun onBackground(pkg: String) {
        try {
            if (Protection.isProtected(pkg)) {
                AppLog.i("后台应用受保护，豁免管理：" + pkg)
                return
            }
            val cfg = ConfigChannel.config()
            if (cfg == null) {
                AppLog.w("配置读取失败，无法管理后台应用：" + pkg)
                return
            }
            if (!cfg.isManaged(pkg, false)) {
                AppLog.i("后台应用不受管（enabledBg=false），豁免管理：" + pkg)
                return
            }
            val tpl = ConfigChannel.activeTemplate()
            val delay = cfg.killDelayFor(pkg, tpl?.killDelay ?: -1)
            var maxBg = -1
            var toKill: String? = null
            synchronized(lock) {
                queue.remove(pkg)
                queue[pkg] = System.currentTimeMillis()
                maxBg = cfg.maxBg
                if (maxBg >= 0 && queue.size > maxBg) {
                    val earliest = queue.keys.firstOrNull { it != pkg }
                    if (earliest != null) {
                        queue.remove(earliest)
                        cancelTimer(earliest)
                        toKill = earliest
                    }
                }
                AppLog.i("后台入队：" + pkg + "（当前后台数=" + queue.size + "，超时=" + delay + "s，上限=" + maxBg + "）")
            }
            toKill?.let {
                AppLog.i("后台超限（上限=" + maxBg + "），处决最早后台应用：" + it)
                killApp(it)
            }
            if (delay >= 0) {
                scheduleKill(pkg, delay)
            } else {
                AppLog.i("killDelay 未配置，仅入队不设超时：" + pkg)
            }
        } catch (e: Throwable) {
            AppLog.e(e, "onBackground 异常：" + pkg)
        }
    }

    private fun onForeground(pkg: String) {
        try {
            synchronized(lock) {
                if (queue.remove(pkg) != null) {
                    AppLog.i("应用回前台，出队：" + pkg + "（当前后台数=" + queue.size + "）")
                }
            }
            cancelTimer(pkg)
        } catch (e: Throwable) {
            AppLog.e(e, "onForeground 异常：" + pkg)
        }
    }

    private fun scheduleKill(pkg: String, delaySec: Int) {
        try {
            timers.remove(pkg)?.cancel(false)
            val f = executor.schedule({
                try {
                    synchronized(lock) { queue.remove(pkg) }
                    timers.remove(pkg)
                    AppLog.i("后台超时触发，处决：" + pkg + "（超时 " + delaySec + "s）")
                    killApp(pkg)
                } catch (e: Throwable) {
                    AppLog.e(e, "后台超时任务异常：" + pkg)
                }
            }, delaySec.toLong(), TimeUnit.SECONDS)
            timers[pkg] = f
            AppLog.i("后台计时开始：" + pkg + " " + delaySec + "s 后处决")
        } catch (e: Throwable) {
            AppLog.e(e, "计时注册失败：" + pkg)
        }
    }

    private fun cancelTimer(pkg: String) {
        timers.remove(pkg)?.let {
            it.cancel(false)
            AppLog.d("后台计时取消：" + pkg)
        }
    }

    /** 经模块 App ContentProvider 下发处决命令（应用进程内观察者收到后自杀）。 */
    private fun killApp(pkg: String) {
        try {
            val resolver = SysContext.contentResolver() ?: return
            val cv = ContentValues()
            cv.put("action", "kill")
            cv.put("pkg", pkg)
            resolver.insert(Uri.parse(Const.BG_URI), cv)
            AppLog.i("处决命令已下发：" + pkg)
        } catch (e: Throwable) {
            AppLog.e(e, "处决命令下发失败：" + pkg)
        }
    }

    private fun pkgOf(record: Any?): String? {
        if (record == null) return null
        return try {
            XposedHelpers.getStringField(record, "packageName")
        } catch (e: Throwable) {
            try {
                val ai = XposedHelpers.getObjectField(record, "activityInfo")
                XposedHelpers.getStringField(ai, "packageName")
            } catch (e2: Throwable) {
                AppLog.d("ActivityRecord 包名解析失败")
                null
            }
        }
    }
}
