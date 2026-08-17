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
    private var prefsDir: File? = null
    private var cached: AppConfig? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
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
        prefs.edit().putString(KEY, cfg.toJson()).commit()
        cached = cfg
        makeSharedReadable()
    }

    /** MODE_PRIVATE 落盘权限为 600，system_server 无法读取。必须每次保存后经 Root 开放目录与文件读取，否则模块读不到配置。 */
    private fun makeSharedReadable() {
        try {
            val dir = prefsDir ?: return
            val xml = File(dir, "config.xml")
            RootExecutor.run(
                "mkdir -p ${dir.absolutePath}; chmod 777 ${dir.absolutePath}; chmod 666 ${xml.absolutePath}"
            )
        } catch (e: Throwable) {
        }
    }

    fun ensureShared(ctx: Context) {
        try {
            val files = ctx.filesDir
            files.mkdirs()
            val st = File(files, "status.json")
            if (!st.exists()) st.writeText("{}")
            val lg = File(files, "power_manager.log")
            if (!lg.exists()) lg.writeText("")
            val sp = File(ctx.applicationInfo.dataDir, "shared_prefs")
            RootExecutor.run(
                "mkdir -p ${sp.absolutePath}; chmod 777 ${files.absolutePath}; " +
                    "chmod 777 ${sp.absolutePath}; " +
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
        sanitizeAllCpu(cfg)
        val root = RootChecker.isRootAvailable()
        cfg.templates[tpl.id] = tpl
        cfg.currentTemplateId = tpl.id
        save(cfg)
        val effectiveFreq = if (root) CpuUtil.resolveCpuFreq(tpl) else -1
        if (tpl.cpuFreq != -1) RootExecutor.writeCpuMaxFreq(effectiveFreq)
    }

    /** 切换模板前强制审查所有模板的 CPU 限制值：哨兵(小数倍率)自动解析为真实 KHz，非法值修复为安全值，防止危险频率落入内核。 */
    fun sanitizeAllCpu(cfg: AppConfig) {
        var changed = false
        for ((id, t) in cfg.templates) {
            val safe = when {
                t.cpuFreq == -1 -> -1
                t.cpuFreq == -2 -> CpuUtil.resolveCpuFreq(t)
                else -> CpuUtil.sanitize(t.cpuFreq.toLong())
            }
            if (safe != t.cpuFreq) {
                cfg.templates[id] = t.copy(cpuFreq = safe)
                changed = true
            }
        }
        if (changed) save(cfg)
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