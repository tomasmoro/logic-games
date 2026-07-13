package com.example.kortexgames.data.repository

import com.example.kortexgames.data.local.LocalPlayerProgressDataSource
import com.example.kortexgames.data.remote.RemotePlayerProgressDataSource
import com.example.kortexgames.domain.model.AuthState
import com.example.kortexgames.domain.model.GameResult
import com.example.kortexgames.domain.model.PlayerGameProgress
import com.example.kortexgames.domain.repository.PlayerProgressRepository
import com.example.kortexgames.game.GameProgressions
import com.example.kortexgames.game.ProgressionKind
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow

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
 * @param authState proveedor del estado de sesión actual (invitado/autenticado).
 */
class PlayerProgressRepositoryImpl(
    private val local: LocalPlayerProgressDataSource,
    private val remote: RemotePlayerProgressDataSource,
    private val authState: () -> AuthState,
    private val clock: Clock = Clock.System,
) : PlayerProgressRepository {

    override fun observe(gameId: String): Flow<PlayerGameProgress?> = local.observe(gameId)

    override fun observeAll(): Flow<List<PlayerGameProgress>> = local.observeAll()

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
        return isNewRecord
    }

    override suspend fun sync() {
        val userId = currentUserId() ?: return
        pullAndMerge()
        pushUnsynced(userId)
    }

    /** Sube una fila si hay sesión; al lograrlo la marca sincronizada. Silencia la red. */
    private suspend fun pushIfPossible(progress: PlayerGameProgress) {
        val userId = currentUserId() ?: return
        runCatching {
            remote.upsert(userId, progress)
            local.markSynced(progress.gameId)
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

    private fun currentUserId(): String? =
        (authState() as? AuthState.Authenticated)?.userId
}
