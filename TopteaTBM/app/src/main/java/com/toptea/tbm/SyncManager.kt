package com.toptea.tbm

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

object SyncManager {
    private val gson = Gson()
    private var pollingJob: Job? = null

    // 广播 Action 常量
    const val ACTION_PLAYLIST_UPDATED = "com.toptea.tbm.ACTION_PLAYLIST_UPDATED"

    // 核心入口：执行一次完整的同步检查
    fun checkUpdate(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                LogUtils.send(context, ">>> Starting Sync Check...")

                // 1. 获取设备信息
                val db = AppDatabase.getDatabase(context)
                val dao = db.appDao()

                var mac = dao.getConfig("device_mac")
                if (mac == null) {
                    mac = UUID.randomUUID().toString()
                    dao.setConfig(AppConfig("device_mac", mac))
                    LogUtils.send(context, "Generated New Device ID: $mac")
                }

                val currentVer = dao.getConfig("strategy_version") ?: "0"

                // 2. 发起网络请求
                LogUtils.send(context, "Connecting to server... (MAC: ${mac?.take(8)}...)")
                val request = CheckUpdateRequest(mac!!, currentVer)

                val response = NetworkClient.apiService.checkUpdate(request)

                // 3. 处理响应
                when (response.status) {
                    "latest" -> {
                        LogUtils.send(context, "System is up to date.")
                    }
                    "update_required" -> {
                        LogUtils.send(context, "Update found! Ver: ${response.new_version}")
                        response.config?.let { config ->
                            processConfig(context, dao, config, response.new_version)
                        }
                    }
                    "error" -> {
                        LogUtils.send(context, "Server Error: ${response.status}") // 通常是未激活
                    }
                    else -> {
                        LogUtils.send(context, "Unknown Status: ${response.status}")
                    }
                }

                // 4. 启动 WDS
                WdsEngine.start()

            } catch (e: Exception) {
                LogUtils.send(context, "Sync Failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private suspend fun processConfig(context: Context, dao: AppDao, config: FullConfig, newVersion: String?) {
        // A. 处理歌曲
        var newCount = 0
        config.resources.forEach { remoteSong ->
            val local = dao.getSongById(remoteSong.id)
            if (local == null) {
                val newSong = LocalSong(
                    id = remoteSong.id,
                    title = "Song_${remoteSong.id}",
                    md5 = remoteSong.md5,
                    downloadUrl = remoteSong.url,
                    fileSize = remoteSong.size,
                    status = 0
                )
                dao.insertOrUpdateSong(newSong)
                newCount++
            }
        }
        if (newCount > 0) LogUtils.send(context, "Added $newCount new songs to download queue.")

        // B. 处理策略
        dao.clearAllSchedules()

        config.assignments.weekdays.forEach { (dayKey, slots) ->
            val schedule = PlaySchedule(
                date = "WEEKDAY_$dayKey",
                priority = 1,
                timeSlotsJson = gson.toJson(slots)
            )
            dao.insertSchedule(schedule)
        }

        if (!config.assignments.holidays.isNullOrEmpty()) {
            val holidayJson = gson.toJson(config.assignments.holidays)
            config.holiday_dates.forEach { dateStr ->
                dao.insertSchedule(PlaySchedule(
                    date = dateStr,
                    priority = 2,
                    timeSlotsJson = holidayJson
                ))
            }
        }

        config.assignments.specials.forEach { (dateStr, slots) ->
            dao.insertSchedule(PlaySchedule(
                date = dateStr,
                priority = 3,
                timeSlotsJson = gson.toJson(slots)
            ))
        }

        // C. 处理歌单 (Playlists)
        dao.clearAllPlaylists()
        var playlistCount = 0
        config.playlists.forEach { (playlistIdStr, remotePlaylist) ->
            val playlistId = playlistIdStr.toIntOrNull() ?: return@forEach
            val playlist = LocalPlaylist(
                id = playlistId,
                name = "Playlist_$playlistId",
                songIdsJson = gson.toJson(remotePlaylist.ids),
                playMode = remotePlaylist.mode
            )
            dao.insertOrUpdatePlaylist(playlist)
            playlistCount++
        }
        if (playlistCount > 0) LogUtils.send(context, "Loaded $playlistCount playlists.")

        // D. 更新版本
        if (newVersion != null) {
            dao.setConfig(AppConfig("strategy_version", newVersion))
            LogUtils.send(context, "Strategy updated to: $newVersion")
        }

        // E. 触发下载
        LogUtils.send(context, "Starting Download Manager...")
        DownloadManager.startDownload(context)

        // F. 发送热更广播 (通知播放器刷新)
        val intent = Intent(ACTION_PLAYLIST_UPDATED)
        context.sendBroadcast(intent)
        LogUtils.send(context, "Playlist update broadcast sent.")
    }

    /**
     * 启动心跳轮询机制 (30分钟一次)
     * 确保断网重连后能自动恢复
     */
    fun startPolling(context: Context) {
        // 先停止旧的 Job (防止重复启动)
        pollingJob?.cancel()

        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            LogUtils.send(context, "Polling service started. Interval: 30 min")
            while (true) {
                delay(30 * 60 * 1000L) // 30 分钟
                LogUtils.send(context, ">>> Auto Sync Triggered (Polling)")
                checkUpdate(context)
            }
        }
    }

    /**
     * 停止心跳轮询
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
}