package com.eggbusinessmanager.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.eggbusinessmanager.data.db.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingDao {
    @Query("SELECT value FROM app_setting WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM app_setting WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun upsert(setting: AppSettingEntity)
}
