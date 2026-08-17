package com.power.manager.core

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.power.manager.data.CpuUtil
import org.json.JSONObject
import java.io.File

data class HardwareCap(
    val cpuFreqSupported: Boolean = true,
    val fpsSupported: Boolean = true,
    val animSupported: Boolean = true,
    val bluetoothSupported: Boolean = true,
    val networkSupported: Boolean = true,
    val gpsSupported: Boolean = true,
    val cpuMaxFreqKHz: Int = 2000000,
    val cpuCoreCount: Int = 1,
    val scannedAt: Long = 0
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("cpuFreq", cpuFreqSupported)
        o.put("fps", fpsSupported)
        o.put("anim", animSupported)
        o.put("bluetooth", bluetoothSupported)
        o.put("network", networkSupported)
        o.put("gps", gpsSupported)
        o.put("maxFreq", cpuMaxFreqKHz)
        o.put("cores", cpuCoreCount)
        o.put("scannedAt", scannedAt)
        return o
    }

    fun unsupportedList(): List<String> {
        val list = mutableListOf<String>()
        if (!cpuFreqSupported) list.add("CPU调频")
        if (!fpsSupported) list.add("帧率锁")
        if (!animSupported) list.add("动画禁用")
        if (!bluetoothSupported) list.add("蓝牙控制")
        if (!networkSupported) list.add("后台网络限制")
        if (!gpsSupported) list.add("GPS 拦截")
        return list
    }

    companion object {
        private const val FILE = "caps.json"

        fun fromJson(o: JSONObject): HardwareCap = HardwareCap(
            cpuFreqSupported = o.optBoolean("cpuFreq", true),
            fpsSupported = o.optBoolean("fps", true),
            animSupported = o.optBoolean("anim", true),
            bluetoothSupported = o.optBoolean("bluetooth", true),
            networkSupported = o.optBoolean("network", true),
            gpsSupported = o.optBoolean("gps", true),
            cpuMaxFreqKHz = o.optInt("maxFreq", 2000000),
            cpuCoreCount = o.optInt("cores", 1),
            scannedAt = o.optLong("scannedAt", 0)
        )

        fun capFile(): String = ModuleFiles.filesDir() + "/" + FILE

        fun fromFile(): HardwareCap? {
            return try {
                val f = File(capFile())
                if (!f.exists()) return null
                fromJson(JSONObject(f.readText()))
            } catch (e: Throwable) {
                null
            }
        }
    }
}

object HardwareProbe {
    @Volatile
    var caps: HardwareCap? = null
        private set

    fun load(): HardwareCap? {
        val c = HardwareCap.fromFile() ?: return null
        caps = c
        return c
    }

    private fun save() {
        val c = caps ?: return
        try {
            val f = File(HardwareCap.capFile())
            f.parentFile?.mkdirs()
            f.writeText(c.toJson().toString())
            RootExecutor.chmod(HardwareCap.capFile(), "666")
        } catch (e: Throwable) {
        }
    }

    /** 授权时扫描硬件基准并逐项测试能力，结果落盘供 system_server 运行时读取。 */
    fun scan(ctx: Context): HardwareCap {
        val max = CpuUtil.cpuinfoMaxFreq().toInt()
        val cores = countCores()
        val cpuFreq = testCpuFreq()
        val fps = testSettings("system", "peak_refresh_rate", "60")
        val anim = testSettings("global", "animator_duration_scale", "1")
        val bt = try {
            BluetoothAdapter.getDefaultAdapter() != null
        } catch (e: Throwable) {
            false
        }
        val gps = try {
            ctx.getSystemService(Context.LOCATION_SERVICE) != null
        } catch (e: Throwable) {
            false
        }
        val network = testNetwork(ctx)
        val c = HardwareCap(cpuFreq, fps, anim, bt, network, gps, max, cores, System.currentTimeMillis())
        caps = c
        save()
        return c
    }

    private fun countCores(): Int {
        var n = 0
        for (i in 0 until 32) {
            try {
                if (File("/sys/devices/system/cpu/cpu$i").isDirectory) n++
            } catch (e: Throwable) {
            }
        }
        return maxOf(n, 1)
    }

    /** 测试 cpufreq 节点可写性：读当前值原样写回，不改变任何频率状态。 */
    private fun testCpuFreq(): Boolean {
        try {
            val f = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq")
            if (!f.exists()) return false
        } catch (e: Throwable) {
            return false
        }
        return RootExecutor.run(
            "V=\$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq); echo \$V > /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
        )
    }

    /** 测试 settings 节点可写性：写入探测值、读回验证、恢复原值，无副作用。 */
    private fun testSettings(namespace: String, key: String, value: String): Boolean {
        return RootExecutor.run(
            "O=\$(settings get $namespace $key); settings put $namespace $key $value; " +
                "R=\$(settings get $namespace $key); if [ \"\$R\" = \"$value\" ]; then " +
                "if [ -n \"\$O\" ]; then settings put $namespace $key \"\$O\"; else settings delete $namespace $key; fi; " +
                "exit 0; else if [ -n \"\$O\" ]; then settings put $namespace $key \"\$O\"; else settings delete $namespace $key; fi; exit 1; fi"
        )
    }

    private fun testNetwork(ctx: Context): Boolean {
        return try {
            val cls = Class.forName("android.net.NetworkPolicyManager")
            val from = cls.getMethod("from", Context::class.java)
            from.invoke(null, ctx) != null
        } catch (e: Throwable) {
            false
        }
    }
}