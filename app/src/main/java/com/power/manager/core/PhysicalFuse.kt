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
    private const val PMON = "/pmon"

    fun isTripped(): Boolean {
        return try {
            if (!File(PMON).exists() || !File(PMON).canRead()) return true
            pmoffPaths.any { File(it).exists() }
        } catch (e: Throwable) {
            true
        }
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