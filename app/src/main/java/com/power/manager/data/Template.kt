package com.power.manager.data

import org.json.JSONObject

data class Template(
    val id: Int,
    val name: String,
    val maxBg: Int = -1,
    val killDelay: Int = -1,
    val targetFps: Int = -1,
    val cpuFreq: Int = -1,
    val brightnessCap: Int = -1,
    val animOff: Boolean = false,
    val gpsPolicy: Int = 2,
    val netPolicy: Int = 1,
    val btPolicy: Int = 1,
    val batterySaver: Boolean = false
) {
    val isBuiltin: Boolean get() = id < 0
    val isReadOnly: Boolean get() = id < 0

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("max_bg", maxBg)
        o.put("kill_delay", killDelay)
        o.put("target_fps", targetFps)
        o.put("cpu_freq", cpuFreq)
        o.put("brightness_cap", brightnessCap)
        o.put("anim_off", if (animOff) 1 else 0)
        o.put("gps_policy", gpsPolicy)
        o.put("net_policy", netPolicy)
        o.put("bt_policy", btPolicy)
        o.put("battery_saver", if (batterySaver) 1 else 0)
        return o
    }

    fun copyWith(id: Int, name: String = this.name): Template = copy(id = id, name = name)

    companion object {
        val BUILTIN_NORMAL = Template(-3, "正常模式")

        val BUILTIN_SAVING = Template(
            -2, "省电模式",
            maxBg = 3, killDelay = 120, targetFps = 60, cpuFreq = -2,
            brightnessCap = 200, animOff = true, gpsPolicy = 1, netPolicy = 0, btPolicy = 1,
            batterySaver = true
        )

        val BUILTIN_ULTRA = Template(
            -1, "极限模式",
            maxBg = 1, killDelay = 30, targetFps = 30, cpuFreq = -2,
            brightnessCap = 80, animOff = true, gpsPolicy = 0, netPolicy = 0, btPolicy = 0,
            batterySaver = true
        )

        fun fromJson(o: JSONObject): Template {
            return Template(
                id = o.optInt("id", 0),
                name = o.optString("name", "模板"),
                maxBg = o.optInt("max_bg", -1),
                killDelay = o.optInt("kill_delay", -1),
                targetFps = o.optInt("target_fps", -1),
                cpuFreq = o.optInt("cpu_freq", -1),
                brightnessCap = o.optInt("brightness_cap", -1),
                animOff = o.optInt("anim_off", 0) == 1,
                gpsPolicy = o.optInt("gps_policy", 2),
                netPolicy = o.optInt("net_policy", 1),
                btPolicy = o.optInt("bt_policy", 1),
                batterySaver = o.optInt("battery_saver", 0) == 1
            )
        }
    }
}