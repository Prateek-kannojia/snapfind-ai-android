package com.example.snapfindai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [JobEntity::class, MatchedPhotoEntity::class], version = 1, exportSchema = false)
abstract class SnapFindDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
}
