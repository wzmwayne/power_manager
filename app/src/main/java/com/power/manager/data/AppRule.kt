package com.power.manager.data

import org.json.JSONObject

/**
 * 单独应用设置规则（优先级高于黑白名单与全局模板）。
 * 包名作为 Map 键，不在本类中重复存储。
 */
data class AppRule(
    val enabledFg: Boolean = true,
    val enabledBg: Boolean = true,
    val killDelay: Int = -1
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("enabled_fg", if (enabledFg) 1 else 0)
        o.put("enabled_bg", if (enabledBg) 1 else 0)
        o.put("kill_delay", killDelay)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): AppRule {
            return AppRule(
                enabledFg = o.optInt("enabled_fg", 1) == 1,
                enabledBg = o.optInt("enabled_bg", 1) == 1,
                killDelay = o.optInt("kill_delay", -1)
            )
        }
    }
}
