package com.power.manager.core

import java.io.File

object PhysicalFuse {
    private val pmoffPaths = arrayOf(
        "/data/local/tmp/pmoff",
        "/data/local/tmp/pmoff.txt",
        "/sdcard/pmoff",
        "/sdcard/pmoff.txt",
        "/storage/emulated/0/pmoff",
        "/cache/pmoff",
        "/system/pmoff",
        "/pmoff"
    )
    private const val PMON = "/sdcard/pmon"
    private const val PMON_LEGACY = "/pmon"

    @Volatile
    var tripped: Boolean = false
        private set

    fun isTripped(): Boolean {
        val r = try {
            val pmonOk = File(PMON).exists() && File(PMON).canRead() ||
                File(PMON_LEGACY).exists() && File(PMON_LEGACY).canRead()
            if (!pmonOk) true
            else pmoffPaths.any { File(it).exists() }
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
        removePmoff()
        return ok
    }

    fun revoke(): Boolean = RootExecutor.createFile(pmoffPaths[0])

    private fun removePmoff() {
        for (p in pmoffPaths) RootExecutor.removeFile(p)
    }

    fun pmoffPaths(): List<String> = pmoffPaths.toList()

    fun pmonPath(): String = PMON
}