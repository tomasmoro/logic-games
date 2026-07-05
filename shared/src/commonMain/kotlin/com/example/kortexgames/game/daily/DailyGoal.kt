package com.example.kortexgames.game.daily

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.kortexgames.domain.repository.ProgressRepository
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
 * @property completed ejercicios completados hoy. @property target meta del día.
 */
data class DailyGoalState(
    val target: Int = DEFAULT_TARGET,
    val completed: Int = 0,
    val rewardClaimed: Boolean = false,
) {
    /** Progreso 0f..1f para barras/anillos. */
    val progress: Float get() = (completed.toFloat() / target).coerceIn(0f, 1f)
    val remaining: Int get() = (target - completed).coerceAtLeast(0)
    val isComplete: Boolean get() = completed >= target

    /** ¿Se puede reclamar la recompensa? (cumplido y aún no reclamado hoy). */
    val canClaim: Boolean get() = isComplete && !rewardClaimed

    companion object {
        const val DEFAULT_TARGET = 5
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
 * @param target ejercicios necesarios para la recompensa (5 por defecto).
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

            val completedToday = history.count { it.createdAt >= dayStart && it.createdAt < nextDayStart }
            DailyGoalState(
                target = target,
                completed = completedToday,
                rewardClaimed = claimedDate == today.toString(),
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
