package com.power.manager.core

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.provider.Settings
import android.util.Log

object ApiExecutor {
    private const val TAG = "PowerManager"

    fun systemContext(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null)
            at.javaClass.getMethod("getSystemContext").invoke(at) as? Context
        } catch (e: Throwable) {
            null
        }
    }

    fun forceStop(pkg: String): Boolean {
        return try {
            val am = Class.forName("android.app.ActivityManagerNative")
                .getMethod("getDefault").invoke(null)
            val iface = Class.forName("android.app.IActivityManager")
            val m = iface.getMethod("forceStopPackage", String::class.java, Integer.TYPE)
            val r = m.invoke(am, pkg, 0) as? Boolean ?: true
            r
        } catch (e: Throwable) {
            Log.w(TAG, "forceStop API 失败", e)
            false
        }
    }

    fun disableBluetooth(): Boolean {
        return try {
            val a = BluetoothAdapter.getDefaultAdapter() ?: return false
            a.disable()
        } catch (e: Throwable) {
            false
        }
    }

    fun enableBluetooth(): Boolean {
        return try {
            val a = BluetoothAdapter.getDefaultAdapter() ?: return false
            a.enable()
        } catch (e: Throwable) {
            false
        }
    }

    fun isBluetoothOn(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
        } catch (e: Throwable) {
            false
        }
    }

    fun setAnimationScales(scale: Float): Boolean {
        return try {
            val ctx = systemContext() ?: return false
            val cr = ctx.contentResolver
            Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
            Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
            Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, scale)
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun setRefreshRate(fps: Int): Boolean {
        return try {
            val ctx = systemContext() ?: return false
            val cr = ctx.contentResolver
            Settings.System.putInt(cr, "peak_refresh_rate", fps)
            Settings.System.putInt(cr, "min_refresh_rate", fps)
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun restrictUidBackground(uid: Int, restrict: Boolean): Boolean {
        return try {
            val ctx = systemContext() ?: return false
            val cls = Class.forName("android.net.NetworkPolicyManager")
            val from = cls.getMethod("from", Context::class.java)
            val npm = from.invoke(null, ctx)
            val setUid = cls.getMethod("setUidPolicy", Integer.TYPE, Integer.TYPE)
            setUid.invoke(npm, uid, if (restrict) 4 else 0)
            true
        } catch (e: Throwable) {
            false
        }
    }
}