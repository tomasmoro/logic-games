package com.kortexgames.app.data.repository

import com.kortexgames.app.data.local.LocalLevelTimeDataSource
import com.kortexgames.app.data.local.LocalPlayerProgressDataSource
import com.kortexgames.app.data.remote.RemoteLevelTimeDataSource
import com.kortexgames.app.data.remote.RemotePlayerProgressDataSource
import com.kortexgames.app.domain.model.AuthState
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.model.LevelBestTime
import com.kortexgames.app.domain.model.PlayerGameProgress
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.game.GameProgressions
import com.kortexgames.app.game.ProgressionKind
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Implementación **local-first** de la progresión por juego.
 *
 *  Registrar (recordResult):
 *    1. Calcula la nueva mejor marca comparando el valor ALCANZADO contra el
 *       récord local, según la dirección del juego (mayor/menor mejor).
 *    2. Escribe en local (marcada no-sincronizada) → funciona offline/invitado.
 *    3. Si hay sesión, sube la fila y la marca como sincronizada.
 *
 *  Sincronizar (sync): fusiona nube↔local (mejor marca gana; lastLevel más
 *  reciente gana) y sube lo pendiente. Como el servidor no interpreta la métrica,
 *  toda la resolución de conflictos vive aquí.
 *
 * Además del récord agregado, mantiene el **mejor tiempo por nivel** (localLevelTime /
 * remoteLevelTime) para los juegos con `tracksLevelTime` (misma ruta local-first).
 *
 * @param authState proveedor del estado de sesión actual (invitado/autenticado).
 */
