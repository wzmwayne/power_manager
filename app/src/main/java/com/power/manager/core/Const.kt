package com.power.manager.core

object Const {
    const val PACKAGE = "com.power.manager"
    const val AUTHORITY = "com.power.manager"
    const val CONFIG_URI = "content://com.power.manager/config"
    const val LOG_URI = "content://com.power.manager/log"
    /** 后台管理指令通道：query ?action=enter/check/exit&pkg=xxx。 */
    const val BG_URI = "content://com.power.manager/bg"
}
