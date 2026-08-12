package com.kortexgames.app.core.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Puente entre el código común (que pide el permiso desde una corrutina) y el
 * `ActivityResultLauncher` de Android (que solo puede registrarse en la Activity,
 * antes de que esta arranque).
 *
 * Sigue el mismo patrón que `CurrentActivityHolder` en el login con Google: el
 * `AppGraph` vive a nivel de proceso y no tiene Activity, así que la Activity
 * publica aquí su lanzador y el `actual` del scheduler lo usa cuando toca.
 *
 * Es un objeto de proceso porque el permiso de notificaciones es uno solo para toda
 * la app: no tiene sentido tener varias peticiones vivas a la vez.
 */
object NotificationPermissionRequester {

    /** Lanza el diálogo del sistema. Lo publica la Activity con [register]. */
    private var launcher: (() -> Unit)? = null

    /** Petición en curso, a la espera de la respuesta del usuario. */
    private var pending: ((Boolean) -> Unit)? = null

    /**
     * La Activity publica su lanzador ya registrado (`registerForActivityResult`).
     * Debe hacerlo en `onCreate`: registrar un contrato más tarde lanza excepción.
     */
    fun register(launch: () -> Unit) {
        launcher = launch
    }

    /** La Activity suelta el lanzador al destruirse (evita filtrarla). */
    fun unregister() {
        launcher = null
        // Una petición viva sin Activity que la resuelva nunca se completaría: se
        // cierra como denegada para no dejar la corrutina colgada para siempre.
        pending?.invoke(false)
        pending = null
    }

    /** La Activity entrega aquí la respuesta del diálogo del sistema. */
    fun onResult(granted: Boolean) {
        pending?.invoke(granted)
        pending = null
    }

    /**
     * Muestra el diálogo del sistema y suspende hasta que el usuario responde.
     *
     * @return true si concedió el permiso; false si lo denegó o si no hay Activity
     *   registrada (app en segundo plano: no hay dónde mostrar el diálogo).
     */
    suspend fun request(): Boolean {
        val launch = launcher ?: return false
        // El contrato de ActivityResult exige hilo principal, y quien pide el permiso
        // suele venir de un scope de ViewModel (Dispatchers.Default).
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                pending = { granted ->
                    if (continuation.isActive) continuation.resume(granted)
                }
                continuation.invokeOnCancellation { pending = null }
                launch()
            }
        }
    }
}
