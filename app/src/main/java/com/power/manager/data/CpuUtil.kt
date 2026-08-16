package com.power.manager.data

import java.io.File

object CpuUtil {
    @Volatile
    private var cachedMax: Long = -1

    fun cpuinfoMaxFreq(): Long {
        if (cachedMax > 0) return cachedMax
        var v = 2000000L
        try {
            val f = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
            if (f.exists()) {
                f.readText().trim().toLongOrNull()?.let { if (it > 0) v = it }
            }
        } catch (e: Throwable) {
        }
        cachedMax = v
        return v
    }

    fun clearCache() {
        cachedMax = -1
    }

    fun convertToKHz(input: Double): Int {
        val max = cpuinfoMaxFreq()
        var v = input
        if (v > 0 && v <= 1.0) v *= max
        if (v > max) v = max.toDouble()
        val threshold = max * 0.2
        return if (v < threshold) -1 else v.toInt()
    }

    fun resolveCpuFreq(tpl: Template): Int {
        if (tpl.cpuFreq == -1) return -1
        val max = cpuinfoMaxFreq()
        val freq: Long = if (tpl.cpuFreq == -2) {
            val ratio = when (tpl.id) {
                -2 -> 0.7
                -1 -> 0.4
                else -> 0.7
            }
            (max * ratio)
        } else {
            tpl.cpuFreq.toLong()
        }
        if (freq > max) return -1
        val threshold = max * 0.2
        return if (freq < threshold) -1 else freq.toInt()
    }
}