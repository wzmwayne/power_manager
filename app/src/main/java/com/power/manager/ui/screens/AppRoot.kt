package com.power.manager.ui.screens

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.power.manager.data.Template

enum class Tab { HOME, SETTINGS, LOG }

sealed class Route {
    object Tabs : Route()
    data class Edit(val template: Template) : Route()
}

@Composable
fun AppRoot() {
    var route by remember { mutableStateOf<Route>(Route.Tabs) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    when (val r = route) {
        is Route.Edit -> EditScreen(template = r.template, onBack = { route = Route.Tabs })
        Route.Tabs -> {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == Tab.HOME,
                            onClick = { tab = Tab.HOME },
                            icon = { Text("首") },
                            label = { Text("首页") }
                        )
                        NavigationBarItem(
                            selected = tab == Tab.SETTINGS,
                            onClick = { tab = Tab.SETTINGS },
                            icon = { Text("设") },
                            label = { Text("设置") }
                        )
                    }
                }
            ) { padding ->
                when (tab) {
                    Tab.HOME -> HomeScreen(padding = padding, onEdit = { route = Route.Edit(it) })
                    Tab.SETTINGS -> SettingsScreen(padding = padding, onOpenLog = { tab = Tab.LOG })
                    Tab.LOG -> LogScreen(padding = padding, onBack = { tab = Tab.HOME })
                }
            }
        }
    }
}