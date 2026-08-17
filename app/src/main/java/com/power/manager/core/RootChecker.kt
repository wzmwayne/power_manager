package com.power.manager.core

object RootChecker {
    private const val TTL_MS = 30_000L

    @Volatile
    private var lastCheck = 0L
    @Volatile
    private var available = false

    fun isRootAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCheck > TTL_MS) {
            available = RootExecutor.isAvailable()
            lastCheck = now
        }
        return available
    }

    fun forceRefresh(): Boolean {
        available = RootExecutor.isAvailable()
        lastCheck = System.currentTimeMillis()
        return available
    }
}