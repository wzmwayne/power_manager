package com.power.manager.core

import com.power.manager.util.LogUtil
import java.io.File

object EmergencyGuard {
    private const val MARKER = "graceful"

    fun markGraceful() {
        try {
            val f = File(ModuleFiles.gracefulMarker())
            f.parentFile?.mkdirs()
            f.writeText(MARKER)
            RootExecutor.chmod(ModuleFiles.gracefulMarker(), "666")
        } catch (e: Throwable) {
        }
    }

    fun isFallback(): Boolean {
        return try {
            File(ModuleFiles.emergencyFile()).exists()
        } catch (e: Throwable) {
            false
        }
    }

    fun checkAndReset() {
        val active = ConfigProvider.activeTemplate()?.id ?: -3
        val graceful = File(ModuleFiles.gracefulMarker()).exists()
        if (active != -3 && !graceful) {
            LogUtil.w("检测到异常关机且非正常模板激活，已自动回退正常模式(-3)，并强制恢复 CPU")
            RootExecutor.restoreCpu()
            try {
                val f = File(ModuleFiles.emergencyFile())
                f.parentFile?.mkdirs()
                f.writeText("fallback")
                RootExecutor.chmod(ModuleFiles.emergencyFile(), "666")
            } catch (e: Throwable) {
            }
        }
        try {
            File(ModuleFiles.gracefulMarker()).delete()
        } catch (e: Throwable) {
        }
    }

    fun clearFallback(): Boolean {
        var ok = try {
            File(ModuleFiles.emergencyFile()).delete()
        } catch (e: Throwable) {
            false
        }
        if (!ok) ok = RootExecutor.removeFile(ModuleFiles.emergencyFile())
        return ok
    }
}