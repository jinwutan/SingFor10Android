package com.example.singfor10

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    private lateinit var countdownText: TextView
    private lateinit var titleText: TextView
    private lateinit var hintText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var exitButton: Button

    private var receiverRegistered = false
    private var finished = false

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                SingService.ACTION_TICK -> {
                    val remaining = intent.getLongExtra(SingService.EXTRA_REMAINING, 0L)
                    updateUi(remaining)
                }
                SingService.ACTION_FINISHED -> showFinished()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        countdownText = findViewById(R.id.countdownText)
        titleText = findViewById(R.id.titleText)
        hintText = findViewById(R.id.hintText)
        progressBar = findViewById(R.id.progressBar)
        exitButton = findViewById(R.id.exitButton)

        progressBar.max = 1000
        exitButton.setOnClickListener {
            Toast.makeText(this, "再等等～ 还没唱完呢 😈", Toast.LENGTH_SHORT).show()
        }

        requestNeededPermissions()
        ensureStartTime()
        startSingService()
    }

    override fun onStart() {
        super.onStart()
        registerTickReceiver()
        if (!finished) {
            val prefs = getSharedPreferences(SingService.PREF_NAME, Context.MODE_PRIVATE)
            val start = prefs.getLong(SingService.PREF_START_TIME, 0L)
            if (start > 0L) {
                val remaining = SingService.DURATION_MS - (System.currentTimeMillis() - start)
                if (remaining <= 0L) showFinished() else updateUi(remaining)
            }
        }
    }

    override fun onStop() {
        unregisterTickReceiver()
        super.onStop()
    }

    private fun requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION
                )
            }
        }
    }

    private fun ensureStartTime() {
        val prefs = getSharedPreferences(SingService.PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(SingService.PREF_START_TIME, 0L) <= 0L) {
            prefs.edit()
                .putLong(SingService.PREF_START_TIME, System.currentTimeMillis())
                .apply()
        }
    }

    private fun startSingService() {
        val intent = Intent(this, SingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "服务启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun registerTickReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(SingService.ACTION_TICK)
            addAction(SingService.ACTION_FINISHED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(tickReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(tickReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterTickReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(tickReceiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }

    private fun updateUi(remaining: Long) {
        if (finished) return
        val seconds = ceil(remaining / 1000.0).toInt().coerceAtLeast(0)
        countdownText.text = String.format("%02d:%02d", seconds / 60, seconds % 60)
        val elapsed = SingService.DURATION_MS - remaining
        progressBar.progress =
            ((elapsed.toDouble() / SingService.DURATION_MS) * 1000).toInt().coerceIn(0, 1000)
    }

    private fun showFinished() {
        finished = true
        countdownText.text = "00:00"
        countdownText.setTextColor(getColor(android.R.color.holo_green_light))
        titleText.text = "✅ 唱完了！"
        hintText.text = "坚持了整整 10 分钟 🎉"
        exitButton.isEnabled = false
    }

    companion object {
        private const val REQ_NOTIFICATION = 100
    }
}
