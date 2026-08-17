package com.power.manager.core

import android.net.Uri
import android.os.SystemClock
import com.power.manager.data.AppConfig
import com.power.manager.data.Template

/**
 * 配置读取通道：system_server 与各应用进程经 ContentProvider 读取模块 App 的配置。
 * 带短 TTL 缓存，避免高频调用触发大量 Binder 往返。
 */
object ConfigChannel {
    private const val TTL_MS = 3000L

    @Volatile
    private var cached: AppConfig? = null
    @Volatile
    private var cachedAt = 0L

    fun config(): AppConfig? {
        val now = SystemClock.elapsedRealtime()
        val c = cached
        if (c != null && now - cachedAt < TTL_MS) return c
        val fresh = query()
        if (fresh != null) {
            cached = fresh
            cachedAt = now
        }
        return cached
    }

    fun activeTemplate(): Template? {
        val cfg = config() ?: return null
        return cfg.templates[cfg.currentTemplateId] ?: cfg.templates[-3]
    }

    fun invalidate() {
        cached = null
    }

    private fun query(): AppConfig? {
        return try {
            val resolver = SysContext.contentResolver() ?: return null
            resolver.query(Uri.parse(Const.CONFIG_URI), null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    AppConfig.fromJson(c.getString(0))
                } else {
                    null
                }
            }
        } catch (e: Throwable) {
            null
        }
    }
}
