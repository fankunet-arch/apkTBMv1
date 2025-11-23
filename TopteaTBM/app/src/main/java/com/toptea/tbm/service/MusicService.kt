package com.toptea.tbm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
    }

    private var player: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

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
        }

        // 3. 创建通知渠道 (Android 8.0+)
        createNotificationChannel()
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

            // A. 计算今天该播什么
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
                updateNotification("今日无排期 - 静默中")
                return@launch
            }

            Log.i(TAG, "Loaded Schedule: ${schedule.date} (Priority ${schedule.priority})")

            // B. 解析时间槽，提取歌单ID
            val type = object : TypeToken<List<TimeSlot>>() {}.type
            val slots: List<TimeSlot> = Gson().fromJson(schedule.timeSlotsJson, type)

            // 简化逻辑：暂时只取第一个时间段的歌单播放 (生产环境需做定时器切换)
            if (slots.isNotEmpty()) {
                val playlistId = slots[0].playlist_id

                // 这里我们需要查 Playlist 表，但为了简化，我们在 SyncManager 里并没有存 Playlist 表...
                // 修正：我们在 ApiModels 里定义了 Playlist，但在 DB 里没存。
                // 紧急修复逻辑：直接查所有 LocalSongs 并播放 (演示用)
                // 或者，如果您在 SyncManager 里把 Playlist 存了，就查 Playlist。
                // 鉴于我们目前的数据库结构 AppEntities 里没有 Playlist 表 (为了简化)
                // 我们假设：**所有已下载的歌，都循环播放** (MVP版本)

                val readySongs = dao.getAllReadySongs()
                if (readySongs.isNotEmpty()) {
                    Log.i(TAG, "Found ${readySongs.size} songs ready to play.")

                    withContext(Dispatchers.Main) {
                        // 在主线程操作播放器
                        player?.clearMediaItems()
                        readySongs.forEach { song ->
                            if (song.localPath != null && File(song.localPath).exists()) {
                                val item = MediaItem.fromUri(song.localPath)
                                player?.addMediaItem(item)
                            }
                        }
                        player?.prepare()
                        updateNotification("正在播放: ${readySongs.size} 首歌曲")
                    }
                } else {
                    Log.w(TAG, "No songs downloaded yet.")
                    updateNotification("正在下载歌曲...")
                }
            }
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
            .setSmallIcon(R.mipmap.ic_launcher) // 确保图标存在
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
        player?.release()
        wakeLock?.release()
        Log.d(TAG, "Service Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}