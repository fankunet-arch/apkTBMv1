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
    const val ACTION_MAC_UPDATED = "com.toptea.tbm.ACTION_MAC_UPDATED"

    // 应用状态枚举 (用于动态心跳)
    enum class AppState {
        STABLE,      // 稳定模式 - 30分钟心跳
        DOWNLOADING, // 下载模式 - 1分钟心跳
        IDLE         // 空闲模式
    }

    // 当前应用状态
    private var currentState: AppState = AppState.STABLE

    // 心跳间隔常量 (毫秒)
    private const val HEARTBEAT_STABLE = 30 * 60 * 1000L  // 30分钟
    private const val HEARTBEAT_FAST = 1 * 60 * 1000L     // 1分钟

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
                    LogUtils.send(context, "✅ Generated New Device ID: $mac")

                    // 发送 MAC 更新广播 (修复首次启动显示问题)
                    val macIntent = Intent(ACTION_MAC_UPDATED)
                    macIntent.putExtra("device_mac", mac)
                    context.sendBroadcast(macIntent)
                }

                val currentVer = dao.getConfig("strategy_version") ?: "0"

                // 2. 发起网络请求
                LogUtils.send(context, "📡 Connecting to API...")
                LogUtils.send(context, "URL: https://hqv3.toptea.es/smsys/api/check_update")
                LogUtils.send(context, "MAC: ${mac?.take(12)}...")
                LogUtils.send(context, "Version: $currentVer")

                val request = CheckUpdateRequest(mac!!, currentVer)

                val response = NetworkClient.apiService.checkUpdate(request)

                // 3. 处理响应
                LogUtils.send(context, "✅ API Response: ${response.status}")

                when (response.status) {
                    "latest" -> {
                        LogUtils.send(context, "✅ System is up to date.")
                    }
                    "update_required" -> {
                        LogUtils.send(context, "🔄 Update found! Ver: ${response.new_version}")
                        response.config?.let { config ->
                            processConfig(context, dao, config, response.new_version)
                        }
                    }
                    "error" -> {
                        LogUtils.send(context, "❌ Server Error: ${response.status}") // 通常是未激活
                    }
                    else -> {
                        LogUtils.send(context, "⚠️ Unknown Status: ${response.status}")
                    }
                }

                // 4. 启动 WDS
                WdsEngine.start()

            } catch (e: Exception) {
                // ✅ 改进错误日志，显示详细的错误类型和消息
                val errorType = e.javaClass.simpleName
                val errorMsg = e.message ?: "Unknown error"

                LogUtils.send(context, "❌ Sync Failed!")
                LogUtils.send(context, "Error Type: $errorType")
                LogUtils.send(context, "Error: $errorMsg")

                // 🔍 添加详细的网络诊断信息
                when {
                    errorType.contains("UnknownHost") -> {
                        LogUtils.send(context, "⚠️ DNS解析失败!")
                        LogUtils.send(context, "无法解析域名: hqv3.toptea.es")
                        LogUtils.send(context, "请检查网络连接或DNS设置")
                        LogUtils.send(context, "提示: 需要访问HTTPS服务")
                    }
                    errorType.contains("SocketTimeout") || errorType.contains("Timeout") -> {
                        LogUtils.send(context, "⚠️ 网络超时!")
                        LogUtils.send(context, "无法在30秒内连接到服务器")
                        LogUtils.send(context, "请检查网络状态或防火墙设置")
                    }
                    errorType.contains("ConnectException") -> {
                        LogUtils.send(context, "⚠️ 连接被拒绝!")
                        LogUtils.send(context, "服务器可能未运行或端口被阻止")
                    }
                    errorType.contains("JsonSyntax") || errorType.contains("JsonParse") -> {
                        LogUtils.send(context, "⚠️ Server returned invalid JSON!")
                        LogUtils.send(context, "服务器可能返回了HTML错误页面")
                        LogUtils.send(context, "Please check server API endpoint.")
                    }
                    errorType.contains("SSLException") || errorType.contains("Certificate") -> {
                        LogUtils.send(context, "⚠️ SSL证书错误!")
                        LogUtils.send(context, "请检查HTTPS配置")
                    }
                    else -> {
                        LogUtils.send(context, "⚠️ 未知错误类型")
                        LogUtils.send(context, "请查看详细日志")
                    }
                }

                // 打印完整堆栈跟踪到Logcat
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
                    title = remoteSong.title, // ✅ [修复点] 使用 API 返回的真实标题
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

        // 检查是否有待下载的歌曲，切换到快速心跳模式
        val db = AppDatabase.getDatabase(context)
        val pendingCount = db.appDao().getPendingSongs().size

        if (pendingCount > 0) {
            // ✅ 修复: 切换状态即可,轮询会在下次循环时自动使用新间隔
            currentState = AppState.DOWNLOADING
            LogUtils.send(context, "⚡ 切换到快速心跳模式 (${pendingCount}首待下载)")
        }

        DownloadManager.startDownload(context)

        // F. 发送热更广播 (通知播放器刷新)
        val intent = Intent(ACTION_PLAYLIST_UPDATED)
        context.sendBroadcast(intent)
        LogUtils.send(context, "Playlist update broadcast sent.")
    }

    /**
     * 启动心跳轮询机制 (动态心跳调度)
     * 确保断网重连后能自动恢复
     */
    fun startPolling(context: Context) {
        // 先停止旧的 Job (防止重复启动)
        pollingJob?.cancel()

        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            val intervalName = if (currentState == AppState.DOWNLOADING) "1 min" else "30 min"
            LogUtils.send(context, "Polling service started. Interval: $intervalName")

            while (true) {
                // ✅ 修复: 每次循环都动态计算心跳间隔,无需重启协程
                val heartbeatInterval = calculateHeartbeatInterval(context)

                val intervalMinutes = heartbeatInterval / (60 * 1000)
                LogUtils.send(context, "Next heartbeat: $intervalMinutes min")

                delay(heartbeatInterval)
                LogUtils.send(context, ">>> Auto Sync Triggered (Polling)")
                checkUpdate(context)

                // ✅ 修复: 检查并切换状态,但不重启协程(下次循环会使用新间隔)
                checkAndSwitchState(context)
            }
        }
    }

    /**
     * 重启轮询 (用于应用新的心跳间隔)
     */
    private fun restartPolling(context: Context) {
        startPolling(context)
    }

    /**
     * 计算心跳间隔
     */
    private fun calculateHeartbeatInterval(context: Context): Long {
        return when (currentState) {
            AppState.DOWNLOADING -> HEARTBEAT_FAST      // 1分钟
            AppState.STABLE -> HEARTBEAT_STABLE         // 30分钟
            AppState.IDLE -> HEARTBEAT_STABLE           // 30分钟
        }
    }

    /**
     * 检查并切换状态
     */
    private suspend fun checkAndSwitchState(context: Context) {
        if (currentState == AppState.DOWNLOADING) {
            val db = AppDatabase.getDatabase(context)
            val pendingCount = db.appDao().getPendingSongs().size

            if (pendingCount == 0) {
                // ✅ 修复: 切换状态后不重启协程,下次循环会自动使用新间隔
                currentState = AppState.STABLE
                LogUtils.send(context, "✅ 下载完成，切换到稳定心跳模式 (30 min)")
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