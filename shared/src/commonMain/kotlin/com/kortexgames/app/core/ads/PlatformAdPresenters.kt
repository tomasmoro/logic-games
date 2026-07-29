package com.kortexgames.app.core.ads

import com.kortexgames.app.core.audio.PlatformContext

/**
 * Registra en [adManager] los presentadores de anuncios **de plataforma** y hace la
 * inicialización que el SDK requiera. Es la frontera `expect`/`actual` del SDK de
 * anuncios: `commonMain` no conoce ningún SDK (AdMob vive solo en el código de
 * plataforma). Se llama **una vez** al ensamblar el grafo de dependencias.
 *
 *  - **Android** (`actual`): inicializa Google Mobile Ads y registra los presentadores
 *    REALES de intersticial y recompensado. Qué unidad usa cada build (prueba en `debug`,
 *    real en `release`) lo decide `AdMobConfig`, no quien llama.
 *  - **iOS** (`actual`): aún sin SDK integrado (Parte B: CocoaPods/SPM + puente Swift);
 *    registra los presentadores **simulados** para que los flujos funcionen en dev.
 *
 * @param adManager el gestor donde se registran los presentadores.
 * @param context contexto de plataforma (en Android envuelve el `Context` de la app).
 */
expect fun installPlatformAdPresenters(adManager: AdManager, context: PlatformContext)
