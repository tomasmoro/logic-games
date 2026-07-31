package com.kortexgames.app.domain.model

import kotlinx.datetime.Instant

/** Plan del usuario — espeja plan_type del backend. */
enum class PlanType { FREE, PREMIUM }

/** Estado de sesión: invitado (solo local) o autenticado (sincroniza). */
sealed interface AuthState {
    data object Guest : AuthState

    /** @property displayName nombre de usuario (`public.users.display_name`); null si no se ha fijado. */
    data class Authenticated(val userId: String, val plan: PlanType, val displayName: String? = null) : AuthState
}

/**
 * Resultado de una partida a persistir. Lo produce el GameEngine al terminar.
 *
 * @property reachedMetric valor de progresión ALCANZADO en esta partida, en la
 *   unidad natural del juego (longitud de secuencia, nivel, mejor reacción en ms…).
 *   Es la base del récord. `null` si la partida no arrojó métrica medible (p. ej.
 *   Reflejos sin ninguna reacción válida). No confundir con [difficultyLevel]
 *   (dificultad de INICIO) ni con [score] (puntaje acumulado).
 */
data class GameResult(
    val gameId: String,
    val score: Int,
    val completionTimeMs: Long,
    val accuracyPercentage: Double,
    val difficultyLevel: Int = 1,
    val dailyChallengeId: String? = null,
    val reachedMetric: Int? = null,
)

/** Fila del historial local/remoto (mapea user_progress). */
data class GameProgress(
    val localId: Long,
    val remoteId: String?,          // null = aún no sincronizado
    val gameId: String,
    val score: Int,
    val completionTimeMs: Long,
    val accuracyPercentage: Double,
    val difficultyLevel: Int,
    val createdAt: Instant,
    val isSynced: Boolean,
)

/**
 * Estado de progresión por juego: mejor marca y punto de reanudación. Mapea la
 * fila de `player_game_progress` (una por juego y usuario). A diferencia de
 * [GameProgress] (log inmutable de partidas), esto es reescribible y se sincroniza
 * como agregado entre dispositivos.
 *
 * @property bestMetric mejor marca en la unidad natural del juego (ver
 *   [GameResult.reachedMetric]). La dirección (mayor/menor mejor) la conoce la app.
 * @property lastLevel nivel de reanudación en juegos LEVELED; null en ENDLESS.
 * @property updatedAt momento de la marca; resuelve qué [lastLevel] gana al fusionar.
 * @property isSynced false = pendiente de subir a Supabase (cola local-first).
 */
data class PlayerGameProgress(
    val gameId: String,
    val bestMetric: Int,
    val lastLevel: Int?,
    val updatedAt: Instant,
    val isSynced: Boolean = true,
)

/**
 * Mejor tiempo del jugador en un nivel concreto de un juego LEVELED (menor = mejor).
 * Complementa a [PlayerGameProgress] (una fila por juego: récord de nivel máx) con el
 * detalle **por nivel**, que no cabe en el `bestMetric` único. Es un mecanismo genérico:
 * cualquier juego con [com.kortexgames.app.game.GameProgression.tracksLevelTime]
 * activo alimenta esta tabla sin lógica propia (hoy, Flujo de Energía).
 *
 * Mapea la fila de `player_level_time` (PK `user_id, game_id, level`) y su espejo local.
 *
 * @property gameId UUID del juego en el catálogo.
 * @property level nivel (1-based) al que corresponde el tiempo.
 * @property bestTimeMs mejor tiempo activo (excluye pausas) en milisegundos.
 * @property updatedAt momento de la marca; desempata al fusionar entre dispositivos.
 * @property isSynced false = pendiente de subir a Supabase (cola local-first).
 */
data class LevelBestTime(
    val gameId: String,
    val level: Int,
    val bestTimeMs: Long,
    val updatedAt: Instant,
    val isSynced: Boolean = true,
)

/**
 * Partida en curso guardada al salir (back / "SALIR" del menú de pausa) de un juego
 * que activa el guardado. [stateJson] es el estado del motor (`S` de
 * `GameEngine<S>`) serializado con kotlinx.serialization; el formato lo decide cada
 * juego, este modelo solo lo transporta. Es estado local efímero (no sincroniza con
 * Supabase, a diferencia de [PlayerGameProgress]/[LevelBestTime]).
 */
data class SavedGameState(
    val gameId: String,
    val stateJson: String,
    val savedAt: Instant,
)

/** Salida del RPC get_score_percentile / submit_game_result (FASE 2). */
data class PercentileResult(
    val betterThanPct: Double,
    val totalPlayers: Long,
    val rank: Long,
)

/**
 * Resultado de persistir una partida ([com.kortexgames.app.domain.repository.ProgressRepository.saveResult]).
 * Reúne en un solo objeto lo que la UI de fin de partida necesita, resuelto en la
 * misma ruta local-first para que ningún ViewModel tenga que recalcularlo.
 *
 * @property percentile percentil global (usuario autenticado + online); null si no.
 * @property isNewRecord true si la partida **batió el récord previo** del jugador en
 *   ese juego (según la dirección de su métrica). Es false en la primera marca
 *   registrada (sin récord anterior que superar) para no celebrar la primera partida.
 */
data class SaveOutcome(
    val percentile: PercentileResult?,
    val isNewRecord: Boolean,
)
