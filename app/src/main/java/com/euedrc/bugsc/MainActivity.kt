package com.euedrc.bugsc

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.euedrc.bugsc.ui.MobiGlasBottomBar
import com.euedrc.bugsc.ui.MobiGlasItem

class MainActivity : AppCompatActivity() {

    // 底部栏三个顶层目的地，顺序与 items 一致
    private val topLevel = listOf(R.id.ToolsFragment, R.id.QueryFragment, R.id.ProfileFragment)

    private val items = listOf(
        MobiGlasItem("工具", Icons.Filled.Build),
        MobiGlasItem("查询", Icons.Filled.Search),
        MobiGlasItem("个人信息", Icons.Filled.Person),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController

        val composeView = findViewById<ComposeView>(R.id.bottom_bar)

        // 选中项 & 显隐由目的地变化驱动
        var selectedIndex by mutableIntStateOf(0)
        var barVisible by mutableStateOf(true)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val idx = topLevel.indexOf(destination.id)
            if (idx >= 0) {
                selectedIndex = idx
                barVisible = true
            } else {
                // 钻进二级页时隐藏底部栏，避免遮挡
                barVisible = false
            }
        }

        composeView.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (barVisible) {
                    MobiGlasBottomBar(
                        items = items,
                        selectedIndex = selectedIndex,
                        onSelect = { idx ->
                            if (topLevel[idx] != navController.currentDestination?.id) {
                                navController.navigateTab(topLevel[idx])
                            }
                        },
                    )
                }
            }
        }

        // 顶部 status bar 留白给内容，底部 navigation bar 留白给底栏
        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left, bars.top, bars.right, 0)
            composeView.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    /** 底部栏切 Tab：保留各 Tab 返回栈状态，避免重复入栈 */
    private fun NavController.navigateTab(destId: Int) {
        navigate(
            destId,
            null,
            navOptions {
                launchSingleTop = true
                restoreState = true
                popUpTo(graph.startDestinationId) { saveState = true }
            },
        )
    }
}
