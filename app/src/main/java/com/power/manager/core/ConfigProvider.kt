package com.power.manager.core

import com.power.manager.data.AppConfig
import com.power.manager.data.Template
import de.robv.android.xposed.XSharedPreferences
import java.io.File

object ConfigProvider {
    @Volatile
    private var config: AppConfig? = null
    private var prefs: XSharedPreferences? = null

    fun load() {
        try {
            val p = XSharedPreferences(ModuleFiles.PACKAGE, "config")
            p.makeWorldReadable()
            p.reload()
            prefs = p
        } catch (e: Throwable) {
            prefs = null
        }
        reload()
    }

    fun reload() {
        val raw = prefs?.getString("config", null) ?: readDirectly()
        config = raw?.let { AppConfig.fromJson(it) } ?: AppConfig.default()
        if (EmergencyGuard.isFallback()) {
            config = config?.let { it.copy(currentTemplateId = -3) }
        }
    }

    fun config(): AppConfig? = config

    fun activeTemplate(): Template? {
        val c = config ?: return null
        return c.templates[c.currentTemplateId] ?: c.templates[-3]
    }

    fun readDirectly(): String? {
        return try {
            val f = File(ModuleFiles.prefsFile())
            if (!f.exists()) return null
            val text = f.readText()
            val startTag = "<string name=\"config\">"
            val start = text.indexOf(startTag)
            if (start < 0) return null
            val vStart = start + startTag.length
            val end = text.indexOf("</string>", vStart)
            if (end < 0) return null
            unescapeXml(text.substring(vStart, end))
        } catch (e: Throwable) {
            null
        }
    }

    private fun unescapeXml(s: String): String {
        return s.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}