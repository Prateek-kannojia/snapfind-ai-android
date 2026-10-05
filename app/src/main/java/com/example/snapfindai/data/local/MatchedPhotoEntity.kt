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
    indices = [
        Index("jobId"),
        // Defence in depth for the one failure that corrupts the grid
        // permanently: a job completing twice (process killed between its
        // photos being written and its status being set) would insert a
        // second row for the same path, and the results grid keys its items
        // by path -- duplicate keys throw. The write is transactional now, so
        // this should be unreachable; it's here so it can't come back.
        Index(value = ["jobId", "photoPath"], unique = true),
    ],
)
data class MatchedPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    /** Absolute path into stable app storage (filesDir), not cache -- must survive as long as the app is installed. */
    val photoPath: String,
    val distance: Float,
    /** Non-null once saved to the device gallery -- when it happened. */
    val savedAt: Long? = null,
    /** The gallery filename this photo maps to, derived from its contents when the job was persisted. */
    val galleryName: String,
)
