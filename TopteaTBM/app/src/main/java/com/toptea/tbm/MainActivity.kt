package com.toptea.tbm

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.toptea.tbm.databinding.ActivityMainBinding
import com.toptea.tbm.service.MusicService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private var volumeCheckJob: Job? = null

    // 日志广播接收器
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: return
            appendLog(log)
        }
    }

    // Now Playing 广播接收器
    private val nowPlayingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val songTitle = intent?.getStringExtra("song_title") ?: "Unknown"
            updateNowPlaying(songTitle)
        }
    }

    // 下载进度广播接收器
    private val downloadProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val completed = intent?.getIntExtra("completed", 0) ?: 0
            val total = intent?.getIntExtra("total", 0) ?: 0
            updateDownloadProgress(completed, total)
        }
    }

    // MAC 地址更新广播接收器 (修复首次启动竞态条件)
    private val macUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val mac = intent?.getStringExtra("device_mac") ?: return
            runOnUiThread {
                binding.tvMacId.text = "MAC: $mac"
                LogUtils.send(applicationContext, "✅ MAC 地址已刷新: $mac")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 启动前台服务
        startMusicService()

        // 2. 初始化界面
        initUI()

        // 3. 加载 MAC ID
        loadMacId()

        // 4. 启动音量哨兵
        startVolumeSentinel()

        // 5. 手动同步按钮点击事件
        binding.btnManualSync.setOnClickListener {
            LogUtils.send(this, ">>> Manual Sync Triggered by User")
            Toast.makeText(this, "正在连接总部...", Toast.LENGTH_SHORT).show()
            SyncManager.checkUpdate(this)
        }

        // 6. MAC ID 长按复制
        binding.tvMacId.setOnLongClickListener {
            val macText = binding.tvMacId.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("MAC ID", macText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "MAC ID 已复制", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun initUI() {
        binding.tvStatus.text = "🟢 服务运行中"
        binding.tvNowPlaying.text = "🎵 等待播放..."

        // 初始化日志显示
        val existingLogs = LogUtils.getAllLogs()
        if (existingLogs.isNotEmpty()) {
            binding.tvLogs.text = existingLogs
        } else {
            binding.tvLogs.text = "Waiting for events..."
        }

        LogUtils.send(this, "Dark Matrix Terminal Initialized.")
    }

    private fun loadMacId() {
        mainScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val dao = db.appDao()
            val mac = dao.getConfig("device_mac") ?: "Unknown"

            withContext(Dispatchers.Main) {
                binding.tvMacId.text = "MAC: $mac"
            }
        }
    }

    private fun startMusicService() {
        try {
            val intent = Intent(this, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            LogUtils.send(this, "Music Service Start Command Sent.")
        } catch (e: Exception) {
            LogUtils.send(this, "Error starting service: ${e.message}")
        }
    }

    /**
     * 音量哨兵 - 定时检测系统音量
     * 每 30 秒检测一次 STREAM_MUSIC 音量，若为 0 则显示红色警告
     */
    private fun startVolumeSentinel() {
        volumeCheckJob?.cancel()
        volumeCheckJob = mainScope.launch {
            while (isActive) {
                checkVolume()
                delay(30_000L) // 30 秒检测一次
            }
        }
    }

    private fun checkVolume() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        if (currentVolume == 0) {
            // 显示警告
            binding.cardVolumeWarning.visibility = View.VISIBLE
            LogUtils.send(this, "⚠️ Volume Warning: Device is muted!")
        } else {
            // 隐藏警告
            binding.cardVolumeWarning.visibility = View.GONE
            // 只在音量恢复时记录一次
            if (binding.cardVolumeWarning.visibility == View.VISIBLE) {
                LogUtils.send(this, "Volume OK: $currentVolume/$maxVolume")
            }
        }
    }

    private fun updateNowPlaying(songTitle: String) {
        runOnUiThread {
            binding.tvNowPlaying.text = "🎵 正在播放: $songTitle"
        }
    }

    private fun updateDownloadProgress(completed: Int, total: Int) {
        runOnUiThread {
            if (total > 0) {
                // 更新状态文本
                if (completed < total) {
                    binding.tvStatus.text = "🔄 正在下载: $completed/$total"
                } else {
                    binding.tvStatus.text = "✅ 下载完成"
                }

                // 显示进度卡片
                binding.cardDownloadProgress.visibility = View.VISIBLE
                binding.tvDownloadProgressText.text = "正在同步资源: $completed/$total"

                val progress = (completed * 100 / total).coerceIn(0, 100)
                binding.progressBarDownload.progress = progress

                // 下载完成后隐藏并恢复状态
                if (completed >= total) {
                    mainScope.launch {
                        delay(2000) // 2秒后隐藏
                        binding.cardDownloadProgress.visibility = View.GONE
                        binding.tvStatus.text = "🟢 服务运行中"
                    }
                }
            } else {
                binding.cardDownloadProgress.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 注册日志接收器
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logReceiver, IntentFilter("com.toptea.tbm.LOG_UPDATE")
        )

        // 注册 Now Playing 接收器 (Android 14+ 需要指定 EXPORTED 标志)
        val nowPlayingFilter = IntentFilter(MusicService.ACTION_NOW_PLAYING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nowPlayingReceiver, nowPlayingFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(nowPlayingReceiver, nowPlayingFilter)
        }

        // 注册下载进度接收器
        val downloadProgressFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadProgressReceiver, downloadProgressFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadProgressReceiver, downloadProgressFilter)
        }

        // 注册 MAC 更新接收器
        val macUpdateFilter = IntentFilter(SyncManager.ACTION_MAC_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(macUpdateReceiver, macUpdateFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(macUpdateReceiver, macUpdateFilter)
        }

        // 立即检测一次音量
        checkVolume()

        // 查询当前播放状态 (修复 Activity 重建后的状态不同步)
        val queryIntent = Intent(MusicService.ACTION_QUERY_STATUS)
        sendBroadcast(queryIntent)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
        try {
            unregisterReceiver(nowPlayingReceiver)
            unregisterReceiver(downloadProgressReceiver)
            unregisterReceiver(macUpdateReceiver)
        } catch (e: Exception) {
            // 忽略重复注销错误
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        volumeCheckJob?.cancel()
        mainScope.cancel()
    }

    private fun appendLog(text: String) {
        runOnUiThread {
            val currentText = binding.tvLogs.text.toString()
            val newLog = if (currentText == "Waiting for events...") {
                text
            } else {
                "$text\n$currentText" // 新日志在最上面
            }
            binding.tvLogs.text = newLog
        }
    }
}
