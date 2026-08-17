package com.power.manager.data

import java.io.File

object CpuUtil {
    const val MIN_SAFE_RATIO = 0.2

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
        return sanitize(v.toLong(), max)
    }

    /** 审查单个频率值：非法/超频/低于安全线一律规范化，绝不把危险值交给内核。 */
    fun sanitize(freqKHz: Long, max: Long = cpuinfoMaxFreq()): Int {
        if (freqKHz <= 0) return -1
        if (freqKHz > max) return max.toInt()
        val threshold = (max * MIN_SAFE_RATIO).toLong()
        return if (freqKHz < threshold) -1 else freqKHz.toInt()
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
            (max * ratio).toLong()
        } else {
            tpl.cpuFreq.toLong()
        }
        return sanitize(freq, max)
    }
}