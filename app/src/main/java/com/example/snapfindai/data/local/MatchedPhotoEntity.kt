package com.example.snapfindai.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One matched photo belonging to a [JobEntity]. Deleted automatically when its job is (CASCADE). */
@Entity(
    tableName = "matched_photos",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("jobId")],
)
data class MatchedPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    /** Absolute path into stable app storage (filesDir), not cache -- must survive as long as the app is installed. */
    val photoPath: String,
    val distance: Float,
    /** Non-null once saved to the device gallery -- when it happened. */
    val savedAt: Long? = null,
)
