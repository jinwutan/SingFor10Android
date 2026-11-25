package com.example.singfor10

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.ceil

/**
 * 前台常驻服务：负责播放、倒计时、音量/静音强制恢复、开机后续播。
 */
class SingService : Service() {

    companion object {
        const val ACTION_TICK = "com.example.singfor10.TICK"
        const val ACTION_FINISHED = "com.example.singfor10.FINISHED"
        const val EXTRA_REMAINING = "remaining"
        const val PREF_NAME = "singfor10"
        const val PREF_START_TIME = "start_time"
        const val DURATION_MS = 600_000L
        const val CHANNEL_ID = "singfor10_channel"
        const val NOTIF_ID = 1001
        private const val TAG = "SingService"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var prefs: SharedPreferences? = null
    private val handler = Handler(Looper.getMainLooper())

    private var enforceRunnable: Runnable? = null
    private var tickRunnable: Runnable? = null
    private var receiversRegistered = false

    /**
     * 监听屏幕亮灭、耳机拔出、以及系统隐藏的音量变化广播。
     * 一旦收到事件立刻把音量/铃声拉回最大并恢复播放。
     */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            enforceAudioState()
            ensurePlaying()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var startTime = prefs?.getLong(PREF_START_TIME, 0L) ?: 0L
        if (startTime <= 0L) {
            startTime = System.currentTimeMillis()
            prefs?.edit()?.putLong(PREF_START_TIME, startTime)?.apply()
        }

        val remaining = remainingOf(startTime)
        if (remaining <= 0L) {
            finishAll()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification(remaining))
        acquireWakeLock()
        ensurePlaying()
        startEnforcer()
        startTicker(startTime)
        registerStateReceivers()

        Log.i(TAG, "服务启动，剩余 ${remaining / 1000}s")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterStateReceivers()
        releaseWakeLock()
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    /**
     * 用户从最近任务里划掉 App 时重新拉起服务（stopWithTask=false 的兜底）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val startTime = prefs?.getLong(PREF_START_TIME, 0L) ?: 0L
        if (startTime > 0L && remainingOf(startTime) > 0L) {
            restartSelf()
        }
    }

    // ------------------------------------------------------------------
    // 播放
    // ------------------------------------------------------------------

    private fun ensurePlaying() {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer.create(this, R.raw.sing)?.apply {
                    isLooping = true
                    setVolume(1f, 1f)
                }
            }
            if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
                mediaPlayer!!.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "播放恢复失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 音量 / 静音守卫
    // ------------------------------------------------------------------

    private fun enforceAudioState() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current < max) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            }
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < max) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                }
            }
            mediaPlayer?.setVolume(1f, 1f)
        } catch (e: Exception) {
            Log.w(TAG, "音量守卫失败: ${e.message}")
        }

        // 若用户已授予“勿扰权限”，顺手把勿扰模式关掉
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                nm.isNotificationPolicyAccessGranted
            ) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (e: Exception) {
            Log.w(TAG, "勿扰关闭跳过: ${e.message}")
        }
    }

    private fun startEnforcer() {
        if (enforceRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                enforceAudioState()
                handler.postDelayed(this, 300L)
            }
        }
        enforceRunnable = runnable
        handler.post(runnable)
    }

    private fun registerStateReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_DEVICES_CHANGED_ACTION")
        }
        try {
            registerReceiver(stateReceiver, filter)
            receiversRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "注册状态接收器失败: ${e.message}")
        }
    }

    private fun unregisterStateReceivers() {
        if (!receiversRegistered) return
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        receiversRegistered = false
    }

    // ------------------------------------------------------------------
    // 倒计时
    // ------------------------------------------------------------------

    private fun remainingOf(startTime: Long): Long =
        (DURATION_MS - (System.currentTimeMillis() - startTime)).coerceAtLeast(0L)

    private fun startTicker(startTime: Long) {
        if (tickRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                val remaining = remainingOf(startTime)
                sendTick(remaining)
                updateNotification(remaining)
                if (remaining <= 0L) {
                    finishAll()
                } else {
                    handler.postDelayed(this, 250L)
                }
            }
        }
        tickRunnable = runnable
        handler.post(runnable)
    }

    private fun sendTick(remaining: Long) {
        sendBroadcast(Intent(ACTION_TICK).apply {
            putExtra(EXTRA_REMAINING, remaining)
            setPackage(packageName)
        })
    }

    private fun finishAll() {
        sendTick(0L)
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName))
        prefs?.edit()?.remove(PREF_START_TIME)?.apply()
        handler.removeCallbacksAndMessages(null)
        enforceRunnable = null
        tickRunnable = null
        unregisterStateReceivers()
        releaseWakeLock()
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ------------------------------------------------------------------
    // 通知与唤醒锁
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "唱歌服务",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "保持唱歌 10 分钟不中断"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(remaining: Long): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val seconds = ceil(remaining / 1000.0).toInt()
        val text = String.format("剩余 %02d:%02d · 静音/关机都拦不住", seconds / 60, seconds % 60)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("🎵 唱歌进行中")
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(remaining: Long) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(remaining))
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "singfor10:playback")
        wakeLock?.acquire(11 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun restartSelf() {
        try {
            val intent = Intent(applicationContext, SingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "重启服务失败: ${e.message}")
        }
    }
}
