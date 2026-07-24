package com.carijajan.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.carijajan.app.data.remote.AuthApi
import com.carijajan.app.data.repository.ListingRepository
import com.carijajan.app.ui.auth.BuyerLoginScreen
import com.carijajan.app.ui.auth.LoginScreen
import com.carijajan.app.ui.buyer.BuyerListScreen
import com.carijajan.app.ui.buyer.BuyerMapScreen
import com.carijajan.app.ui.buyer.BuyerViewModel
import com.carijajan.app.ui.buyer.ListingDetailScreen
import com.carijajan.app.ui.seller.EditListingScreen
import com.carijajan.app.ui.seller.SellerDashboardScreen
import com.carijajan.app.ui.seller.UploadPhotoScreen

sealed class Screen(val route: String, val title: String? = null) {
    object BuyerMap : Screen("buyer_map", "Peta Pembeli")
    object BuyerList : Screen("buyer_list", "Daftar Pembeli")
    object BuyerDetail : Screen("buyer_detail/{listingId}") {
        fun createRoute(listingId: String) = "buyer_detail/$listingId"
    }
    object SellerDashboard : Screen("seller_dashboard", "Dashboard Penjual")
    object SellerUploadPhoto : Screen("seller_upload_photo/{listingId}") {
        fun createRoute(listingId: String) = "seller_upload_photo/$listingId"
    }
    object SellerEditListing : Screen("seller_edit_listing/{listingId}") {
        fun createRoute(listingId: String) = "seller_edit_listing/$listingId"
    }
    object Auth : Screen("auth", "Masuk Penjual")
    object BuyerLogin : Screen("buyer_login", "Masuk Pembeli")
}

@Composable
fun CariJajanApp(
    authApi: AuthApi = AuthApi(),
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    val repository = remember { ListingRepository(context) }
    val buyerViewModel: BuyerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Show BottomNavigation only for main tabs
    val showBottomBar = currentRoute in listOf(Screen.BuyerMap.route, Screen.BuyerList.route, Screen.SellerDashboard.route)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Screen.BuyerMap.route,
                        onClick = { navController.navigate(Screen.BuyerMap.route) },
                        icon = { Icon(Icons.Default.Map, contentDescription = "Peta Pembeli") },
                        label = { Text("Peta") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.SellerDashboard.route,
                        onClick = {
                            if (authApi.isLoggedIn()) {
                                navController.navigate(Screen.SellerDashboard.route)
                            } else {
                                navController.navigate(Screen.Auth.route)
                            }
                        },
                        icon = { Icon(Icons.Default.Store, contentDescription = "Area Penjual") },
                        label = { Text("Penjual") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.BuyerMap.route,
            modifier = Modifier.padding(padding)
        ) {
            // ── Buyer Screens ──────────────────────────────────────────────
            composable(Screen.BuyerMap.route) {
                BuyerMapScreen(
                    viewModel = buyerViewModel,
                    onNavigateToList = { navController.navigate(Screen.BuyerList.route) },
                    onSelectListing = { listingId ->
                        navController.navigate(Screen.BuyerDetail.createRoute(listingId))
                    }
                )
            }

            composable(Screen.BuyerList.route) {
                BuyerListScreen(
                    viewModel = buyerViewModel,
                    onNavigateToMap = { navController.navigate(Screen.BuyerMap.route) },
                    onSelectListing = { listingId ->
                        navController.navigate(Screen.BuyerDetail.createRoute(listingId))
                    }
                )
            }

            composable(Screen.BuyerDetail.route) { backStackEntry ->
                val listingId = backStackEntry.arguments?.getString("listingId") ?: ""
                ListingDetailScreen(
                    listingId = listingId,
                    repository = repository,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.BuyerLogin.route) {
                BuyerLoginScreen(
                    onSuccess = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() }
                )
            }

            // ── Seller Screens ──────────────────────────────────────────────
            composable(Screen.Auth.route) {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Screen.SellerDashboard.route) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                        }
                    },
                    onContinueAsBuyer = {
                        navController.navigate(Screen.BuyerMap.route)
                    }
                )
            }

            composable(Screen.SellerDashboard.route) {
                SellerDashboardScreen(
                    onUploadPhoto = { listingId ->
                        navController.navigate(Screen.SellerUploadPhoto.createRoute(listingId))
                    },
                    onEditListing = { listingId ->
                        navController.navigate(Screen.SellerEditListing.createRoute(listingId))
                    },
                    onLogout = {
                        navController.navigate(Screen.BuyerMap.route) {
                            popUpTo(0)
                        }
                    }
                )
            }

            composable(Screen.SellerUploadPhoto.route) { backStackEntry ->
                val listingId = backStackEntry.arguments?.getString("listingId") ?: ""
                UploadPhotoScreen(
                    listingId = listingId,
                    onBack = { navController.popBackStack() },
                    onSuccess = { navController.popBackStack() }
                )
            }

            composable(Screen.SellerEditListing.route) { backStackEntry ->
                val listingId = backStackEntry.arguments?.getString("listingId") ?: ""
                EditListingScreen(
                    listingId = listingId,
                    repository = repository,
                    onBack = { navController.popBackStack() },
                    onSuccess = { navController.popBackStack() }
                )
            }
        }
    }
}
