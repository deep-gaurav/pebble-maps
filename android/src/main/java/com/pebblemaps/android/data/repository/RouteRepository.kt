package com.pebblemaps.android.data.repository

import com.pebblemaps.android.data.remote.OsrmApi
import com.pebblemaps.android.domain.model.Route
import com.pebblemaps.android.domain.model.RouteProfile
import org.osmdroid.util.GeoPoint

interface RouteRepository {
    suspend fun getRoute(origin: GeoPoint, destination: GeoPoint, profile: RouteProfile): Route
}

class RouteRepositoryImpl(
    private val osrmApi: OsrmApi
) : RouteRepository {

    override suspend fun getRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: RouteProfile
    ): Route {
        return osrmApi.getRoute(origin, destination, profile)
    }
}