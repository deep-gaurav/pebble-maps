package com.pebblemaps.android.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pebblemaps.android.ui.map.MapScreen
import com.pebblemaps.android.ui.navigation.ActiveNavigationScreen
import com.pebblemaps.android.ui.navigation.NavigationScreen
import com.pebblemaps.android.ui.preview.WatchPreviewScreen

@Composable
fun PebbleMapsNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "map"
    ) {
        composable("map") {
            MapScreen(
                onNavigateToNavigation = {
                    navController.navigate("navigation")
                },
                onNavigateToPreview = {
                    navController.navigate("preview")
                }
            )
        }
        composable("navigation") {
            NavigationScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToPreview = {
                    navController.navigate("preview")
                },
                onStartNavigation = {
                    navController.navigate("activeNavigation")
                }
            )
        }
        composable("preview") {
            WatchPreviewScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable("activeNavigation") {
            ActiveNavigationScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}