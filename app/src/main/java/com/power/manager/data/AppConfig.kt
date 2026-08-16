package com.power.manager.data

import org.json.JSONArray
import org.json.JSONObject

data class AppConfig(
    val templates: MutableMap<Int, Template>,
    var currentTemplateId: Int = -3,
    val whitelist: MutableSet<String>,
    val blacklist: MutableSet<String>,
    val overrides: MutableMap<String, Template>,
    var authorized: Boolean = false
) {

    fun isRestricted(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (whitelist.contains(pkg)) return false
        return blacklist.contains(pkg)
    }

    fun restrictedPackages(): List<String> = blacklist.filter { !whitelist.contains(it) }

    fun overrideFor(pkg: String): Template? = overrides[pkg]

    fun toJson(): String {
        val o = JSONObject()
        val t = JSONObject()
        for ((id, v) in templates) t.put(id.toString(), v.toJson())
        o.put("templates", t)
        o.put("current", currentTemplateId)
        o.put("whitelist", JSONArray(whitelist.toList()))
        o.put("blacklist", JSONArray(blacklist.toList()))
        val ov = JSONObject()
        for ((pkg, v) in overrides) ov.put(pkg, v.toJson())
        o.put("overrides", ov)
        o.put("authorized", authorized)
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
                whitelist = mutableSetOf(),
                blacklist = mutableSetOf(),
                overrides = mutableMapOf(),
                authorized = false
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
                val whitelist = mutableSetOf<String>()
                val wj = o.optJSONArray("whitelist")
                if (wj != null) for (i in 0 until wj.length()) {
                    val v = wj.optString(i)
                    if (v.isNotEmpty()) whitelist.add(v)
                }
                val blacklist = mutableSetOf<String>()
                val bj = o.optJSONArray("blacklist")
                if (bj != null) for (i in 0 until bj.length()) {
                    val v = bj.optString(i)
                    if (v.isNotEmpty()) blacklist.add(v)
                }
                val overrides = mutableMapOf<String, Template>()
                val oj = o.optJSONObject("overrides")
                if (oj != null) for (k in oj.keys()) oj.optJSONObject(k)?.let { overrides[k] = Template.fromJson(it) }
                AppConfig(
                    templates = t,
                    currentTemplateId = o.optInt("current", -3),
                    whitelist = whitelist,
                    blacklist = blacklist,
                    overrides = overrides,
                    authorized = o.optBoolean("authorized", false)
                )
            } catch (e: Throwable) {
                default()
            }
        }
    }
}