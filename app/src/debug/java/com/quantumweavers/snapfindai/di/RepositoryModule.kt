package com.quantumweavers.snapfindai.di

import com.quantumweavers.snapfindai.data.repository.JobRepositoryImpl
import com.quantumweavers.snapfindai.domain.repository.JobRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindJobRepository(
        jobRepositoryImpl: JobRepositoryImpl
    ): JobRepository
}
