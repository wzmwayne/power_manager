package com.power.manager.core

import java.io.File

object PhysicalFuse {
    private const val PMON = "/sdcard/pmon"
    private const val PMOFF = "/sdcard/pmoff"

    @Volatile
    var tripped: Boolean = false
        private set

    fun isTripped(): Boolean {
        val r = try {
            val pmonOk = File(PMON).exists() && File(PMON).canRead()
            !pmonOk || File(PMOFF).exists()
        } catch (e: Throwable) {
            true
        }
        if (r) tripped = true
        return r
    }

    fun resetTripped() {
        tripped = false
    }

    fun authorize(): Boolean {
        val ok = RootExecutor.writeFile(PMON, "authorized-by-power-manager")
        if (ok) RootExecutor.removeFile(PMOFF)
        return ok
    }

    fun revoke(): Boolean = RootExecutor.createFile(PMOFF)

    fun pmoffPaths(): List<String> = listOf(PMOFF)

    fun pmonPath(): String = PMON
}