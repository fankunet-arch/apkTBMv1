package com.toptea.tbm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.toptea.tbm.service.MusicService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Boot completed! Starting MusicService...")
            // 启动播放服务
            val serviceIntent = Intent(context, MusicService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}