package com.toptea.tbm

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object DownloadManager {
    private const val TAG = "DownloadManager"
    private var isDownloading = false

    // 广播 Action 常量
    const val ACTION_SONG_READY = "com.toptea.tbm.ACTION_SONG_READY"
    const val ACTION_DOWNLOAD_PROGRESS = "com.toptea.tbm.ACTION_DOWNLOAD_PROGRESS"

    // 启动下载任务 (会被 SyncManager 调用)
    fun startDownload(context: Context) {
        if (isDownloading) return // 防止重复启动
        isDownloading = true

        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, ">>> Download Service Started")
            val dao = AppDatabase.getDatabase(context).appDao()
            val client = OkHttpClient()

            // 1. 找出所有没下载好的歌
            val pendingList = dao.getPendingSongs()
            val totalCount = pendingList.size
            Log.d(TAG, "Pending songs count: $totalCount")
            LogUtils.send(context, "开始下载: $totalCount 首歌曲")

            // 发送初始进度
            sendProgressBroadcast(context, 0, totalCount)

            var completedCount = 0

            for ((index, song) in pendingList.withIndex()) {
                try {
                    Log.d(TAG, "Downloading [${index + 1}/$totalCount]: ${song.title} (${song.downloadUrl})")

                    // 2. 准备文件路径 (存放在 App 私有目录/music 下)
                    val musicDir = File(context.getExternalFilesDir(null), "music")
                    if (!musicDir.exists()) musicDir.mkdirs()

                    val fileName = "${song.md5}.mp3" // 用 MD5 做文件名，防止乱码且天然去重
                    val file = File(musicDir, fileName)

                    // 3. 检查文件是否其实已经存在了 (断点续传的简化版：有文件且MD5对就不下了)
                    if (file.exists() && verifyMd5(file, song.md5)) {
                        Log.i(TAG, "File already exists and valid. Skipping.")
                        markAsReady(context, dao, song, file.absolutePath)
                        completedCount++
                        sendProgressBroadcast(context, completedCount, totalCount)
                        continue
                    }

                    // 4. 开始下载
                    val request = Request.Builder().url(song.downloadUrl).build()
                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download failed: Code ${response.code}")
                        continue
                    }

                    // 5. 写入文件流
                    val sink = FileOutputStream(file)
                    response.body?.byteStream()?.use { input ->
                        input.copyTo(sink)
                    }
                    sink.close()

                    // 6. 下载后再次校验 MD5，确保文件没坏
                    if (verifyMd5(file, song.md5)) {
                        Log.i(TAG, "Download success: ${file.name}")
                        markAsReady(context, dao, song, file.absolutePath)
                        completedCount++
                        sendProgressBroadcast(context, completedCount, totalCount)
                    } else {
                        Log.e(TAG, "MD5 mismatch! Deleting corrupted file.")
                        file.delete()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading song ${song.id}: ${e.message}")
                }
            }

            isDownloading = false

            // 发送最终进度 (包含完成标记)
            val finishIntent = Intent(ACTION_DOWNLOAD_PROGRESS)
            finishIntent.putExtra("completed", completedCount)
            finishIntent.putExtra("total", totalCount)
            finishIntent.putExtra("is_finished", true) // <--- 新增标记：无论由于成败，循环已结束
            context.sendBroadcast(finishIntent)

            if (completedCount < totalCount) {
                LogUtils.send(context, "⚠️ 下载部分完成: $completedCount/$totalCount 首歌曲 (${totalCount - completedCount} 首失败)")
            } else {
                LogUtils.send(context, "✅ 下载全部完成: $completedCount/$totalCount 首歌曲")
            }

            Log.d(TAG, "<<< Download Service Finished")
        }
    }

    // 辅助：更新数据库状态为"已就绪" + 发送广播
    private suspend fun markAsReady(context: Context, dao: AppDao, song: LocalSong, path: String) {
        val newSong = song.copy(localPath = path, status = 2) // status=2 代表 Ready
        dao.insertOrUpdateSong(newSong)

        // 关键：发送单曲就绪广播 (ACTION_SONG_READY)
        val intent = Intent(ACTION_SONG_READY)
        intent.putExtra("song_id", song.id)
        intent.putExtra("song_path", path)
        intent.putExtra("song_title", song.title)
        context.sendBroadcast(intent)

        Log.i(TAG, "✅ Song Ready Broadcast Sent: ${song.title}")
        LogUtils.send(context, "✅ 歌曲就绪: ${song.title}")
    }

    // 辅助：发送下载进度广播
    private fun sendProgressBroadcast(context: Context, completed: Int, total: Int) {
        val intent = Intent(ACTION_DOWNLOAD_PROGRESS)
        intent.putExtra("completed", completed)
        intent.putExtra("total", total)
        context.sendBroadcast(intent)
    }

    // 辅助：校验 MD5
    private fun verifyMd5(file: File, expectedMd5: String): Boolean {
        if (!file.exists()) return false

        // 严格校验：如果服务器没有提供有效的 MD5，拒绝下载
        if (expectedMd5.length != 32) {
            Log.e(TAG, "Security Alert: Invalid MD5 length provided!")
            return false
        }

        return try {
            val buffer = ByteArray(8192)
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actualMd5 = digest.digest().joinToString("") { "%02x".format(it) }
            actualMd5.equals(expectedMd5, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }
}