class PlayerProgressRepositoryImpl(
    private val local: LocalPlayerProgressDataSource,
    private val remote: RemotePlayerProgressDataSource,
    private val localLevelTime: LocalLevelTimeDataSource,
    private val remoteLevelTime: RemoteLevelTimeDataSource,
    private val authState: () -> AuthState,
    private val clock: Clock = Clock.System,
) : PlayerProgressRepository {

    override fun observe(gameId: String): Flow<PlayerGameProgress?> = local.observe(gameId)

    override fun observeAll(): Flow<List<PlayerGameProgress>> = local.observeAll()

    override fun observeLevelTimes(gameId: String): Flow<Map<Int, Long>> =
        localLevelTime.observeByGame(gameId).map { rows -> rows.associate { it.level to it.bestTimeMs } }

    override suspend fun recordResult(result: GameResult): Boolean {
        val progression = GameProgressions.forId(result.gameId) ?: return false
        val reached = result.reachedMetric ?: return false // sin métrica medible → nada que registrar

        val current = local.getByGame(result.gameId)
        // Récord solo si HABÍA marca previa y esta la supera; la primera marca no cuenta
        // como "nuevo récord" (no hay nada que batir) para no celebrar la primera partida.
        val isNewRecord = current != null && progression.isBetter(reached, current.bestMetric)
        val newBest = when {
            current == null -> reached
            progression.isBetter(reached, current.bestMetric) -> reached
            else -> current.bestMetric
        }
        // El nivel de reanudación solo tiene sentido en juegos LEVELED; refleja la
        // última partida (puede ser menor que el récord: rejugar un nivel anterior).
        val lastLevel = if (progression.kind == ProgressionKind.LEVELED) reached else null

        val merged = PlayerGameProgress(
            gameId = result.gameId,
            bestMetric = newBest,
            lastLevel = lastLevel,
            updatedAt = clock.now(),
            isSynced = false,
        )
        local.upsert(merged)
        pushIfPossible(merged)

        // Mejor tiempo por nivel (genérico): solo en juegos LEVELED que lo activan y si
        // la partida arrojó un tiempo válido. `reached` es el nivel completado y
        // `completionTimeMs` su tiempo activo (excluye pausas), emparejados por finish().
        if (progression.kind == ProgressionKind.LEVELED &&
            progression.tracksLevelTime &&
            result.completionTimeMs > 0
        ) {
            recordLevelTime(result.gameId, level = reached, timeMs = result.completionTimeMs)
        }
        return isNewRecord
    }

    /** Guarda el tiempo [timeMs] como mejor marca del nivel si mejora la previa (menor gana). */
    private suspend fun recordLevelTime(gameId: String, level: Int, timeMs: Long) {
        val current = localLevelTime.getOne(gameId, level)
        if (current != null && timeMs >= current.bestTimeMs) return // no mejora → nada que hacer
        val best = LevelBestTime(
            gameId = gameId,
            level = level,
            bestTimeMs = timeMs,
            updatedAt = clock.now(),
            isSynced = false,
        )
        localLevelTime.upsert(best)
        pushLevelTimeIfPossible(best)
    }

    override suspend fun sync() {
        val userId = currentUserId() ?: return
        pullAndMerge()
        pushUnsynced(userId)
        pullAndMergeLevelTimes()
        pushUnsyncedLevelTimes(userId)
    }

    /** Sube una fila si hay sesión; al lograrlo la marca sincronizada. Silencia la red. */
    private suspend fun pushIfPossible(progress: PlayerGameProgress) {
        val userId = currentUserId() ?: return
        runCatching {
            remote.upsert(userId, progress)
            local.markSynced(progress.gameId)
        } // fallo de red → queda pendiente, se subirá en sync()
    }

    /** Igual que [pushIfPossible] pero para un tiempo por nivel. */
    private suspend fun pushLevelTimeIfPossible(time: LevelBestTime) {
        val userId = currentUserId() ?: return
        runCatching {
            remoteLevelTime.upsert(userId, time)
            localLevelTime.markSynced(time.gameId, time.level)
        } // fallo de red → queda pendiente, se subirá en sync()
    }

    /**
     * Descarga la nube y la fusiona con local. Reglas de conflicto:
     *  - mejor marca: gana la mejor según la dirección del juego,
     *  - reanudación: gana el `lastLevel` de la marca más reciente (`updatedAt`).
     * Si tras fusionar local aporta algo que la nube no tiene, la fila queda
     * pendiente (no-sincronizada) para que [pushUnsynced] la suba.
     */
    private suspend fun pullAndMerge() {
        val remoteRows = runCatching { remote.fetchAll() }.getOrNull() ?: return
        for (r in remoteRows) {
            val progression = GameProgressions.forId(r.gameId) ?: continue
            val localRow = local.getByGame(r.gameId)
            if (localRow == null) {
                local.upsert(r) // r ya viene marcada como sincronizada
                continue
            }
            val mergedBest =
                if (progression.isBetter(r.bestMetric, localRow.bestMetric)) r.bestMetric
                else localRow.bestMetric
            val remoteNewer = r.updatedAt >= localRow.updatedAt
            val mergedLastLevel = if (remoteNewer) r.lastLevel else localRow.lastLevel
            val mergedUpdatedAt = if (remoteNewer) r.updatedAt else localRow.updatedAt

            // ¿local mejora lo que hay en la nube? entonces hay que volver a subir.
            val needsPush = mergedBest != r.bestMetric || mergedLastLevel != r.lastLevel
            local.upsert(
                PlayerGameProgress(
                    gameId = r.gameId,
                    bestMetric = mergedBest,
                    lastLevel = mergedLastLevel,
                    updatedAt = mergedUpdatedAt,
                    isSynced = !needsPush,
                ),
            )
        }
    }

    /** Sube todas las filas locales pendientes (incluye las fusionadas con aporte local). */
    private suspend fun pushUnsynced(userId: String) {
        for (row in local.getUnsynced()) {
            runCatching {
                remote.upsert(userId, row)
                local.markSynced(row.gameId)
            } // si una falla, seguimos; reintento en la próxima sync
        }
    }

    /**
     * Fusiona los tiempos por nivel de la nube con local. Regla única (más simple que
     * el récord agregado): **gana el menor tiempo**; si local mejora lo remoto, la fila
     * queda pendiente para volver a subir. `updatedAt` acompaña a la marca ganadora.
     */
    private suspend fun pullAndMergeLevelTimes() {
        val remoteRows = runCatching { remoteLevelTime.fetchAll() }.getOrNull() ?: return
        for (r in remoteRows) {
            val localRow = localLevelTime.getOne(r.gameId, r.level)
            if (localRow == null) {
                localLevelTime.upsert(r) // r ya viene marcada como sincronizada
                continue
            }
            val localWins = localRow.bestTimeMs < r.bestTimeMs
            localLevelTime.upsert(
                if (localWins) localRow.copy(isSynced = false) // hay que resubir la mejor local
                else r, // remoto igual o mejor: adoptamos la fila remota (ya sincronizada)
            )
        }
    }

    /** Sube todos los tiempos por nivel locales pendientes. */
    private suspend fun pushUnsyncedLevelTimes(userId: String) {
        for (row in localLevelTime.getUnsynced()) {
            runCatching {
                remoteLevelTime.upsert(userId, row)
                localLevelTime.markSynced(row.gameId, row.level)
            } // si una falla, seguimos; reintento en la próxima sync
        }
    }

    override suspend fun clearLocal() {
        local.clearAll()
        localLevelTime.clearAll()
    }

    private fun currentUserId(): String? =
        (authState() as? AuthState.Authenticated)?.userId
}
