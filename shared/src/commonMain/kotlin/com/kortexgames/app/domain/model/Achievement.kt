package com.kortexgames.app.domain.model

import kotlin.time.Instant

/**
 * Tipo de condición que desbloquea un logro. Espeja el enum `achievement_condition`
 * del backend (`0001_initial_schema.sql`). Modelamos el dominio con `enum`, no
 * strings (CLAUDE.md §4), y la conversión desde/hacia el texto del backend vive en
 * la capa de datos.
 */
enum class AchievementCondition {
    /** Nº total de partidas jugadas. */
    GAMES_PLAYED,

    /** Puntaje acumulado de por vida. */
    TOTAL_SCORE,

    /** Días consecutivos de actividad (racha). */
    STREAK_DAYS,

    /** Dominio de una categoría cognitiva concreta (usa [Achievement.categoryId]). */
    CATEGORY_MASTERY,

    /** Partidas terminadas con 100% de precisión. */
    PERFECT_ACCURACY,

    /** Veces que se completó el objetivo mental diario. */
    DAILY_GOAL_COMPLETED,
}

/**
 * **Definición** (catálogo) de un logro: la regla de desbloqueo y sus metadatos de
 * presentación. Es inmutable y vive en código (`AchievementCatalog`), espejando la
 * fila de `public.achievements`. El `id` es un UUID fijo que coincide con el seed
 * del backend (`0012_seed_achievements.sql`) para que la FK de `user_achievements`
 * resuelva; ver la nota de UUIDs en `AchievementCatalog`.
 *
 * @property threshold umbral de la condición (nº de partidas, puntos, días…) que
 *   marca el desbloqueo. Siempre > 0.
 * @property categoryId ámbito opcional: solo para [AchievementCondition.CATEGORY_MASTERY]
 *   (id de `categories`); `null` en logros globales.
 * @property rewardPoints puntos de recompensa al desbloquear (gamificación futura).
 */
data class Achievement(
    val id: String,
    val slug: String,
    val name: String,
    val description: String,
    val condition: AchievementCondition,
    val threshold: Int,
    val categoryId: Int? = null,
    val rewardPoints: Int = 0,
)

/**
 * **Estado por-usuario** de un logro: progreso hacia el umbral y momento de
 * desbloqueo. Es la parte que se persiste local-first y se sincroniza con
 * `user_achievements`.
 *
 * @property progress avance hacia el umbral (para barras "7/10"). Monotónico
 *   creciente, lo que permite resolver conflictos de sync tomando el mayor.
 * @property unlockedAt instante de desbloqueo, o `null` si aún no se logró. Es la
 *   fuente de verdad de "desbloqueado" (no se deriva solo del progreso, porque un
 *   umbral podría recalcularse); la primera vez que `progress` alcanza el umbral se
 *   sella y ya no se borra.
 * @property isSynced `false` mientras esté pendiente de subir a Supabase.
 */
data class UserAchievement(
    val achievementId: String,
    val progress: Int,
    val unlockedAt: Instant? = null,
    val isSynced: Boolean = true,
)

/**
 * Vista combinada **catálogo + estado del usuario**, lista para la UI (pantalla
 * "Logros"). La produce el repositorio uniendo cada [Achievement] del catálogo con
 * su [UserAchievement] local (o progreso cero si nunca se tocó).
 */
data class AchievementStatus(
    val achievement: Achievement,
    val progress: Int = 0,
    val unlockedAt: Instant? = null,
) {
    /** `true` si el logro ya está desbloqueado. */
    val isUnlocked: Boolean get() = unlockedAt != null

    /**
     * Progreso normalizado [0f, 1f] para barras/anillos. Se acota por si el
     * progreso acumulado supera el umbral (p. ej. logros de 1 partida).
     */
    val fraction: Float
        get() = if (achievement.threshold <= 0) 1f
        else (progress.toFloat() / achievement.threshold).coerceIn(0f, 1f)
}
