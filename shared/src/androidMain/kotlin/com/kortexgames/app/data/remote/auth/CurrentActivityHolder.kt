package com.kortexgames.app.data.remote.auth

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Guarda una referencia **débil** a la Activity en primer plano.
 *
 * Credential Manager (login con Google) necesita un `Context` de Activity para
 * presentar el selector de cuentas — no basta el `applicationContext` con el que
 * se construye el [com.kortexgames.app.di.AppGraph]. Como el grafo vive a
 * nivel de proceso y las Activities van y vienen, la Activity actual se publica
 * aquí desde su ciclo de vida y el `actual` de Android la lee al pedir el token.
 *
 * Se usa [WeakReference] a propósito: este holder no debe impedir que la Activity
 * se recolecte si se destruye sin desregistrarse (evita fugas de memoria).
 */
object CurrentActivityHolder {

    private var ref: WeakReference<Activity> = WeakReference(null)

    /** La Activity en primer plano, o null si no hay ninguna registrada. */
    val activity: Activity? get() = ref.get()

    /** La Activity la publica al reanudarse (`onResume`). */
    fun set(activity: Activity) {
        ref = WeakReference(activity)
    }

    /** Limpia la referencia si la que se destruye es la registrada (`onDestroy`). */
    fun clear(activity: Activity) {
        if (ref.get() === activity) ref = WeakReference(null)
    }
}
