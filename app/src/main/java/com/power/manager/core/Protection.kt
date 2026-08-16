package com.power.manager.core

object Protection {
    private val hardExempt = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.inputmethod.latin",
        "com.android.settings",
        "com.android.providers.settings",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.as",
        "com.google.android.googlequicksearchbox"
    )

    fun isProtected(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == ModuleFiles.PACKAGE) return true
        return hardExempt.contains(pkg)
    }
}