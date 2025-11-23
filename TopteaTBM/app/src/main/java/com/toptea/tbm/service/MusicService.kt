package com.toptea.tbm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.toptea.tbm.AppDatabase
import com.toptea.tbm.DownloadManager
import com.toptea.tbm.LogUtils
import com.toptea.tbm.MainActivity
import com.toptea.tbm.R // 确保 R 引用正确
import com.toptea.tbm.SyncManager
import com.toptea.tbm.TimeSlot
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MusicService : Service() {

    companion object {
        const val TAG = "MusicService"
        const val CHANNEL_ID = "TopteaMusicChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_RELOAD = "com.toptea.tbm.RELOAD"
        const val ACTION_NOW_PLAYING = "com.toptea.tbm.ACTION_NOW_PLAYING"
        const val ACTION_KILL_SWITCH = "com.toptea.tbm.ACTION_KILL_SWITCH"
    }

    private var player: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // 追踪当前播放模式 (sequence/random)
    private var currentPlayMode: String = "sequence"
    // 追踪播放队列是否为空 (用于冷启动优化)
    private var isPlaylistEmpty: Boolean = true

    // 精准停播守卫 (Precision Stop Watchdog)
    private var stopWatchdogJob: Job? = null

    // 热重载广播接收器
    private val playlistUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received playlist update broadcast - reloading music")
            LogUtils.send(applicationContext, "Hot Reload: Playlist updated, refreshing...")
            loadAndPlayMusic()
        }
    }

    // 紧急熔断广播接收器
    private val killSwitchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.w(TAG, "KILL SWITCH ACTIVATED - Device Blocked")
            LogUtils.send(applicationContext, "⚠️ KILL SWITCH: Device blocked, stopping playback")
            player?.stop()
            player?.clearMediaItems()
            updateNotification("设备已被阻止 (Device Blocked)")
        }
    }

    // 单曲就绪广播接收器 (边下边播核心)
    private val songReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val songId = intent?.getIntExtra("song_id", -1) ?: return
            val songPath = intent.getStringExtra("song_path") ?: return
            val songTitle = intent.getStringExtra("song_title") ?: "Unknown"

            Log.d(TAG, "Song Ready Received: $songTitle (ID: $songId)")
            LogUtils.send(applicationContext, "🎵 新歌就绪: $songTitle")

            // 动态插入播放队列
            serviceScope.launch(Dispatchers.Main) {
                val mediaItem = MediaItem.fromUri(songPath)

                if (isPlaylistEmpty) {
                    // 冷启动优化：第一首歌立即播放
                    player?.addMediaItem(mediaItem)
                    player?.prepare()
                    isPlaylistEmpty = false
                    Log.i(TAG, "✨ Cold Start: First song ready, playback started!")
                    LogUtils.send(applicationContext, "✨ 首曲启动: $songTitle")
                    updateNotification("正在播放: $songTitle")
                } else {
                    // 后续歌曲：插入队列末尾
                    if (currentPlayMode == "random") {
                        // 随机模式：插入随机位置
                        val randomIndex = (0 until (player?.mediaItemCount ?: 0) + 1).random()
                        player?.addMediaItem(randomIndex, mediaItem)
                    } else {
                        // 顺序模式：插入末尾
                        player?.addMediaItem(mediaItem)
                    }
                    Log.i(TAG, "Added to playlist: $songTitle")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service Created")

        // 1. 获取唤醒锁 (防止熄屏后 CPU 休眠)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Toptea:MusicWakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 锁12小时，防万一

        // 2. 初始化播放器
        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL // 列表循环
            playWhenReady = true // 准备好就自动播

            // 添加播放器监听器 - 实现状态上报
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    mediaItem?.let {
                        // 提取歌曲标题 (从 URI 路径提取文件名)
                        val songTitle = it.localConfiguration?.uri?.lastPathSegment ?: "Unknown"
                        Log.d(TAG, "Now Playing: $songTitle")

                        // 发送状态上报广播
                        val intent = Intent(ACTION_NOW_PLAYING)
                        intent.putExtra("song_title", songTitle)
                        sendBroadcast(intent)

                        LogUtils.send(applicationContext, "▶️ Now Playing: $songTitle")
                    }
                }
            })
        }

        // 3. 创建通知渠道 (Android 8.0+)
        createNotificationChannel()

        // 4. 注册广播接收器 - 热重载 (Android 14+ 需要指定 EXPORTED 标志)
        val playlistFilter = IntentFilter(SyncManager.ACTION_PLAYLIST_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playlistUpdateReceiver, playlistFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(playlistUpdateReceiver, playlistFilter)
        }

        // 5. 注册紧急熔断接收器
        val killSwitchFilter = IntentFilter(ACTION_KILL_SWITCH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(killSwitchReceiver, killSwitchFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(killSwitchReceiver, killSwitchFilter)
        }

        // 6. 注册单曲就绪接收器 (边下边播核心)
        val songReadyFilter = IntentFilter(DownloadManager.ACTION_SONG_READY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(songReadyReceiver, songReadyFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(songReadyReceiver, songReadyFilter)
        }

        // 7. 启动心跳轮询
        SyncManager.startPolling(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Received Action ${intent?.action}")

        // 4. 启动前台服务 (必须在5秒内调用，否则Crash)
        val notification = createNotification("Toptea BGM 服务运行中")
        startForeground(NOTIFICATION_ID, notification)

        // 5. 触发业务逻辑
        // 每次启动服务，都尝试加载今天的歌单
        loadAndPlayMusic()

        // 顺便检查一下更新
        SyncManager.checkUpdate(this)

        return START_STICKY // 如果被杀，系统会自动重启服务
    }

    private fun loadAndPlayMusic() {
        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val dao = db.appDao()

            // A. 计算今天该播什么 (PlaySchedule)
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            var schedule = dao.getScheduleByDate(todayStr) // 1. 先查特例/节日

            if (schedule == null) {
                // 2. 没命中，查周循环 (WEEKDAY_1 ~ WEEKDAY_7)
                val cal = Calendar.getInstance()
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // Sun=1, Mon=2...
                // 转换一下: Sun=1 -> 7, Mon=2 -> 1
                val weekdayIndex = if (dayOfWeek == 1) 7 else dayOfWeek - 1
                schedule = dao.getScheduleByDate("WEEKDAY_$weekdayIndex")
            }

            if (schedule == null) {
                Log.w(TAG, "No schedule found for today.")
                LogUtils.send(applicationContext, "今日无排期 - 静默中")
                updateNotification("今日无排期 - 静默中")
                return@launch
            }

            Log.i(TAG, "Loaded Schedule: ${schedule.date} (Priority ${schedule.priority})")
            LogUtils.send(applicationContext, "Loaded schedule: ${schedule.date}")

            // B. 解析时间槽，提取歌单ID (TimeSlot)
            val type = object : TypeToken<List<TimeSlot>>() {}.type
            val slots: List<TimeSlot> = Gson().fromJson(schedule.timeSlotsJson, type)

            // 简化逻辑：暂时只取第一个时间段的歌单播放 (生产环境需做定时器切换)
            if (slots.isEmpty()) {
                LogUtils.send(applicationContext, "No time slots configured.")
                return@launch
            }

            val currentSlot = slots[0]
            val playlistId = currentSlot.playlist_id
            Log.i(TAG, "Target Playlist ID: $playlistId")

            // 🔥 精准停播守卫：计算距离结束时间的毫秒差
            setupStopWatchdog(currentSlot.end)

            // C. 查询歌单详情 (LocalPlaylist)
            val playlist = dao.getPlaylistById(playlistId)
            if (playlist == null) {
                Log.w(TAG, "Playlist $playlistId not found in DB")
                LogUtils.send(applicationContext, "歌单 #$playlistId 未找到，等待同步...")
                updateNotification("等待同步歌单...")
                return@launch
            }

            Log.i(TAG, "Loaded Playlist: ${playlist.name} (Mode: ${playlist.playMode})")
            LogUtils.send(applicationContext, "Playlist: ${playlist.name} (${playlist.playMode})")

            // 保存当前播放模式
            currentPlayMode = playlist.playMode

            // D. 解析歌曲ID列表
            val songIdsType = object : TypeToken<List<Int>>() {}.type
            val songIds: List<Int> = Gson().fromJson(playlist.songIdsJson, songIdsType)

            if (songIds.isEmpty()) {
                LogUtils.send(applicationContext, "Playlist is empty.")
                return@launch
            }

            // E. 精准查询歌曲 (LocalSong)
            val songs = songIds.mapNotNull { songId ->
                dao.getSongById(songId)
            }.filter { song ->
                song.status == 2 && song.localPath != null && File(song.localPath).exists()
            }

            if (songs.isEmpty()) {
                Log.w(TAG, "No songs ready for playback yet.")
                LogUtils.send(applicationContext, "歌曲下载中...")
                updateNotification("正在下载歌曲...")
                return@launch
            }

            Log.i(TAG, "Found ${songs.size}/${songIds.size} songs ready to play.")
            LogUtils.send(applicationContext, "Loaded ${songs.size} songs for playback")

            // F. 根据播放模式处理歌曲列表
            val playbackList = if (playlist.playMode == "random") {
                songs.shuffled()
            } else {
                songs // sequence
            }

            // G. 加载到播放器 (ExoPlayer)
            withContext(Dispatchers.Main) {
                player?.clearMediaItems()
                playbackList.forEach { song ->
                    val item = MediaItem.fromUri(song.localPath!!)
                    player?.addMediaItem(item)
                }

                if (playbackList.isNotEmpty()) {
                    player?.prepare()
                    isPlaylistEmpty = false
                    LogUtils.send(applicationContext, "✅ Playback started: ${songs.size} songs")
                    updateNotification("正在播放: ${playlist.name} (${songs.size} 首)")
                } else {
                    isPlaylistEmpty = true
                    LogUtils.send(applicationContext, "等待歌曲下载...")
                    updateNotification("等待歌曲下载...")
                }
            }
        }
    }

    /**
     * 精准停播守卫 (Precision Stop Watchdog)
     * 在指定的结束时间自动停止播放，并触发策略检查
     */
    private fun setupStopWatchdog(endTimeStr: String) {
        // 取消旧的守卫任务
        stopWatchdogJob?.cancel()

        try {
            // 解析结束时间 (格式: "HH:mm" 例如 "22:00")
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val currentTime = Calendar.getInstance()

            // 构造今天的结束时间点
            val endTimeParts = endTimeStr.split(":")
            val endHour = endTimeParts[0].toInt()
            val endMinute = endTimeParts[1].toInt()

            val endTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, endHour)
                set(Calendar.MINUTE, endMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // 如果结束时间已经过了，说明是明天的时间段（或者已经结束）
            val deltaMillis = endTime.timeInMillis - currentTime.timeInMillis

            if (deltaMillis <= 0) {
                Log.w(TAG, "End time already passed or invalid: $endTimeStr")
                LogUtils.send(applicationContext, "⏰ 当前时段已结束")
                return
            }

            Log.i(TAG, "Stop Watchdog armed: will stop in ${deltaMillis / 1000}s (at $endTimeStr)")
            LogUtils.send(applicationContext, "⏰ 停播定时器已设置: $endTimeStr")

            // 启动定时任务
            stopWatchdogJob = serviceScope.launch {
                delay(deltaMillis)

                // 时间到！执行停播
                withContext(Dispatchers.Main) {
                    Log.w(TAG, "🛑 Stop Watchdog triggered! Stopping playback at $endTimeStr")
                    LogUtils.send(applicationContext, "🛑 播放时段结束 ($endTimeStr)")

                    player?.stop()
                    updateNotification("播放已停止 (时段结束)")
                }

                // 立即检查更新，看看是否有后续时段
                Log.i(TAG, "Checking for next time slot...")
                LogUtils.send(applicationContext, ">>> 检查后续播放计划...")
                SyncManager.checkUpdate(applicationContext)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup Stop Watchdog: ${e.message}")
            LogUtils.send(applicationContext, "⚠️ 停播定时器设置失败: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Music Service",
                NotificationManager.IMPORTANCE_LOW // Low 不会发出声音打扰
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Toptea SoundMatrix")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 使用前景色矢量图
            .setContentIntent(pendingIntent)
            .setOngoing(true) // 禁止划掉
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销广播接收器
        try {
            unregisterReceiver(playlistUpdateReceiver)
            unregisterReceiver(killSwitchReceiver)
            unregisterReceiver(songReadyReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers: ${e.message}")
        }
        // 停止心跳轮询
        SyncManager.stopPolling()
        // 取消精准停播守卫
        stopWatchdogJob?.cancel()
        // 取消协程作用域
        serviceScope.cancel()
        // 释放资源
        player?.release()
        wakeLock?.release()
        Log.d(TAG, "Service Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}