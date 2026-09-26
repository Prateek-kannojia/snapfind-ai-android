package com.example.snapfindai.di

import com.example.snapfindai.data.repository.OnDeviceFaceMatchRepositoryImpl
import com.example.snapfindai.domain.repository.FaceMatchRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FaceMatchModule {

    @Binds
    @Singleton
    abstract fun bindFaceMatchRepository(
        impl: OnDeviceFaceMatchRepositoryImpl,
    ): FaceMatchRepository
}
