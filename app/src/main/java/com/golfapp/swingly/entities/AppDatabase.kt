package com.golfapp.swingly.entities

import androidx.room.Database
import androidx.room.RoomDatabase
import com.golfapp.swingly.dao.SwingDao

@Database(entities = [Swing::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun swingDao(): SwingDao
}