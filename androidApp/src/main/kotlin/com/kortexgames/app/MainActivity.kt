package com.kortexgames.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.kortexgames.app.core.notifications.NotificationPermissionRequester
import com.kortexgames.app.data.remote.auth.CurrentActivityHolder
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.ui.App

/**
 * Activity única (single-activity). Obtiene el [AppGraph] del [LogicGamesApp] y
 * monta la UI Compose compartida.
 */
class MainActivity : ComponentActivity() {

    private val graph: AppGraph by lazy { (application as LogicGamesApp).graph }

    /**
     * Diálogo del permiso de notificaciones (Android 13+). Se registra aquí porque
     * `registerForActivityResult` exige hacerlo antes de que la Activity arranque; el
     * código común lo dispara a través de [NotificationPermissionRequester] cuando el
     * usuario activa los recordatorios en Ajustes.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            NotificationPermissionRequester.onResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // El consentimiento GDPR/UMP ya NO se pide aquí: lo dispara el código común
        // (`beginAdConsentFlow`, desde el `AppGraph`) cuando termina la primera
        // apertura, que es cuando pueden empezar a hacer falta anuncios. La Activity
        // que el formulario necesita la toma de `CurrentActivityHolder`, publicada
        // abajo en `onResume`.
        NotificationPermissionRequester.register {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        NotificationPermissionRequester.unregister()
        super.onDestroy()
    }
}
