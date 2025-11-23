package com.toptea.tbm

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 1. 本地歌曲表
 * 对应服务器的 sm_songs，但增加了 localPath (本地存储路径)
 */
@Entity(tableName = "local_songs")
data class LocalSong(
    @PrimaryKey val id: Int,       // 对应服务器 song_id
    val title: String,
    val md5: String,               // 用于校验文件完整性
    val downloadUrl: String,       // 下载链接
    val localPath: String? = null, // 下载成功后的本地路径 (为空代表未下载)
    val fileSize: Long = 0,
    val duration: Int = 0,
    val status: Int = 0            // 0=未下载, 1=下载中, 2=已就绪
)

/**
 * 2. 播放策略表 (简化版)
 * 我们把服务器复杂的关联表，简化为本地的一张宽表，方便查询
 * 存储：今天、明天、未来的每天该播什么
 */
@Entity(tableName = "play_schedules")
data class PlaySchedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: String,              // 日期 "2025-11-23"
    val priority: Int,             // 优先级 (3=特例, 2=节日, 1=周循环)
    val timeSlotsJson: String      // 核心：存 JSON 字符串，包含具体的 [09:00-12:00: SongIDs...]
)

/**
 * 3. 歌单详情表 (LocalPlaylist)
 * 存储歌单的播放模式和歌曲ID列表，支持精准播放
 */
@Entity(tableName = "local_playlists")
data class LocalPlaylist(
    @PrimaryKey val id: Int,           // 歌单ID (对应服务器的 playlist_id)
    val name: String,                  // 歌单名称 (可选，用于调试)
    val songIdsJson: String,           // 歌曲ID列表，JSON 格式 "[1,2,3]"
    val playMode: String               // 播放模式: "sequence" 或 "random"
)

/**
 * 4. 全局配置表
 * 存储 MAC 地址、当前策略版本号等
 */
@Entity(tableName = "app_config")
data class AppConfig(
    @ColumnInfo(name = "config_key") // 避开 SQL 保留关键字
    @PrimaryKey val key: String,
    val value: String
)