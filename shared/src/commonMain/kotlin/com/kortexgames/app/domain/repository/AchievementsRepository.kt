package com.kortexgames.app.domain.repository

import com.kortexgames.app.domain.model.AchievementStatus
import kotlinx.coroutines.flow.Flow

/**
 * Logros del jugador con estrategia **local-first**, igual que
 * [PlayerProgressRepository]:
 *
 *  - [observeAll] une el catálogo (código) con el estado local del usuario y emite
 *    la vista lista para la UI. Lee SIEMPRE de local (funciona offline/invitado).
 *  - [recordProgress] fija el progreso hacia un logro y sella el desbloqueo al
 *    alcanzar el umbral; escribe local y, si hay sesión, sube el desbloqueo.
 *  - [sync] fusiona en ambas direcciones al iniciar sesión o recuperar red.
 *
 * La **evaluación** de las condiciones (cuántas partidas, qué racha, etc.) NO vive
 * aquí: la calculará un evaluador aparte a partir de las estadísticas y llamará a
 * [recordProgress]. Este repositorio solo persiste y sincroniza el estado.
 */
interface AchievementsRepository {

    /**
     * Estado combinado de TODOS los logros del catálogo (desbloqueados y no),
     * reactivo. Los que el usuario nunca tocó salen con progreso cero.
     */
    fun observeAll(): Flow<List<AchievementStatus>>

    /**
     * Registra el progreso hacia un logro. Si [progress] alcanza el umbral del
     * logro por primera vez, sella `unlockedAt` (desbloqueo). Nunca reduce el
     * progreso ni des-desbloquea (es monotónico). No-op si el id no está en el
     * catálogo.
     *
     * @param achievementId UUID del logro (ver `AchievementCatalog`).
     * @param progress avance absoluto hacia el umbral (no incremento).
     * @return `true` si esta llamada **desbloqueó** el logro (para celebrarlo en la
     *   UI); `false` si ya estaba desbloqueado o solo avanzó el progreso.
     */
    suspend fun recordProgress(achievementId: String, progress: Int): Boolean

    /**
     * Sincroniza los logros en ambas direcciones: descarga los desbloqueados de la
     * nube (fusiona con local) y sube los desbloqueos locales pendientes. No-op en
     * modo invitado.
     */
    suspend fun sync()

    /**
     * Vacía el progreso/desbloqueo local de todos los logros. Puramente local; lo
     * usa el borrado de cuenta tras confirmar el borrado en el backend.
     */
    suspend fun clearLocal()
}
