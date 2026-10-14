package com.example.snapfindai.di

import com.example.snapfindai.data.remote.SnapFindApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // IMPORTANT: 192.168.0.110 is the laptop's local Wi-Fi IP for the physical device to access the backend
    private const val BASE_URL = "http://192.168.0.110:8000/"

    @Provides
    @Named("baseUrl")
    fun provideBaseUrl(): String = BASE_URL

    @Provides
    @Singleton
    fun provideRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideSnapFindApi(retrofit: Retrofit): SnapFindApi {
        return retrofit.create(SnapFindApi::class.java)
    }
}
