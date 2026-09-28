package com.example.snapfindai.di

import com.example.snapfindai.data.repository.MediaStoreGalleryRepositoryImpl
import com.example.snapfindai.domain.repository.PhotoGalleryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GalleryModule {

    @Binds
    @Singleton
    abstract fun bindPhotoGalleryRepository(
        impl: MediaStoreGalleryRepositoryImpl,
    ): PhotoGalleryRepository
}
