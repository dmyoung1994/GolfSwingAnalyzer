package com.golfapp.swingly.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.golfapp.swingly.entities.Swing

@Dao
interface SwingDao {
    @Query("Select * from swing where swingId = :videoName")
    fun findSwingDataByVideoName(videoName: String) : Swing

    @Insert
    fun saveSwingData(swing: Swing)
}