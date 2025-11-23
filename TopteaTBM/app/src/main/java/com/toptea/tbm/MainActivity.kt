package com.toptea.tbm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.toptea.tbm.databinding.ActivityMainBinding
import com.toptea.tbm.service.MusicService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: return
            appendLog(log)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 启动前台服务
        startMusicService()

        // 2. 初始化界面状态
        binding.tvStatus.text = "服务已启动 (Service Started)"
        appendLog("App Started. UI Initialized.")

        // 3. 添加一个临时的手动测试按钮 (直接添加到布局最下方)
        // 如果您不想改 XML，这里用代码动态加一个按钮也行，或者利用现有的点击事件
        // 这里我们简单一点：点击顶部的 "Toptea SoundMatrix" 标题触发同步
        binding.cardStatus.setOnClickListener {
            appendLog(">>> Manual Sync Triggered by User")
            SyncManager.checkUpdate(this)
        }
        appendLog("Tip: Tap the top card to force sync.")
    }

    private fun startMusicService() {
        try {
            val intent = Intent(this, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            appendLog("Service Start Command Sent.")
        } catch (e: Exception) {
            appendLog("Error starting service: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logReceiver, IntentFilter("com.toptea.tbm.LOG_UPDATE")
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
    }

    private fun appendLog(text: String) {
        // 确保在主线程更新 UI
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val currentText = binding.tvLogs.text.toString()
            val newLog = "[$time] $text\n$currentText" // 新日志在最上面
            binding.tvLogs.text = newLog
        }
    }
}