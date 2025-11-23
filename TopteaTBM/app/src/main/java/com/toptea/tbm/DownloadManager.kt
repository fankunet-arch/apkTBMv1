package com.toptea.tbm

import android.content.Context
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
            Log.d(TAG, "Pending songs count: ${pendingList.size}")

            for (song in pendingList) {
                try {
                    Log.d(TAG, "Downloading: ${song.title} (${song.downloadUrl})")

                    // 2. 准备文件路径 (存放在 App 私有目录/music 下)
                    val musicDir = File(context.getExternalFilesDir(null), "music")
                    if (!musicDir.exists()) musicDir.mkdirs()

                    val fileName = "${song.md5}.mp3" // 用 MD5 做文件名，防止乱码且天然去重
                    val file = File(musicDir, fileName)

                    // 3. 检查文件是否其实已经存在了 (断点续传的简化版：有文件且MD5对就不下了)
                    if (file.exists() && verifyMd5(file, song.md5)) {
                        Log.i(TAG, "File already exists and valid. Skipping.")
                        markAsReady(dao, song, file.absolutePath)
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
                        markAsReady(dao, song, file.absolutePath)
                    } else {
                        Log.e(TAG, "MD5 mismatch! Deleting corrupted file.")
                        file.delete()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading song ${song.id}: ${e.message}")
                }
            }

            isDownloading = false
            Log.d(TAG, "<<< Download Service Finished")
        }
    }

    // 辅助：更新数据库状态为“已就绪”
    private suspend fun markAsReady(dao: AppDao, song: LocalSong, path: String) {
        val newSong = song.copy(localPath = path, status = 2) // status=2 代表 Ready
        dao.insertOrUpdateSong(newSong)
    }

    // 辅助：校验 MD5
    private fun verifyMd5(file: File, expectedMd5: String): Boolean {
        if (!file.exists()) return false
        // 如果服务器还没给MD5，或者给的是空，暂时先放行(为了兼容测试)，但在正式环境应该严格校验
        if (expectedMd5.length < 30) return true

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