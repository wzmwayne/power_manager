package com.power.manager.core

import com.power.manager.util.LogUtil

enum class ExecMode { API, ROOT, FAILED }

enum class Action(val key: String) {
    FORCE_STOP("force_stop"),
    FPS("fps"),
    ANIM("anim"),
    BLUETOOTH("bluetooth"),
    NETWORK("network"),
    CPU("cpu"),
    BRIGHTNESS("brightness"),
    BATTERY_SAVER("battery_saver")
}

object StrategyExecutor {
    fun execute(action: Action, api: () -> Boolean, root: () -> Boolean): ExecMode {
        if (!CircuitBreaker.shouldBypass(action.key)) {
            if (api()) {
                CircuitBreaker.recordSuccess(action.key)
                LogUtil.d("${action.key} 主管线(API)成功")
                return ExecMode.API
            }
            CircuitBreaker.recordFailure(action.key)
            LogUtil.i("${action.key} 主管线(API)失败，降级备分管线(Root)")
        } else {
            LogUtil.i("${action.key} 不稳定(连续失败)，直走备分管线(Root)")
        }
        return if (root()) {
            LogUtil.d("${action.key} 备分管线(Root)成功")
            ExecMode.ROOT
        } else {
            LogUtil.w("${action.key} 备分管线(Root)失败")
            ExecMode.FAILED
        }
    }

    fun forceStop(pkg: String): ExecMode {
        if (Protection.isProtected(pkg)) {
            LogUtil.w("force-stop 拒绝：$pkg 受保护")
            return ExecMode.FAILED
        }
        return execute(
            Action.FORCE_STOP,
            { ApiExecutor.forceStop(pkg) },
            { RootExecutor.run("am force-stop $pkg") }
        )
    }

    fun setFps(fps: Int): ExecMode = execute(
        Action.FPS,
        { ApiExecutor.setRefreshRate(fps) },
        { RootExecutor.run("settings put system peak_refresh_rate $fps; settings put system min_refresh_rate $fps") }
    )

    fun resetFps(): ExecMode = execute(
        Action.FPS,
        { ApiExecutor.setRefreshRate(0) },
        { RootExecutor.run("settings put system peak_refresh_rate 0; settings put system min_refresh_rate 0") }
    )

    fun disableAnimation(): ExecMode = execute(
        Action.ANIM,
        { ApiExecutor.setAnimationScales(0f) },
        {
            RootExecutor.run(
                "settings put global animator_duration_scale 0; settings put global transition_animation_scale 0; settings put global window_animation_scale 0"
            )
        }
    )

    fun enableAnimation(): ExecMode = execute(
        Action.ANIM,
        { ApiExecutor.setAnimationScales(1f) },
        {
            RootExecutor.run(
                "settings put global animator_duration_scale 1; settings put global transition_animation_scale 1; settings put global window_animation_scale 1"
            )
        }
    )

    fun disableBluetooth(): ExecMode = execute(
        Action.BLUETOOTH,
        { ApiExecutor.disableBluetooth() },
        { RootExecutor.run("svc bluetooth disable") }
    )

    fun enableBluetooth(): ExecMode = execute(
        Action.BLUETOOTH,
        { ApiExecutor.enableBluetooth() },
        { RootExecutor.run("svc bluetooth enable") }
    )

    fun restrictUid(uid: Int, restrict: Boolean): ExecMode = execute(
        Action.NETWORK,
        { ApiExecutor.restrictUidBackground(uid, restrict) },
        { LogUtil.w("后台网络限制不降级 Root，放弃（防误伤系统网络）"); false }
    )

    fun setCpuFreq(freqKHz: Int): ExecMode = execute(
        Action.CPU,
        { LogUtil.w("CPU 频率无 API 主管线，直接走 Root"); false },
        { RootExecutor.writeCpuMaxFreq(freqKHz) }
    )

    fun setBatterySaver(enabled: Boolean): ExecMode = execute(
        Action.BATTERY_SAVER,
        { ApiExecutor.setBatterySaver(enabled) },
        { RootExecutor.run("settings put global low_power ${if (enabled) 1 else 0}") }
    )
}