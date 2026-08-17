package com.power.manager.core

import android.os.Handler
import android.os.HandlerThread
import com.power.manager.data.AppConfig
import com.power.manager.data.CpuUtil
import com.power.manager.data.Template
import com.power.manager.util.LogUtil

object ModuleScheduler {
    private const val TICK_MS = 10_000L
    private const val CPU_REAPPLY_MS = 60_000L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastCpuApply = 0L
    private var running = false

    fun start() {
        if (running) return
        running = true
        HardwareProbe.load()
        thread = HandlerThread("power-manager").also { it.start() }
        handler = Handler(thread!!.looper)
        handler?.postDelayed(tick, 100L)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                if (PhysicalFuse.isTripped()) {
                    LogUtil.w("运行期间检测到物理熔断，强制恢复 CPU 并停止调度")
                    RootExecutor.restoreCpu()
                    running = false
                    return
                }
                ConfigProvider.reload()
                syncStrategies()
                val now = System.currentTimeMillis()
                if (now - lastCpuApply >= CPU_REAPPLY_MS) {
                    val tpl = ConfigProvider.activeTemplate()
                    if (tpl != null) applyCpu(tpl)
                    lastCpuApply = now
                }
                StatusReporter.write()
            } catch (e: Throwable) {
                LogUtil.e(e, "调度循环异常")
            }
            handler?.postDelayed(this, TICK_MS)
        }
    }

    private fun syncStrategies() {
        val cfg = ConfigProvider.config() ?: return
        val tpl = cfg.templates[cfg.currentTemplateId] ?: return
        try {
            val cap = HardwareProbe.caps
            if (cap == null || cap.animSupported) {
                if (tpl.animOff) StrategyExecutor.disableAnimation() else StrategyExecutor.enableAnimation()
            }
            syncBluetooth(tpl)
            syncNetwork(cfg, tpl)
        } catch (e: Throwable) {
            LogUtil.e(e, "策略同步异常")
        }
    }

    private fun syncBluetooth(tpl: Template) {
        val cap = HardwareProbe.caps
        if (cap != null && !cap.bluetoothSupported) return
        if (tpl.btPolicy == 0) {
            if (!StatusReporter.btDisabledByModule && ApiExecutor.isBluetoothOn()) {
                StatusReporter.btDisabledByModule = true
                StrategyExecutor.disableBluetooth()
            }
        } else {
            if (StatusReporter.btDisabledByModule) {
                StatusReporter.btDisabledByModule = false
                StrategyExecutor.enableBluetooth()
            }
        }
    }

    private fun syncNetwork(cfg: AppConfig, tpl: Template) {
        if (tpl.netPolicy != 0) return
        val cap = HardwareProbe.caps
        if (cap != null && !cap.networkSupported) {
            LogUtil.w("后台网络限制不受支持，已自动禁用")
            return
        }
        for (pkg in cfg.restrictedPackages()) {
            val uid = packageUid(pkg)
            if (uid > 0) StrategyExecutor.restrictUid(uid, true)
        }
    }

    fun applyCpu(tpl: Template) {
        val cap = HardwareProbe.caps
        if (cap != null && !cap.cpuFreqSupported) {
            LogUtil.w("CPU 调频不受支持，已自动禁用")
            StatusReporter.cpuFreqApplied = -1
            return
        }
        val freq = CpuUtil.resolveCpuFreq(tpl)
        val mode = StrategyExecutor.setCpuFreq(freq)
        if (mode == ExecMode.API || mode == ExecMode.ROOT) {
            StatusReporter.cpuFreqApplied = freq
        } else {
            StatusReporter.cpuFreqApplied = -1
        }
    }

    private fun packageUid(pkg: String): Int {
        return try {
            val ctx = ApiExecutor.systemContext() ?: return -1
            ctx.packageManager.getPackageUid(pkg, 0)
        } catch (e: Throwable) {
            -1
        }
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
    }
}