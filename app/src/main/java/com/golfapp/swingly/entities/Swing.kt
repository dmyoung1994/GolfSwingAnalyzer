package com.golfapp.swingly.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Swing (
    @PrimaryKey val swingId: String,
    @ColumnInfo(name = "swing_data") val swingData: String
)