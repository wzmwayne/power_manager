package com.power.manager.data

import org.json.JSONArray
import org.json.JSONObject

data class AppConfig(
    val templates: MutableMap<Int, Template>,
    var currentTemplateId: Int = -3,
    var listMode: Int = LIST_MODE_BLACK,
    val appList: MutableSet<String>,
    val rules: MutableMap<String, AppRule>
) {
    fun isRestricted(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return if (listMode == LIST_MODE_WHITE) {
            !appList.contains(pkg)
        } else {
            appList.contains(pkg)
        }
    }

    /** 白名单模式下无法从配置枚举「所有未列入的应用」，运行时由 system_server 枚举已装应用补全。 */
    fun restrictedPackages(): List<String> =
        if (listMode == LIST_MODE_WHITE) emptyList() else appList.toList()

    /** 单独应用规则优先判定模块是否管辖该应用。 */
    fun isManaged(pkg: String, foreground: Boolean): Boolean {
        val rule = rules[pkg] ?: return isRestricted(pkg)
        return if (foreground) rule.enabledFg else rule.enabledBg
    }

    /** 后台杀死时间：单独应用规则优先，其次全局模板。 */
    fun killDelayFor(pkg: String, fallback: Int): Int {
        val rule = rules[pkg] ?: return fallback
        return if (rule.killDelay >= 0) rule.killDelay else fallback
    }

    fun ruleFor(pkg: String): AppRule? = rules[pkg]

    fun toJson(): String {
        val o = JSONObject()
        val t = JSONObject()
        for ((id, v) in templates) t.put(id.toString(), v.toJson())
        o.put("templates", t)
        o.put("current", currentTemplateId)
        o.put("list_mode", listMode)
        o.put("list", JSONArray(appList.toList()))
        val r = JSONObject()
        for ((pkg, v) in rules) r.put(pkg, v.toJson())
        o.put("rules", r)
        return o.toString()
    }

    companion object {
        const val LIST_MODE_BLACK = 0
        const val LIST_MODE_WHITE = 1

        fun default(): AppConfig {
            val t = LinkedHashMap<Int, Template>()
            t[-3] = Template.BUILTIN_NORMAL
            t[-2] = Template.BUILTIN_SAVING
            t[-1] = Template.BUILTIN_ULTRA
            return AppConfig(
                templates = t,
                currentTemplateId = -3,
                listMode = LIST_MODE_BLACK,
                appList = mutableSetOf(),
                rules = mutableMapOf()
            )
        }

        fun fromJson(s: String): AppConfig {
            return try {
                val o = JSONObject(s)
                val t = LinkedHashMap<Int, Template>()
                val tj = o.optJSONObject("templates")
                if (tj != null) {
                    for (k in tj.keys()) {
                        val id = k.toIntOrNull() ?: continue
                        tj.optJSONObject(k)?.let { t[id] = Template.fromJson(it) }
                    }
                }
                for (b in listOf(Template.BUILTIN_NORMAL, Template.BUILTIN_SAVING, Template.BUILTIN_ULTRA)) {
                    if (!t.containsKey(b.id)) t[b.id] = b
                }
                val list = mutableSetOf<String>()
                val listMode = o.optInt("list_mode", LIST_MODE_BLACK)
                val lj = o.optJSONArray("list")
                if (lj != null) {
                    for (i in 0 until lj.length()) {
                        val v = lj.optString(i)
                        if (v.isNotEmpty()) list.add(v)
                    }
                }
                val rules = mutableMapOf<String, AppRule>()
                val rj = o.optJSONObject("rules")
                if (rj != null) for (k in rj.keys()) rj.optJSONObject(k)?.let { rules[k] = AppRule.fromJson(it) }
                AppConfig(
                    templates = t,
                    currentTemplateId = o.optInt("current", -3),
                    listMode = listMode,
                    appList = list,
                    rules = rules
                )
            } catch (e: Throwable) {
                default()
            }
        }
    }
}
