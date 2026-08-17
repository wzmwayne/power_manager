package com.power.manager.data

import org.json.JSONArray
import org.json.JSONObject

data class AppConfig(
    val templates: MutableMap<Int, Template>,
    var currentTemplateId: Int = -3,
    val rules: MutableMap<String, AppRule>
) {
    /** 作用域即全部受管应用。规则不存在时默认受管，存在时按前台/后台启用标志裁决。 */
    fun isManaged(pkg: String, foreground: Boolean): Boolean {
        val rule = rules[pkg] ?: return true
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
        val r = JSONObject()
        for ((pkg, v) in rules) r.put(pkg, v.toJson())
        o.put("rules", r)
        return o.toString()
    }

    companion object {
        fun default(): AppConfig {
            val t = LinkedHashMap<Int, Template>()
            t[-3] = Template.BUILTIN_NORMAL
            t[-2] = Template.BUILTIN_SAVING
            t[-1] = Template.BUILTIN_ULTRA
            return AppConfig(
                templates = t,
                currentTemplateId = -3,
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
                val rules = mutableMapOf<String, AppRule>()
                val rj = o.optJSONObject("rules")
                if (rj != null) for (k in rj.keys()) rj.optJSONObject(k)?.let { rules[k] = AppRule.fromJson(it) }
                AppConfig(
                    templates = t,
                    currentTemplateId = o.optInt("current", -3),
                    rules = rules
                )
            } catch (e: Throwable) {
                default()
            }
        }
    }
}
