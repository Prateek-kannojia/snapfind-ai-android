package com.example.snapfindai.di

import android.content.Context
import androidx.room.Room
import com.example.snapfindai.data.local.JobDao
import com.example.snapfindai.data.local.SnapFindDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Deliberately no `fallbackToDestructiveMigration()`. It's the usual
     * reflex for "Room is complaining about a schema change", and here it
     * would silently delete the user's entire job history -- which is the
     * only thing this database stores and the reason it exists. Without it, a
     * missing migration throws on open: loud in development, where it's a
     * two-line fix, instead of quiet in production where it's data loss.
     *
     * Hand-written migrations go in `addMigrations(...)` here. Additive
     * changes don't need one -- they're declared as `autoMigrations` on
     * [SnapFindDatabase] instead.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SnapFindDatabase =
        Room.databaseBuilder(context, SnapFindDatabase::class.java, "snapfind.db")
            .build()

    @Provides
    fun provideJobDao(database: SnapFindDatabase): JobDao = database.jobDao()
}
