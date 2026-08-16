package com.power.manager.hook

object CurrentApp {
    @Volatile
    var foreground: String? = null
}