package com.example.singfor10

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机 / 覆盖安装后自动续播。
 * 开始时间持久化在 SharedPreferences，重启后按真实经过时间计算剩余时长。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val valid = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!valid) return

        try {
            val prefs = context.getSharedPreferences(SingService.PREF_NAME, Context.MODE_PRIVATE)
            val startTime = prefs.getLong(SingService.PREF_START_TIME, 0L)
            if (startTime <= 0L) return

            val remaining = SingService.DURATION_MS - (System.currentTimeMillis() - startTime)
            if (remaining <= 0L) {
                prefs.edit().remove(SingService.PREF_START_TIME).apply()
                return
            }

            val serviceIntent = Intent(context.applicationContext, SingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i("BootReceiver", "开机续播，剩余 ${remaining / 1000}s")
        } catch (e: Exception) {
            Log.w("BootReceiver", "续播失败: ${e.message}")
        }
    }
}
