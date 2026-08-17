package com.power.manager.core

object StatusReporter {
    @Volatile
    var cpuFreqApplied = -1

    @Volatile
    var btDisabledByModule = false
}