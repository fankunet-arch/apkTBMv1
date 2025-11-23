package com.toptea.tbm

import com.google.gson.annotations.SerializedName

// 1. 发送给服务器的请求
data class CheckUpdateRequest(
    val mac_address: String,
    val current_version: String
)

// 2. 服务器返回的总包裹
data class ApiResponse(
    val status: String,          // "latest", "update_required", "error"
    val new_version: String?,
    val config: FullConfig?      // 如果不需要更新，这里是 null
)

// 3. 核心配置大礼包
data class FullConfig(
    val resources: List<RemoteSong>,
    val playlists: Map<String, RemotePlaylist>, // Key是歌单ID，Value是详情
    val assignments: RemoteAssignments,
    val holiday_dates: List<String>
)

// --- 内部零件 ---

data class RemoteSong(
    val id: Int,
    @SerializedName("title") val title: String, // ✅ [新增] 接收人可读标题
    @SerializedName("md5") val md5: String,
    @SerializedName("url") val url: String,
    @SerializedName("size") val size: Long
)

data class RemotePlaylist(
    val mode: String, // "sequence" or "random"
    val ids: List<Int>
)

data class RemoteAssignments(
    val specials: Map<String, List<TimeSlot>>, // "2025-12-25" -> 策略
    val holidays: List<TimeSlot>?,
    val weekdays: Map<String, List<TimeSlot>>  // "1" -> 策略
)

data class TimeSlot(
    val start: String,
    val end: String,
    val playlist_id: Int
)