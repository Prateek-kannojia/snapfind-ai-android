package com.example.snapfindai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One completed matching job. Every job gets its own row -- see JobHistoryRepositoryImpl. */
@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val threshold: Float,
)
