package com.power.manager.core

object ScopeGuard {
    private val allowed = setOf(
        "android",
        "com.android.providers.settings",
        "com.android.phone"
    )

    fun isAllowed(pkg: String): Boolean = allowed.contains(pkg)

    fun allowedScopes(): List<String> = allowed.toList()
}