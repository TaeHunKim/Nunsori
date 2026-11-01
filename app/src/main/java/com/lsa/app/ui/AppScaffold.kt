@file:OptIn(ExperimentalMaterial3Api::class)

package com.lsa.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lsa.app.R
import com.lsa.app.ui.index.IndexScreen
import com.lsa.app.ui.search.SearchScreen
import com.lsa.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

enum class Dest(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    Index("index", R.string.nav_index, Icons.Outlined.PhotoLibrary),
    Search("search", R.string.nav_search, Icons.Outlined.Search),
    Settings("settings", R.string.nav_settings, Icons.Outlined.Settings),
}

@Composable
fun AppScaffold() {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val entry by nav.currentBackStackEntryAsState()
    val current = Dest.entries.firstOrNull { it.route == entry?.destination?.route } ?: Dest.Index

    fun go(d: Dest) {
        nav.navigate(d.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.app_name),
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                )
                Dest.entries.forEach { d ->
                    NavigationDrawerItem(
                        selected = d == current,
                        label = { Text(stringResource(d.label)) },
                        icon = { Icon(d.icon, contentDescription = null) },
                        onClick = { go(d); scope.launch { drawer.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(current.label)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.open_menu))
                        }
                    },
                )
            },
        ) { padding ->
            NavHost(nav, startDestination = Dest.Index.route, modifier = Modifier.padding(padding)) {
                composable(Dest.Index.route) { IndexScreen(onOpenSettings = { go(Dest.Settings) }) }
                composable(Dest.Search.route) { SearchScreen(onOpenSettings = { go(Dest.Settings) }) }
                composable(Dest.Settings.route) { SettingsScreen() }
            }
        }
    }
}
