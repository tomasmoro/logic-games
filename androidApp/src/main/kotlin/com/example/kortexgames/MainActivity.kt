package com.example.kortexgames

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.kortexgames.data.remote.auth.CurrentActivityHolder
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.ui.App

/**
 * Activity única (single-activity). Obtiene el [AppGraph] del [LogicGamesApp] y
 * monta la UI Compose compartida.
 */
class MainActivity : ComponentActivity() {

    private val graph: AppGraph by lazy { (application as LogicGamesApp).graph }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App(graph) }
    }

    /**
     * Publica esta Activity como la "actual" para que el login con Google
     * (Credential Manager) tenga un contexto de UI donde mostrar el selector.
     */
    override fun onResume() {
        super.onResume()
        CurrentActivityHolder.set(this)
    }

    /**
     * Al perder el foco (menús del sistema, background) pausamos el conteo de
     * juego activo del AdManager: los anuncios solo cuentan tiempo jugando.
     */
    override fun onPause() {
        super.onPause()
        graph.adManager.onEnterMenuOrPause()
    }

    /** Suelta la referencia a esta Activity para no filtrarla tras destruirse. */
    override fun onDestroy() {
        CurrentActivityHolder.clear(this)
        super.onDestroy()
    }
}
