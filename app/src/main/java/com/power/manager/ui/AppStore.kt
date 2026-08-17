package com.power.manager.ui

import android.content.Context
import android.content.SharedPreferences
import com.power.manager.data.AppConfig
import com.power.manager.data.Template

object AppStore {
    private const val PREFS = "config"
    private const val KEY = "config"
    private const val KEY_CONSENT = "consent"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isConsented(): Boolean = prefs.getBoolean(KEY_CONSENT, false)

    fun setConsented() {
        prefs.edit().putBoolean(KEY_CONSENT, true).commit()
    }

    /** 每次都解析全新实例，保证调用方持有独立对象，写操作后可整体替换触发重组。 */
    fun load(): AppConfig {
        val raw = prefs.getString(KEY, null)
        return raw?.let { AppConfig.fromJson(it) } ?: AppConfig.default()
    }

    fun save(cfg: AppConfig) {
        prefs.edit().putString(KEY, cfg.toJson()).commit()
    }

    /** 深拷贝：可变容器全部换新实例，避免就地修改状态对象导致 Compose 不重组。 */
    fun copyOf(cfg: AppConfig): AppConfig = cfg.copy(
        templates = HashMap(cfg.templates),
        rules = HashMap(cfg.rules)
    )

    fun nextTemplateId(cfg: AppConfig): Int {
        var max = -1
        for (id in cfg.templates.keys) if (id >= 0 && id > max) max = id
        return max + 1
    }

    fun createTemplate(cfg: AppConfig, source: Template, name: String): Template {
        val id = nextTemplateId(cfg)
        return source.copyWith(id, name)
    }

    /** 应用模板：写入模板并激活。策略执行由 system_server 侧经 ConfigChannel 读取配置完成。 */
    fun applyTemplate(cfg: AppConfig, tpl: Template) {
        cfg.templates[tpl.id] = tpl
        cfg.currentTemplateId = tpl.id
        save(cfg)
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
}
