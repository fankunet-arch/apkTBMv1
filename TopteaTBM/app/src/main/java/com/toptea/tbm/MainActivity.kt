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
            val isFinished = intent?.getBooleanExtra("is_finished", false) ?: false
            updateDownloadProgress(completed, total, isFinished)
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

        // 0. ✅ 修复: 提前注册MAC更新接收器，确保能接收到首次生成的MAC
        registerMacUpdateReceiver()

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

    // ✅ 新增辅助函数: 注册MAC更新接收器
    private fun registerMacUpdateReceiver() {
        val macUpdateFilter = IntentFilter(SyncManager.ACTION_MAC_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(macUpdateReceiver, macUpdateFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(macUpdateReceiver, macUpdateFilter)
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
            val mac = dao.getConfig("device_mac")

            withContext(Dispatchers.Main) {
                if (mac != null) {
                    binding.tvMacId.text = "MAC: $mac"
                } else {
                    // ✅ 修复: 首次启动时显示"正在生成..."而非"Unknown"
                    binding.tvMacId.text = "MAC: 正在生成..."
                    LogUtils.send(applicationContext, "⏳ 等待生成设备ID...")
                }
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
            // ✅ 修复: 当songTitle是"等待播放..."时,不显示"正在播放:"前缀
            val displayText = if (songTitle == "等待播放...") {
                "🎵 $songTitle"
            } else {
                "🎵 正在播放: $songTitle"
            }
            binding.tvNowPlaying.text = displayText
        }
    }

    private fun updateDownloadProgress(completed: Int, total: Int, isFinished: Boolean) {
        runOnUiThread {
            if (total > 0) {
                // 1. 更新状态文本
                val progress = (completed * 100 / total).coerceIn(0, 100)
                binding.progressBarDownload.progress = progress

                // 2. 区分“进行中”与“结束”
                if (!isFinished) {
                    // [进行中]
                    binding.cardDownloadProgress.visibility = View.VISIBLE
                    binding.tvStatus.text = "🔄 正在下载: $completed/$total"
                    binding.tvDownloadProgressText.text = "正在同步资源: $completed/$total"
                } else {
                    // [已结束] - 强制执行结算逻辑
                    val statusText = if (completed == total) "✅ 下载完成" else "⚠️ 下载结束 ($completed/$total)"
                    binding.tvStatus.text = statusText
                    binding.tvDownloadProgressText.text = "同步结束"

                    // 延迟隐藏
                    mainScope.launch {
                        delay(3000)
                        binding.cardDownloadProgress.visibility = View.GONE
                        // 只有全部成功才恢复绿色，否则保留警告状态提醒运维
                        if (completed == total) {
                            binding.tvStatus.text = "🟢 服务运行中"
                        }
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
        val downloadProgressFilter = IntentFilter().apply {
            addAction(DownloadManager.ACTION_DOWNLOAD_PROGRESS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadProgressReceiver, downloadProgressFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadProgressReceiver, downloadProgressFilter)
        }

        // ✅ 修复: MAC 更新接收器已在onCreate()中注册，此处无需重复注册

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
            // ✅ 修复: MAC 更新接收器在onDestroy()中注销，此处无需注销
        } catch (e: Exception) {
            // 忽略重复注销错误
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        volumeCheckJob?.cancel()
        mainScope.cancel()

        // ✅ 修复: 在Activity销毁时注销MAC更新接收器
        try {
            unregisterReceiver(macUpdateReceiver)
        } catch (e: Exception) {
            // 忽略重复注销错误
        }
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
