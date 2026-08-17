package com.power.manager.hook

import com.power.manager.core.ConfigProvider
import com.power.manager.core.HardwareProbe
import com.power.manager.core.PhysicalFuse
import com.power.manager.core.StrategyExecutor
import com.power.manager.util.LogUtil

object FpsHook {
    @Volatile
    private var appliedFps = -1

    fun onForegroundChanged(pkg: String) {
        try {
            if (PhysicalFuse.tripped) return
            val cap = HardwareProbe.caps
            if (cap != null && !cap.fpsSupported) return
            val cfg = ConfigProvider.config() ?: return
            val tpl = cfg.templates[cfg.currentTemplateId] ?: return
            val fps = tpl.targetFps
            if (cfg.isRestricted(pkg) && fps >= 30) {
                if (appliedFps != fps) {
                    appliedFps = fps
                    LogUtil.i("前台受限应用 $pkg，帧率锁 $fps")
                    StrategyExecutor.setFps(fps)
                }
            } else if (appliedFps != -1) {
                appliedFps = -1
                LogUtil.i("前台非受限应用，恢复帧率")
                StrategyExecutor.resetFps()
            }
        } catch (e: Throwable) {
            LogUtil.e(e, "FpsHook 异常")
        }
    }
}