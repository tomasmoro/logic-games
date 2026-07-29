package com.kortexgames.app.core.ads

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Resuelve qué **unidad de anuncio** de AdMob usar en cada build de Android.
 *
 * ## El problema que resuelve
 *
 * Hacer clic en anuncios REALES durante el desarrollo viola la política de AdMob y
 * puede suspender la cuenta; publicar con los de PRUEBA, en cambio, no genera ni un
 * céntimo. Dejar la elección a un cambio manual antes de publicar es justo el tipo de
 * paso que se olvida. Por eso aquí no hay nada que recordar: la decisión la toma el
 * propio build.
 *
 * ## La regla
 *
 * Se usa la unidad real **solo si se cumplen las dos condiciones**:
 *  1. El build **no es depurable** (`FLAG_DEBUGGABLE`), que es exactamente la marca que
 *     AGP pone en `debug` y quita en `release`. Se mira el flag y no una constante de
 *     `BuildConfig` porque este código vive en `shared`, que se compila como librería y
 *     no conoce el tipo de build de `androidApp`: el flag llega vía el `Context` real y
 *     describe el APK que de verdad se está ejecutando.
 *  2. Hay una unidad real configurada ([AdMobSecrets], generada desde
 *     `secrets.properties`). Si falta, se cae a la de prueba en vez de romper: así un
 *     clon del repo sin secretos sigue compilando y jugando.
 *
 * En cualquier otro caso → unidad de PRUEBA. Los IDs de prueba son públicos y los
 * publica Google, por eso pueden vivir en el repo sin ser un secreto.
 *
 * > ⚠️ Un `release` sin IDs reales servirá anuncios de prueba (rotulados "Test Ad") y no
 * > monetizará. `androidApp/build.gradle.kts` avisa por consola al ensamblarlo.
 *
 * El **App ID** no se resuelve aquí: no es un Ad Unit ID, el SDK lo lee del `meta-data`
 * del manifest y lo inyecta el placeholder `admobAppId` de `androidApp`.
 */
internal object AdMobConfig {

    /** Unidad **intersticial** de PRUEBA de Google (pública, segura en el repo). */
    const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    /** Unidad **recompensada** de PRUEBA de Google (pública, segura en el repo). */
    const val TEST_REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    /**
     * Unidad intersticial a usar, según la regla del KDoc de la clase.
     *
     * @param context contexto de aplicación; de él sale el flag de "build depurable".
     */
    fun interstitialUnitId(context: Context): String =
        resolveUnitId(context, AdMobSecrets.INTERSTITIAL_UNIT_ID, TEST_INTERSTITIAL_UNIT_ID)

    /**
     * Unidad recompensada a usar, según la regla del KDoc de la clase.
     *
     * @param context contexto de aplicación; de él sale el flag de "build depurable".
     */
    fun rewardedUnitId(context: Context): String =
        resolveUnitId(context, AdMobSecrets.REWARDED_UNIT_ID, TEST_REWARDED_UNIT_ID)

    /** Aplica la regla "real solo si release Y está configurada". */
    private fun resolveUnitId(context: Context, realId: String, testId: String): String =
        if (realId.isNotBlank() && !context.isDebuggable) realId else testId

    /**
     * `true` en builds `debug` de AGP. `release` deja el flag a 0 aunque se firme con
     * una clave de pruebas, así que distingue "desarrollo" de "lo que se publica".
     */
    private val Context.isDebuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
