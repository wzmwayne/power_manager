package com.power.manager.core

import org.json.JSONObject
import java.io.File

object StatusReporter {
    @Volatile
    var apiCount = 0
    @Volatile
    var rootCount = 0
    @Volatile
    var lastAction = ""
    @Volatile
    var lastMode = ""
    @Volatile
    var cpuFreqApplied = -1
    @Volatile
    var btDisabledByModule = false

    fun record(mode: ExecMode, action: String) {
        lastAction = action
        lastMode = mode.name
        when (mode) {
            ExecMode.API -> apiCount++
            ExecMode.ROOT -> rootCount++
            ExecMode.FAILED -> Unit
        }
        write()
    }

    fun write() {
        try {
            val obj = JSONObject()
            obj.put("apiCount", apiCount)
            obj.put("rootCount", rootCount)
            obj.put("lastAction", lastAction)
            obj.put("lastMode", lastMode)
            obj.put("rootAvailable", RootChecker.isRootAvailable())
            obj.put("fuseTripped", PhysicalFuse.isTripped())
            obj.put("emergencyFallback", EmergencyGuard.isFallback())
            obj.put("cpuFreqApplied", cpuFreqApplied)
            obj.put("btDisabledByModule", btDisabledByModule)
            val f = File(ModuleFiles.statusFile())
            f.parentFile?.mkdirs()
            f.writeText(obj.toString())
            RootExecutor.chmod(ModuleFiles.statusFile(), "666")
        } catch (e: Throwable) {
        }
    }

    fun summary(): String {
        val rootOk = RootChecker.isRootAvailable()
        return when {
            !rootOk -> "警告：Root 缺失，CPU 限制已禁用"
            rootCount > 0 -> "运行模式：混合（部分功能使用 Root）"
            else -> "运行模式：系统 API（流畅）"
        }
    }
}