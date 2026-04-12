package com.pebblemaps.android.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pebblemaps.android.ui.map.MapScreen
import com.pebblemaps.android.ui.navigation.ActiveNavigationScreen
import com.pebblemaps.android.ui.navigation.NavigationViewModel
import com.pebblemaps.android.ui.navigation.NavigationScreen
import com.pebblemaps.android.ui.preview.WatchPreviewScreen
import org.koin.androidx.compose.koinViewModel

@Composable
fun PebbleMapsNavHost(
    pendingShareUrl: String? = null,
    onShareUrlConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navigationViewModel: NavigationViewModel = koinViewModel()

    LaunchedEffect(pendingShareUrl) {
        val url = pendingShareUrl ?: return@LaunchedEffect
        onShareUrlConsumed()
        Log.d("PebbleMapsNavHost", "Handing off share URL: $url")
        navigationViewModel.processSharedUrl(url)
    }

    LaunchedEffect(Unit) {
        navigationViewModel.navigationEvents.collect { destination ->
            navController.navigate(destination) {
                popUpTo("map") { inclusive = false }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "map"
    ) {
        composable("map") {
            MapScreen()
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
