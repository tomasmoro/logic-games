package com.example.kortexgames

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.example.kortexgames.core.audio.PlatformContext
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.ui.App
import platform.UIKit.UIViewController

/**
 * Punto de entrada para iOS. Se invoca desde Swift:
 *   `ComposeView { MainViewControllerKt.MainViewController() }`
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val graph = remember { AppGraph(PlatformContext()) }
    App(graph)
}
