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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SnapFindDatabase =
        Room.databaseBuilder(context, SnapFindDatabase::class.java, "snapfind.db").build()

    @Provides
    fun provideJobDao(database: SnapFindDatabase): JobDao = database.jobDao()
}
