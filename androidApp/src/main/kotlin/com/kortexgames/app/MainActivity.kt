package com.kortexgames.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kortexgames.app.core.ads.AdConsentManager
import com.kortexgames.app.data.remote.auth.CurrentActivityHolder
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.ui.App

/**
 * Activity única (single-activity). Obtiene el [AppGraph] del [LogicGamesApp] y
 * monta la UI Compose compartida.
 */
class MainActivity : ComponentActivity() {

    private val graph: AppGraph by lazy { (application as LogicGamesApp).graph }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Consentimiento GDPR/UMP: se pide aquí (hay Activity para el formulario) y, al
        // resolverse, inicializa el SDK de anuncios. Debe ocurrir antes del primer
        // anuncio; el primer intersticial recién puede darse a los 3 min, así que hay
        // tiempo de sobra. Idempotente: no re-inicializa en recreaciones de la Activity.
        AdConsentManager.gatherConsentAndInitialize(this)
        setContent { App(graph) }
    }

    /**
     * Publica esta Activity como la "actual" para que el login con Google
     * (Credential Manager) tenga un contexto de UI donde mostrar el selector, y
     * reanuda el contador de anuncios: cuenta tiempo mientras la app está en primer
     * plano (no solo mientras se juega).
     */
    override fun onResume() {
        super.onResume()
        CurrentActivityHolder.set(this)
        graph.adManager.onAppForeground()
    }

    /**
     * Al pasar a segundo plano (otra app, pantalla apagada) pausamos el contador de
     * anuncios: el tiempo solo corre mientras la app está en primer plano.
     */
    override fun onPause() {
        super.onPause()
        graph.adManager.onAppBackground()
    }

    /** Suelta la referencia a esta Activity para no filtrarla tras destruirse. */
    override fun onDestroy() {
        CurrentActivityHolder.clear(this)
        super.onDestroy()
    }
}
