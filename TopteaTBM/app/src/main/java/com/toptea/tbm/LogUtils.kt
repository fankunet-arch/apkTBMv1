package com.toptea.tbm

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

object LogUtils {
    fun send(context: Context, message: String) {
        Log.d("TopteaLog", message) // 打印到 Logcat

        // 发送广播给界面
        val intent = Intent("com.toptea.tbm.LOG_UPDATE")
        intent.putExtra("log", message)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }
}