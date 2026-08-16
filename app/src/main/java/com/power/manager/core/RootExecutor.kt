package com.power.manager.core

import com.power.manager.data.CpuUtil
import java.io.File
import java.util.concurrent.TimeUnit

object RootExecutor {
    private const val TIMEOUT_MS = 300L

    fun run(command: String): Boolean {
        val proc = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            return false
        }
        return try {
            val done = proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!done) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0
            }
        } catch (e: Throwable) {
            proc.destroyForcibly()
            false
        }
    }

    fun run(commands: List<String>): Boolean = run(commands.joinToString(";"))

    fun isAvailable(): Boolean = run("echo ok")

    fun writeFile(path: String, content: String): Boolean {
        val parent = File(path).parent ?: "/"
        return run("mkdir -p $parent; printf '%s' '$content' > $path; chmod 644 $path")
    }

    fun removeFile(path: String): Boolean = run("rm -f $path")

    fun createFile(path: String): Boolean = run("touch $path; chmod 644 $path")

    fun chmod(path: String, mode: String): Boolean = run("chmod $mode $path")

    fun writeCpuMaxFreq(freqKHz: Int): Boolean {
        val target = if (freqKHz > 0) freqKHz else CpuUtil.cpuinfoMaxFreq().toInt()
        val cmds = mutableListOf<String>()
        for (i in 0 until 32) {
            cmds += "if [ -f /sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq ]; then echo $target > /sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq; fi"
        }
        return run(cmds)
    }

    fun restoreCpu(): Boolean = writeCpuMaxFreq(-1)
}