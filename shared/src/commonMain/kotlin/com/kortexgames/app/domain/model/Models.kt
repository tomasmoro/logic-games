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
 * Un puesto del ranking mundial de un juego, tal y como se pinta en la lista de
 * fin de partida.
 *
 * @property rank puesto (1 = mejor del mundo).
 * @property displayName nombre público del jugador; **null** si aún no ha elegido
 *   uno (la UI lo sustituye por un genérico). El backend nunca envía el `user_id`,
 *   así que este modelo no puede identificar a nadie más allá de su nombre público.
 * @property score la **mejor marca** de ese jugador en el juego, no la última. La unidad depende
 *   del criterio del ranking ([GameRanking.rankedByTime]): puntos en la mayoría de juegos,
 *   milisegundos en los que se rankean por tiempo. Se comparte el campo porque para el ranking es
 *   el mismo concepto —"el valor por el que se ordena"— y duplicarlo obligaría a que cada
 *   consumidor eligiera cuál mirar.
 * @property isCurrentUser true en la fila del jugador que acaba de jugar; la UI la
 *   resalta para que encuentre su posición de un vistazo.
 */
data class LeaderboardEntry(
    val rank: Long,
    val displayName: String?,
    val score: Int,
    val isCurrentUser: Boolean,
)

/**
 * Comparativa con el resto del mundo al terminar una partida (RPC `get_game_ranking`).
 *
 * A diferencia de [PercentileResult] —que compara la partida contra todas las
 * *partidas* históricas— aquí el universo son los **jugadores**, cada uno
 * representado por su mejor marca. Es la métrica que se puede listar y la que
 * corresponde a la idea intuitiva de "mi puesto".
 *
 * @property rank puesto del jugador (1 = el mejor del mundo).
 * @property totalPlayers cuántos jugadores tienen al menos una partida en el juego.
 * @property betterThanPct % de jugadores con marca estrictamente peor (0..100).
 *   Los empatados NO cuentan, así que el número nunca infla el logro.
 * @property isGlobalRecord true si la partida recién jugada **superó** la mejor
 *   marca mundial anterior (empatarla no basta). Dispara la celebración especial.
 * @property entries ventana del ranking alrededor del jugador (1 por encima y 3 por
 *   debajo; si es el nº1, los 5 primeros), ya ordenada por puesto ascendente.
 * @property difficultyLabel nombre de la dificultad a la que pertenece ESTA tabla
 *   ("Experto"), en los juegos cuyo ranking se separa por dificultad; null cuando el
 *   juego tiene una tabla única. La UI lo rotula para que se entienda por qué el top
 *   cambia según la dificultad elegida (ver `GameRankingScopes`).
 * @property rankedByTime la tabla se ordena por **tiempo de resolución** (gana el más rápido) en
 *   vez de por puntos. Lo usan los juegos donde el reto es idéntico para todos y lo único que
 *   distingue a un jugador es cuánto tarda —Neon Hyper-Cube y su nivel de mezcla fijo—, donde
 *   rankear por puntos mezclaría estilo de juego con velocidad. Cambia dos cosas en la UI: cómo se
 *   formatean las marcas (tiempo, no "puntos") y hacia qué lado es "mejor" (menor gana).
 */
data class GameRanking(
    val rank: Long,
    val totalPlayers: Long,
    val betterThanPct: Double,
    val isGlobalRecord: Boolean,
    val entries: List<LeaderboardEntry>,
    val difficultyLabel: String? = null,
    val rankedByTime: Boolean = false,
)

/**
 * Resultado de persistir una partida ([com.kortexgames.app.domain.repository.ProgressRepository.saveResult]).
 * Reúne en un solo objeto lo que la UI de fin de partida necesita, resuelto en la
 * misma ruta local-first para que ningún ViewModel tenga que recalcularlo.
 *
 * @property percentile percentil global (usuario autenticado + online); null si no.
 * @property ranking comparativa por jugadores (puesto + vecinos del ranking); null en
 *   invitado/offline, o si la RPC de ranking falló aunque la partida sí se subiera —
 *   se resuelve aparte del percentil justamente para que un fallo suyo no arrastre
 *   al resto de la tarjeta de resultados.
 * @property isNewRecord true si la partida **batió el récord previo** del jugador en
 *   ese juego (según la dirección de su métrica). Es false en la primera marca
 *   registrada (sin récord anterior que superar) para no celebrar la primera partida.
 */
data class SaveOutcome(
    val percentile: PercentileResult?,
    val ranking: GameRanking?,
    val isNewRecord: Boolean,
)
