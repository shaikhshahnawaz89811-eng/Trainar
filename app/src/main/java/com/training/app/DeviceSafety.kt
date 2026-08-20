package com.training.app

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** Conservative device guard: stops new work before Android reaches severe/critical thermal state. */
object DeviceSafety {
    fun shouldPauseTraining(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(PowerManager::class.java)
            if (power != null && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return true
        }
        val battery = context.getSystemService(BatteryManager::class.java)
        val capacity = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return capacity in 0..9
    }

    fun thermalLabel(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Unavailable"
        return when (context.getSystemService(PowerManager::class.java)?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Safe"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe — training paused"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical — training paused"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency — training paused"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown — training paused"
            else -> "Unavailable"
        }
    }

    fun batteryPercent(context: Context): String {
        val value = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (value in 0..100) "$value%" else "Unavailable"
    }
}
