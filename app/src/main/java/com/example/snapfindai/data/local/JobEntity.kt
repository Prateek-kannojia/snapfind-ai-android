package com.example.snapfindai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One completed matching job. Deliberately shaped for a full multi-job
 * history (own primary key, own timestamp) even though only one row is
 * kept today -- see JobHistoryRepositoryImpl for why.
 */
@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val threshold: Float,
)
