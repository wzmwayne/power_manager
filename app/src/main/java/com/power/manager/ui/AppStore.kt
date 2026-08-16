package com.power.manager.ui

import android.content.Context
import android.content.SharedPreferences
import com.power.manager.core.EmergencyGuard
import com.power.manager.core.PhysicalFuse
import com.power.manager.core.RootChecker
import com.power.manager.core.RootExecutor
import com.power.manager.data.AppConfig
import com.power.manager.data.CpuUtil
import com.power.manager.data.Template
import java.io.File

object AppStore {
    private const val PREFS = "config"
    private const val KEY = "config"
    private lateinit var prefs: SharedPreferences
    private var cached: AppConfig? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cached = null
    }

    fun load(): AppConfig {
        cached?.let { return it }
        val raw = prefs.getString(KEY, null)
        val cfg = raw?.let { AppConfig.fromJson(it) } ?: AppConfig.default()
        cached = cfg
        return cfg
    }

    fun save(cfg: AppConfig) {
        prefs.edit().putString(KEY, cfg.toJson()).apply()
        cached = cfg
    }

    fun ensureShared(ctx: Context) {
        try {
            val files = ctx.filesDir
            files.mkdirs()
            val st = File(files, "status.json")
            if (!st.exists()) st.writeText("{}")
            val lg = File(files, "power_manager.log")
            if (!lg.exists()) lg.writeText("")
            RootExecutor.run(
                "chmod 777 ${files.absolutePath}; " +
                    "chmod 666 ${st.absolutePath}; " +
                    "chmod 666 ${lg.absolutePath}"
            )
        } catch (e: Throwable) {
        }
    }

    fun nextTemplateId(cfg: AppConfig): Int {
        var max = -1
        for (id in cfg.templates.keys) if (id >= 0 && id > max) max = id
        return max + 1
    }

    fun createTemplate(cfg: AppConfig, source: Template, name: String): Template {
        val id = nextTemplateId(cfg)
        return source.copyWith(id, name)
    }

    fun applyTemplate(cfg: AppConfig, tpl: Template) {
        val root = RootChecker.isRootAvailable()
        var target = tpl
        if (!root && tpl.cpuFreq != -1) {
            target = tpl.copy(cpuFreq = -1)
        }
        cfg.templates[target.id] = target
        cfg.currentTemplateId = target.id
        save(cfg)
        if (target.cpuFreq != -1) {
            RootExecutor.writeCpuMaxFreq(CpuUtil.resolveCpuFreq(target))
        }
    }

    fun deleteTemplate(cfg: AppConfig, tpl: Template) {
        if (tpl.isBuiltin) return
        cfg.templates.remove(tpl.id)
        if (cfg.currentTemplateId == tpl.id) cfg.currentTemplateId = -3
        save(cfg)
    }

    fun resetTemplates(cfg: AppConfig) {
        val user = cfg.templates.keys.filter { it >= 0 }
        for (id in user) cfg.templates.remove(id)
        cfg.currentTemplateId = -3
        save(cfg)
    }

    fun authorize(): Boolean = PhysicalFuse.authorize()

    fun revoke(): Boolean = PhysicalFuse.revoke()

    fun clearEmergency(): Boolean = EmergencyGuard.clearFallback()

    fun isRoot(): Boolean = RootChecker.isRootAvailable()
}