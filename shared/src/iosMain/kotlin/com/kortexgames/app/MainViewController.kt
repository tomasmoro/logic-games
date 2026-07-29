package com.kortexgames.app

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.kortexgames.app.core.audio.PlatformContext
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.ui.App
import platform.UIKit.UIViewController

/**
 * Punto de entrada para iOS. Se invoca desde Swift:
 *   `ComposeView { MainViewControllerKt.MainViewController() }`
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val graph = remember { AppGraph(PlatformContext()) }
    App(graph)
}
