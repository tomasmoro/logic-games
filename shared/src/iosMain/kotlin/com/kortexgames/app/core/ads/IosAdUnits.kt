package com.kortexgames.app.core.ads

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * Resuelve qué **unidad de anuncio** de AdMob usar en cada build de iOS.
 *
 * Es el gemelo de `AdMobConfig` (androidMain): misma política, distinta señal de
 * plataforma. El KDoc de aquel explica el porqué a fondo; el resumen es que hacer
 * clic en anuncios REALES durante el desarrollo viola la política de AdMob y puede
 * costar la suspensión de la cuenta, mientras que publicar con los de PRUEBA
 * significa no ingresar nada. Dejar el cambio a un paso manual antes de publicar es
 * justo lo que se olvida, así que la decisión la toma el propio build.
 *
 * ## La regla
 *
 * Unidad real **solo si se cumplen las dos condiciones**:
 *  1. El binario **no es de depuración** ([Platform.isDebugBinary]). Es el análogo de
 *     `FLAG_DEBUGGABLE` en Android: la tarea `embedAndSignAppleFrameworkForXcode`
 *     compila el framework en el mismo modo que la configuración de Xcode, así que un
 *     Archive (configuración Release) produce un binario de release y esto es `false`.
 *  2. Hay una unidad real configurada en `secrets.properties` (llega vía el objeto
 *     generado `AdMobSecrets`). Si falta, se cae a la de prueba en vez de romper: un
 *     clon del repo sin secretos sigue compilando y jugando.
 *
 * En cualquier otro caso → unidad de PRUEBA. Los IDs de prueba son públicos y los
 * publica Google, por eso pueden vivir en el repo sin ser un secreto.
 *
 * ## Por qué es `public` y no `internal`
 *
 * A diferencia del de Android, este objeto lo consume **Swift**: `AdMobBridge.swift`
 * es quien enlaza el SDK de Google Mobile Ads (ver [IosAdBridge]), así que necesita
 * preguntar por el ad unit desde el otro lado del framework. Se exporta como
 * `IosAdUnits.shared.interstitialUnitId` en Swift.
 *
 * > ⚠️ El **App ID** de AdMob no se resuelve aquí. No es un Ad Unit ID: el SDK lo lee
 * > de la clave `GADApplicationIdentifier` del `Info.plist` al arrancar, antes de que
 * > exista runtime de Kotlin, así que tiene que estar en el plist.
 */
object IosAdUnits {

    /** Unidad **intersticial** de PRUEBA de Google para iOS (pública, segura en el repo). */
    const val TEST_INTERSTITIAL_UNIT_ID: String = "ca-app-pub-3940256099942544/4411468910"

    /** Unidad **recompensada** de PRUEBA de Google para iOS (pública, segura en el repo). */
    const val TEST_REWARDED_UNIT_ID: String = "ca-app-pub-3940256099942544/1712485313"

    /** Unidad intersticial a usar, según la regla del KDoc de la clase. */
    val interstitialUnitId: String
        get() = resolver(AdMobSecrets.IOS_INTERSTITIAL_UNIT_ID, TEST_INTERSTITIAL_UNIT_ID)

    /** Unidad recompensada a usar, según la regla del KDoc de la clase. */
    val rewardedUnitId: String
        get() = resolver(AdMobSecrets.IOS_REWARDED_UNIT_ID, TEST_REWARDED_UNIT_ID)

    /**
     * `true` cuando el build servirá anuncios REALES. Lo expone para que la capa Swift
     * pueda registrarlo en el log al arrancar: si algún día un release no monetiza,
     * esta línea dice en un vistazo si el problema es la configuración o el inventario
     * de AdMob.
     */
    @OptIn(ExperimentalNativeApi::class)
    val usaAnunciosReales: Boolean
        get() = !Platform.isDebugBinary &&
            AdMobSecrets.IOS_INTERSTITIAL_UNIT_ID.isNotBlank()

    /** Aplica la regla "real solo si es release Y está configurada". */
    @OptIn(ExperimentalNativeApi::class)
    private fun resolver(idReal: String, idPrueba: String): String =
        if (idReal.isNotBlank() && !Platform.isDebugBinary) idReal else idPrueba
}
