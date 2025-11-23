package com.toptea.tbm

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * LogUtils - 日志防爆版本
 * 维护固定长度队列 (Max 100 lines)，防止长期运行 OOM
 */
object LogUtils {
    private const val MAX_LOG_LINES = 100
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun send(context: Context, message: String) {
        Log.d("TopteaLog", message) // 打印到 Logcat

        // 添加时间戳
        val time = dateFormat.format(Date())
        val logLine = "[$time] $message"

        // 维护固定长度队列
        logQueue.offer(logLine)
        while (logQueue.size > MAX_LOG_LINES) {
            logQueue.poll() // 移除最旧的日志
        }

        // 发送广播给界面
        val intent = Intent("com.toptea.tbm.LOG_UPDATE")
        intent.putExtra("log", logLine)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    /**
     * 获取所有日志（用于界面初始化）
     */
    fun getAllLogs(): String {
        return logQueue.reversed().joinToString("\n")
    }
}