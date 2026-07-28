package com.example.kortexgames.core.ads

/**
 * IDs de unidades de anuncio de AdMob para Android.
 *
 * Hoy usa los **IDs de PRUEBA oficiales de Google** (son públicos y seguros en
 * desarrollo). Es deliberado: usar los IDs reales antes de publicar —y hacer clic en
 * anuncios reales durante el desarrollo— viola la política de AdMob y suspende la
 * cuenta. Al publicar, sustituir por los IDs reales, idealmente inyectados desde
 * `secrets.properties` (como los Client IDs de Google, ver `Secrets`/`SupabaseConfig`)
 * para no commitearlos.
 *
 * El **App ID** va aparte, como `meta-data` en el `AndroidManifest` de `androidApp`
 * (placeholder `admobAppId`); no es un Ad Unit ID y el SDK lo lee del manifest.
 */
internal object AdMobConfig {
    /** Unidad **intersticial** de PRUEBA de Google. */
    const val INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    /** Unidad **recompensada** de PRUEBA de Google. */
    const val REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
}
