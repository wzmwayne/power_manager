package com.power.manager.ui

import com.power.manager.core.AppLog

/**
 * 后台处决裁决（模块 App 进程内，经 ContentProvider 与 system_server/各应用进程通讯）：
 * - 收到处决命令（insert /bg action=kill）：登记死刑标记并 notifyChange(/bg)，
 *   触发目标应用进程内观察者查询并自杀。
 * - 应用进程检查（query /bg action=check）：命中死刑标记则应答 kill=true（应用自杀）。
 * 后台队列与超时计时由 system_server 侧 BackgroundKeeper 承载（本类不持有全局队列）。
 */
object BackgroundManager {

    private val lock = Any()
    private val killNow = mutableSetOf<String>()

    /** system_server 下发处决命令。 */
    fun onKillCommand(pkg: String, from: String) {
        synchronized(lock) {
            killNow.add(pkg)
        }
        AppLog.i("处决命令登记（来源=" + from + "）：" + pkg + "，通知对应应用自杀")
        AppStore.notifyBgChanged()
        AppLog.i("已广播处决通知（notifyChange /bg）")
    }

    /** 应用进程查询处决状态：命中返回 true（该应用应立即自杀）。 */
    fun onCheck(pkg: String, from: String): Boolean = synchronized(lock) {
        if (killNow.remove(pkg)) {
            AppLog.i("处决确认应答（来源=" + from + "）：" + pkg + " -> kill=true，应用将自杀")
            true
        } else {
            AppLog.d("处决检查应答（来源=" + from + "）：" + pkg + " -> kill=false")
            false
        }
    }
}
