package com.example.kortexgames.data.repository

import com.example.kortexgames.data.local.LocalProgressDataSource
import com.example.kortexgames.data.remote.RemoteProgressDataSource
import com.example.kortexgames.domain.model.AuthState
import com.example.kortexgames.domain.model.GameProgress
import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.model.PercentileResult
import com.example.kortexgames.domain.repository.PlayerProgressRepository
import com.example.kortexgames.domain.repository.ProgressRepository
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.todayIn

/**
 * Implementación **local-first** del historial de partidas.
 *
 *  Guardar (saveResult):
 *    1. Escribe SIEMPRE en local primero (marcada como no sincronizada).
 *       → funciona offline y en modo invitado.
 *    2. Si hay sesión autenticada, intenta subir a Supabase y, si lo logra,
 *       marca la fila como sincronizada y devuelve el percentil.
 *    3. Si es invitado o falla la red, devuelve null (sin percentil) pero la
 *       partida ya está a salvo en local para sincronizarse después.
 *
 *  Sincronizar (syncPending): al iniciar sesión hace las DOS direcciones —
 *    primero sube lo pendiente (push local→nube) y luego descarga el historial
 *    de la nube que falte en este dispositivo (pull nube→local). El orden importa:
 *    subir antes de bajar evita traernos de vuelta como "nuevas" las partidas que
 *    jugamos como invitados y acabamos de subir.
 *
 * @param authState proveedor del estado de sesión actual (invitado/autenticado).
 * @param playerProgress progresión por juego (récord/reanudación); se actualiza en
 *   la misma ruta de fin de partida para no duplicar el hook en cada ViewModel.
 */
class ProgressRepositoryImpl(
    private val local: LocalProgressDataSource,
    private val remote: RemoteProgressDataSource,
    private val authState: () -> AuthState,
    private val playerProgress: PlayerProgressRepository,
    private val clock: Clock = Clock.System,
) : ProgressRepository {

    override suspend fun saveResult(result: GameResult): PercentileResult? {
        // 1) Local primero — nunca se pierde la partida.
        val localId = local.insert(result, clock.now().toEpochMilliseconds())

        // 1b) Actualiza récord/reanudación (local-first; también en modo invitado).
        //     No debe tumbar el guardado del historial si algo falla.
        runCatching { playerProgress.recordResult(result) }

        // 2) ¿Podemos ir al backend?
        if (authState() !is AuthState.Authenticated) return null

        return runCatching {
            val (remoteId, percentile) = remote.submit(result)
            local.markSynced(localId, remoteId)
            percentile
        }.getOrNull() // fallo de red → queda pendiente, se subirá en syncPending()
    }

    override fun observeHistory(gameId: String?): Flow<List<GameProgress>> =
        local.observeHistory(gameId)

    override suspend fun syncPending() {
        if (authState() !is AuthState.Authenticated) return
        pushLocal()
        pullRemote()
    }

    /** Push: sube a Supabase las filas locales aún sin sincronizar. */
    private suspend fun pushLocal() {
        for (row in local.getUnsynced()) {
            runCatching {
                val (remoteId, _) = remote.submit(row.toResult())
                local.markSynced(row.localId, remoteId)
            } // si una falla, seguimos con las demás; reintento en la próxima sync
        }
    }

    /**
     * Pull: descarga el historial de la nube y fusiona en local lo que falte
     * (deduplicado por remoteId en [LocalProgressDataSource.mergeRemote]). Así, al
     * entrar en un dispositivo nuevo o tras reinstalar, la app no aparece vacía.
     * Un fallo de red no rompe la sesión: se reintenta en la próxima sync.
     */
    private suspend fun pullRemote() {
        runCatching {
            local.mergeRemote(remote.fetchAll())
        }
    }

    override suspend fun countPlayedToday(): Int {
        val tz = TimeZone.currentSystemDefault()
        val start = clock.todayIn(tz).atStartOfDayIn(tz).toEpochMilliseconds()
        val end = start + 24L * 60 * 60 * 1000
        return local.countInRange(start, end)
    }

    private fun GameProgress.toResult() = GameResult(
        gameId = gameId,
        score = score,
        completionTimeMs = completionTimeMs,
        accuracyPercentage = accuracyPercentage,
        difficultyLevel = difficultyLevel,
    )
}
