package com.example.snapfindai.di

import com.example.snapfindai.data.repository.JobHistoryRepositoryImpl
import com.example.snapfindai.domain.repository.JobHistoryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class JobHistoryModule {

    @Binds
    @Singleton
    abstract fun bindJobHistoryRepository(
        impl: JobHistoryRepositoryImpl,
    ): JobHistoryRepository
}
