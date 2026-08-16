package com.power.manager.core

import java.io.File

object ModuleFiles {
    const val PACKAGE = "com.power.manager"
    private const val DATA_DIR = "/data/user/0/$PACKAGE"
    private val candidates = listOf("$DATA_DIR/files", "/data/local/tmp/power_manager")

    fun filesDir(): String {
        for (d in candidates) {
            val f = File(d)
            if ((f.exists() || f.mkdirs()) && f.canWrite()) return d
        }
        return candidates.last()
    }

    fun statusFile(): String = filesDir() + "/status.json"

    fun logFile(): String = filesDir() + "/power_manager.log"

    fun emergencyFile(): String = filesDir() + "/emergency"

    fun gracefulMarker(): String = filesDir() + "/graceful"

    fun prefsFile(): String = "$DATA_DIR/shared_prefs/config.xml"
}