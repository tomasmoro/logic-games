package com.kortexgames.app.core.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Consentimiento GDPR/EEA con la **User Messaging Platform** (UMP) de Google, previo a
 * inicializar el SDK de anuncios.
 *
 * Por qué existe y por qué aquí:
 *  - Google **exige** resolver el consentimiento antes de pedir el primer anuncio a
 *    usuarios que puedan estar en la UE/UK; no hacerlo puede suspender la cuenta.
 *  - El formulario de consentimiento **necesita una `Activity`** para presentarse (no
 *    basta el `applicationContext` con el que se arma el grafo), así que este flujo lo
 *    dispara [com.kortexgames.app.MainActivity], no el `AppGraph`.
 *  - `MobileAds.initialize` se llama **después** de resolver el consentimiento (o de
 *    saber que ya se pueden pedir anuncios), no en el registro de presentadores
 *    ([installPlatformAdPresenters]).
 *
 * Flujo (patrón recomendado por Google):
 *  1. `requestConsentInfoUpdate` refresca el estado (según región y consentimiento previo).
 *  2. `loadAndShowConsentFormIfRequired` muestra el formulario solo si hace falta.
 *  3. Cuando `canRequestAds()` es true (consentimiento dado o no requerido), se
 *     inicializa el SDK una sola vez ([initializeAdsOnce]).
 *
 * Todos los callbacks de UMP llegan en el hilo principal, por eso el flag
 * [adsInitialized] no necesita sincronización.
 */
object AdConsentManager {

    /** Evita inicializar el SDK dos veces (arranque rápido + callback del formulario). */
    private var adsInitialized = false

    /**
     * Pide/actualiza el consentimiento y, en cuanto se puedan pedir anuncios, inicializa
     * el SDK. Idempotente: puede llamarse en cada `onCreate` sin re-inicializar.
     *
     * Para **probar el formulario en desarrollo** (fuera de la UE no aparece), añadir un
     * `ConsentDebugSettings` a los [ConsentRequestParameters] con
     * `setDebugGeography(DEBUG_GEOGRAPHY_EEA)` y el hashed id del dispositivo (aparece en
     * logcat al pedir el consentimiento). No se deja fijo aquí porque el id es por-dispositivo.
     *
     * @param activity Activity en primer plano donde presentar el formulario si aplica.
     */
    fun gatherConsentAndInitialize(activity: Activity) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                // Estado actualizado: muestra el formulario si hace falta y, al cerrarse
                // (o si no era necesario), inicializa si ya se pueden pedir anuncios.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (consentInformation.canRequestAds()) initializeAdsOnce(activity)
                }
            },
            {
                // Falló la actualización (p. ej. sin red): aun así inicializa si un
                // consentimiento de una sesión previa ya lo permite.
                if (consentInformation.canRequestAds()) initializeAdsOnce(activity)
            },
        )

        // Arranque rápido: si una sesión anterior ya dejó consentimiento válido,
        // inicializa sin esperar al callback (el flag evita el doble init).
        if (consentInformation.canRequestAds()) initializeAdsOnce(activity)
    }

    private fun initializeAdsOnce(context: Context) {
        if (adsInitialized) return
        adsInitialized = true
        // Asíncrona; no bloqueamos el arranque esperando la callback.
        MobileAds.initialize(context.applicationContext) { /* SDK listo */ }
    }
}
