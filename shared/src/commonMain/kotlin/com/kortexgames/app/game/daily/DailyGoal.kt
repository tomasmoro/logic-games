package com.kortexgames.app.game.daily

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kortexgames.app.domain.repository.ProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/**
 * Estado del objetivo diario. Derivado; no se persiste (salvo la reclamación).
 *
 * El "entrenamiento del día" son [DAILY_MISSION_SIZE] juegos concretos elegidos al
 * azar y **fijos durante el día** (ver [dailyMissionGames]); [mission] los expone
 * con su estado (hecho/pendiente) para pintar las celdas de "Tu misión de hoy".
 *
 * @property completed juegos de la misión completados hoy. @property target meta del
 *   día (número de juegos de la misión). @property mission juegos del día + estado.
 */
data class DailyGoalState(
    val target: Int = DEFAULT_TARGET,
    val completed: Int = 0,
    val rewardClaimed: Boolean = false,
    val mission: List<DailyMissionGame> = emptyList(),
) {
    /** Progreso 0f..1f para barras/anillos. */
    val progress: Float get() = (completed.toFloat() / target).coerceIn(0f, 1f)
    val remaining: Int get() = (target - completed).coerceAtLeast(0)
    val isComplete: Boolean get() = completed >= target

    /** ¿Se puede reclamar la recompensa? (cumplido y aún no reclamado hoy). */
    val canClaim: Boolean get() = isComplete && !rewardClaimed

    companion object {
        /** El objetivo diario es completar los juegos de la misión (3). */
        const val DEFAULT_TARGET = DAILY_MISSION_SIZE
    }
}

/**
 * Persiste SOLO la fecha (ISO `yyyy-MM-dd`) en que se reclamó la recompensa. Al
 * cambiar de día, la comparación falla y el objetivo se reinicia solo. Guardar la
 * fecha (y no un booleano) evita tener que "resetear" nada a medianoche.
 */
class DailyGoalStore(private val dataStore: DataStore<Preferences>) {
    private val claimedDateKey = stringPreferencesKey("daily_goal_claimed_date")

    val claimedDate: Flow<String?> = dataStore.data.map { it[claimedDateKey] }

    suspend fun setClaimedDate(isoDate: String) {
        dataStore.edit { it[claimedDateKey] = isoDate }
    }
}

/**
 * Lógica del objetivo diario. Combina el historial local (fuente de verdad,
 * funciona offline) con la fecha de reclamación para emitir un [DailyGoalState]
 * reactivo: cada partida guardada actualiza el progreso automáticamente.
 *
 * @param target juegos de la misión necesarios para la recompensa (3 por defecto).
 */
class DailyGoalManager(
    progress: ProgressRepository,
    private val store: DailyGoalStore,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val target: Int = DailyGoalState.DEFAULT_TARGET,
) {
    val state: StateFlow<DailyGoalState> =
        combine(progress.observeHistory(null), store.claimedDate) { history, claimedDate ->
            val tz = TimeZone.currentSystemDefault()
            val today = clock.todayIn(tz)
            val dayStart = today.atStartOfDayIn(tz)
            val nextDayStart = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)

            // Ids jugados hoy: base para marcar qué juegos de la misión están hechos.
            val playedTodayIds = history
                .filter { it.createdAt >= dayStart && it.createdAt < nextDayStart }
                .map { it.gameId }
                .toSet()

            // Misión del día (fija durante el día, ver [dailyMissionGames]) con su estado.
            val mission = dailyMissionGames(today.toEpochDays()).map { game ->
                DailyMissionGame(game = game, isDone = game.id in playedTodayIds)
            }
            val completed = mission.count { it.isDone }

            DailyGoalState(
                // El objetivo es completar la misión: la meta es su tamaño real.
                target = if (mission.isEmpty()) target else mission.size,
                completed = completed,
                rewardClaimed = claimedDate == today.toString(),
                mission = mission,
            )
        }.stateIn(scope, SharingStarted.Eagerly, DailyGoalState(target = target))

    /**
     * Reclama la recompensa del día si procede. Idempotente: solo tiene efecto
     * una vez por día. Devuelve true si se otorgó ahora.
     */
    suspend fun claimReward(): Boolean {
        if (!state.value.canClaim) return false
        store.setClaimedDate(clock.todayIn(TimeZone.currentSystemDefault()).toString())
        return true
    }
}
