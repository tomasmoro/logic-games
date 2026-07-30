package com.kortexgames.app

import android.app.Application
import com.kortexgames.app.core.audio.PlatformContext
import com.kortexgames.app.di.AppGraph

/**
 * Application de la app Android. Mantiene el [AppGraph] (DI manual) durante todo
 * el ciclo de vida del proceso, de modo que se construye UNA sola vez y sobrevive
 * a recreaciones de la Activity (rotaciones, cambios de configuración).
 */
class LogicGamesApp : Application() {

    /** Contenedor de dependencias de la app (repos, audio, ads, settings...). */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // PlatformContext envuelve el Context de la app.
        graph = AppGraph(PlatformContext(this))
    }
}
