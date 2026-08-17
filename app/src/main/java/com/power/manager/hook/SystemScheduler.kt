package com.power.manager.hook

import com.power.manager.core.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * system_server 侧周期任务调度：
 * - 蓝牙锁定周期检查（每 30s）：模板 btPolicy=0 时强制关闭仍开启的蓝牙。
 * 全部任务包裹异常防护，防止扩散影响 system_server。
 */
object SystemScheduler {

    private const val BLUETOOTH_INTERVAL_SEC = 30L

    @Volatile
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (executor != null) {
            AppLog.d("SystemScheduler 已启动，跳过重复初始化")
            return
        }
        val ex = Executors.newScheduledThreadPool(1) { r ->
            Thread(r, "pm-system-scheduler").apply { isDaemon = true }
        }
        executor = ex
        ex.scheduleAtFixedRate(
            { runCatching { BluetoothHook.periodicCheck() } },
            BLUETOOTH_INTERVAL_SEC, BLUETOOTH_INTERVAL_SEC, TimeUnit.SECONDS
        )
        AppLog.i("SystemScheduler 启动：蓝牙锁定周期检查每 " + BLUETOOTH_INTERVAL_SEC + "s")
    }
}
