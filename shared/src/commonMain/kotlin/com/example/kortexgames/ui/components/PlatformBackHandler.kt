package com.example.kortexgames.ui.components

import androidx.compose.runtime.Composable

/**
 * Intercepta el botón/gesto "atrás" del sistema, cuando la plataforma tiene uno.
 *
 * `expect`/`actual` en vez del `BackHandler` común de Compose Multiplatform
 * (`androidx.compose.ui.backhandler`, paquete `jbMain`) porque ese artefacto **no
 * publica una variante para el target Android** (solo commonMain + targets no-Android:
 * iOS/desktop/wasm) — en Android compila contra `metadataCommonMainCompileClasspath`
 * pero no resuelve en el classpath real de `androidMain`. Este wrapper delega en el
 * `BackHandler` nativo real de cada plataforma.
 *
 * @param enabled si el handler debe interceptar el atrás.
 * @param onBack acción al pulsar/deslizar atrás.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
