package com.pebblemaps.android.di

import com.pebblemaps.android.data.remote.OsrmApi
import com.pebblemaps.android.data.repository.RouteRepository
import com.pebblemaps.android.data.repository.RouteRepositoryImpl
import com.pebblemaps.android.data.pebble.PebbleWatchManager
import com.pebblemaps.android.ui.map.MapViewModel
import com.pebblemaps.android.ui.navigation.NavigationViewModel
import com.pebblemaps.android.ui.preview.WatchPreviewViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import kotlinx.serialization.json.Json

val appModule = module {
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    single {
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(get())
            }
        }
    }

    single { OsrmApi(get()) }
    single<RouteRepository> { RouteRepositoryImpl(get()) }

    viewModel { MapViewModel(get()) }
    single { NavigationViewModel(get()) }
    viewModel { WatchPreviewViewModel() }
    single { PebbleWatchManager(get()) }
}