package com.example.snapfindai.di

import com.example.snapfindai.data.repository.ModelProvisioningRepositoryImpl
import com.example.snapfindai.domain.repository.ModelProvisioningRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ModelProvisioningModule {

    @Binds
    @Singleton
    abstract fun bindModelProvisioningRepository(
        impl: ModelProvisioningRepositoryImpl,
    ): ModelProvisioningRepository
}
