package com.kortexgames.app.core.startup

import com.kortexgames.app.data.settings.OnboardingGate
import com.kortexgames.app.domain.model.GameProgress
import com.kortexgames.app.domain.repository.AuthRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.game.GameCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Precarga de arranque: reúne en un solo sitio TODO lo que la Home necesita para
 * pintarse completa en su primer frame, y expone cuándo está listo.
 *
 * El porqué: la Home es local-first y observa varias fuentes (historial en
 * SQLDelight, objetivo diario, sesión). Si se monta antes de que hayan emitido,
 * el usuario ve la pantalla "vacía" y luego un baile de datos apareciendo
 * (racha a 0 → racha real, banner de invitado que desaparece al restaurarse la
 * sesión). La splash dura lo suficiente como para cubrir esas lecturas, así que
 * se aprovecha como ventana de carga: mientras la animación del logo corre, aquí
 * se calientan las fuentes; cuando la Home aparece, todo son valores ya resueltos.
 *
 * Decisiones clave:
 *  - [history] se comparte con `SharingStarted.Eagerly`: **una sola** consulta viva
 *    para toda la app (antes cada montaje de la Home abría la suya). El `null`
 *    inicial distingue "aún no leído" de "leído y vacío" (jugador nuevo), algo que
 *    `emptyList()` como valor inicial no permitía.
 *  - [awaitReady] tiene tope de espera: un arranque degradado (BD lenta, red caída
 *    en la restauración de sesión) nunca puede dejar al usuario mirando la splash.
 *
 * @param scope scope de aplicación: mantiene vivas las fuentes compartidas.
 */
class StartupPreloader(
    onboardingGate: OnboardingGate,
    authRepository: AuthRepository,
    progressRepository: ProgressRepository,
    scope: CoroutineScope,
) {

    /**
     * Historial completo de partidas, compartido por toda la app.
     *
     * `null` mientras la primera lectura local no ha emitido; a partir de ahí, la
     * lista real (que se mantiene fresca sola: SQLDelight reemite al cambiar la
     * tabla). Los consumidores lo usan como valor inicial, de forma que la Home
     * no "busca" nada al montarse.
     */
    val history: StateFlow<List<GameProgress>?> =
        progressRepository.observeHistory(null)
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * true cuando la app puede mostrar la Home sin datos a medio resolver:
     * puerta de onboarding leída, sesión resuelta (restaurada o confirmada como
     * invitado) e historial local cargado.
     *
     * No se espera al objetivo diario ni a la racha a propósito: ambos se derivan
     * de [history] y del mismo DataStore que la puerta de onboarding, así que para
     * cuando estas tres condiciones se cumplen, ya están calculados.
     */
    val isReady: StateFlow<Boolean> =
        combine(
            onboardingGate.hasDecided,
            authRepository.sessionResolved,
            history,
        ) { decided, sessionResolved, hist ->
            decided != null && sessionResolved && hist != null
        }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Toca el catálogo fuera del hilo de UI para pagar aquí su inicialización
        // (creación de los 30 GameInfo y sus paletas) en vez de en el primer
        // scroll del usuario.
        scope.launch { GameCatalog.games.size }
    }

    /**
     * Suspende hasta que [isReady] sea true, con un tope de [timeoutMs].
     *
     * El tope es deliberado: si algo se atasca preferimos entrar a la Home con
     * datos incompletos (que se completarán solos al emitir) antes que dejar la
     * splash colgada. En la práctica nunca se alcanza, porque la animación de la
     * splash dura más que la carga.
     */
    suspend fun awaitReady(timeoutMs: Long = MAX_WAIT_MS) {
        withTimeoutOrNull(timeoutMs) { isReady.first { it } }
    }

    private companion object {
        /** Tope de espera del arranque (ms) antes de entrar igualmente a la Home. */
        const val MAX_WAIT_MS = 4_000L
    }
}
