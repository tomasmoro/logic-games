package com.kortexgames.app.core.review

/**
 * Ficha de Google Play. El identificador es el `applicationId` del módulo
 * `androidApp` (`com.kortexgames.app`), que es lo que Play usa como clave de la
 * ficha; se escribe literal —y no se deriva de `BuildConfig`— porque el módulo
 * `shared` no genera `BuildConfig` propio y este valor no cambia nunca.
 */
actual val storeReviewLink: String? =
    "https://play.google.com/store/apps/details?id=com.kortexgames.app"
