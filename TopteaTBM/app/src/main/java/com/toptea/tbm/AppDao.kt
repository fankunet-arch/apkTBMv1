package com.toptea.tbm

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppDao {
    // --- 歌曲操作 ---
    @Query("SELECT * FROM local_songs WHERE id = :id")
    suspend fun getSongById(id: Int): LocalSong?

    @Query("SELECT * FROM local_songs WHERE md5 = :md5")
    suspend fun getSongByMd5(md5: String): LocalSong?

    @Query("SELECT * FROM local_songs WHERE status = 2") // 查所有已下载(Ready)的歌
    suspend fun getAllReadySongs(): List<LocalSong>

    @Query("SELECT * FROM local_songs WHERE status != 2") // 【新增】查所有未完成的歌
    suspend fun getPendingSongs(): List<LocalSong>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSong(song: LocalSong)

    @Query("DELETE FROM local_songs WHERE id NOT IN (:validIds)")
    suspend fun deleteObsoleteSongs(validIds: List<Int>) // 清理旧歌

    // --- 策略操作 ---
    @Query("SELECT * FROM play_schedules WHERE date = :date ORDER BY priority DESC LIMIT 1")
    suspend fun getScheduleByDate(date: String): PlaySchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: PlaySchedule)

    @Query("DELETE FROM play_schedules")
    suspend fun clearAllSchedules()

    // --- 歌单操作 (LocalPlaylist) ---
    @Query("SELECT * FROM local_playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Int): LocalPlaylist?

    @Query("SELECT * FROM local_playlists")
    suspend fun getAllPlaylists(): List<LocalPlaylist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePlaylist(playlist: LocalPlaylist)

    @Query("DELETE FROM local_playlists")
    suspend fun clearAllPlaylists()

    // --- 配置操作 ---
    @Query("SELECT value FROM app_config WHERE config_key = :key")
    suspend fun getConfig(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setConfig(config: AppConfig)
}