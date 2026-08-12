package com.kortexgames.app.core.ads

import com.kortexgames.app.core.audio.PlatformContext
import com.kortexgames.app.data.remote.auth.CurrentActivityHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Android: delega en [AdConsentManager] (UMP + `MobileAds.initialize`).
 *
 * Lo único que añade es **esperar a que haya una `Activity`**: el formulario de UMP no
 * se puede presentar con el `applicationContext`, y esta función puede dispararse
 * durante el arranque —cuando el grafo ya existe pero `MainActivity.onResume` aún no
 * ha publicado la suya en [CurrentActivityHolder]—. La espera está acotada: si en
 * [MAX_WAIT_MS] no hay Activity (app abierta en segundo plano, por ejemplo) se
 * abandona en silencio; el flujo se reintentará en la siguiente apertura, y hasta
 * entonces no se pide ningún anuncio porque el SDK sigue sin inicializar.
 */
actual suspend fun beginAdConsentFlow(context: PlatformContext) {
    var waited = 0L
    var activity = CurrentActivityHolder.activity
    while (activity == null && waited < MAX_WAIT_MS) {
        delay(POLL_MS)
        waited += POLL_MS
        activity = CurrentActivityHolder.activity
    }
    val target = activity ?: return
    // UMP toca UI: su API debe invocarse desde el hilo principal.
    withContext(Dispatchers.Main) { AdConsentManager.gatherConsentAndInitialize(target) }
}

/** Cadencia del sondeo de la Activity (ms). */
private const val POLL_MS = 150L

/** Tope de espera de una Activity antes de abandonar el intento (ms). */
private const val MAX_WAIT_MS = 10_000L
