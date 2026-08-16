package com.power.manager.core

object RootChecker {
    @Volatile
    private var checked = false
    @Volatile
    private var available = false

    fun isRootAvailable(): Boolean {
        if (!checked) {
            available = RootExecutor.isAvailable()
            checked = true
        }
        return available
    }
}