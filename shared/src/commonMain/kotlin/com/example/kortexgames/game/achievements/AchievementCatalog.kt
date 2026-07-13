package com.example.kortexgames.game.achievements

import com.example.kortexgames.domain.model.Achievement
import com.example.kortexgames.domain.model.AchievementCondition

/**
 * IDs estables de los logros. Deben coincidir con las filas sembradas en la tabla
 * `achievements` de Supabase (migración `0012_seed_achievements.sql`) porque
 * `user_achievements.achievement_id` es una FK a esos UUID. Son UUID fijos y
 * deterministas, igual que [com.example.kortexgames.game.GameIds] para los juegos.
 *
 * El prefijo agrupa por tipo de condición (`a0000001…` = games_played, etc.) solo
 * como ayuda de lectura; no tiene significado para el backend.
 */
object AchievementIds {
    // games_played
    const val FIRST_GAME = "a0000001-0000-4000-8000-000000000001"
    const val GAMES_10 = "a0000001-0000-4000-8000-000000000002"
    const val GAMES_50 = "a0000001-0000-4000-8000-000000000003"
    const val GAMES_100 = "a0000001-0000-4000-8000-000000000004"

    // total_score
    const val SCORE_5000 = "a0000002-0000-4000-8000-000000000001"
    const val SCORE_25000 = "a0000002-0000-4000-8000-000000000002"

    // streak_days
    const val STREAK_3 = "a0000003-0000-4000-8000-000000000001"
    const val STREAK_7 = "a0000003-0000-4000-8000-000000000002"
    const val STREAK_30 = "a0000003-0000-4000-8000-000000000003"

    // daily_goal_completed
    const val DAILY_1 = "a0000004-0000-4000-8000-000000000001"
    const val DAILY_7 = "a0000004-0000-4000-8000-000000000002"

    // perfect_accuracy
    const val PERFECT_1 = "a0000005-0000-4000-8000-000000000001"
    const val PERFECT_10 = "a0000005-0000-4000-8000-000000000002"

    // category_mastery (sufijo = id de la categoría en `categories`)
    const val MASTER_MEMORY = "a0000006-0000-4000-8000-000000000003"
    const val MASTER_CALCULATION = "a0000006-0000-4000-8000-000000000010"
    const val MASTER_LANGUAGE = "a0000006-0000-4000-8000-000000000011"
}

/**
 * Catálogo de logros **en código**, fuente de verdad de la app para las
 * definiciones (regla + metadatos). Espeja el seed del backend
 * (`0012_seed_achievements.sql`).
 *
 * ¿Por qué en código y no leído de Supabase? Igual que el catálogo de juegos
 * ([com.example.kortexgames.game.GameCatalog]): la tabla `achievements` solo es
 * legible para el rol `authenticated` (RLS `achievements_read_all`), así que un
 * **invitado** (rol anónimo, 100% offline) no podría descargarla. Tenerlo en código
 * garantiza que la pantalla de Logros funcione sin sesión ni red. La tabla del
 * backend sigue siendo necesaria para la FK de `user_achievements` y validaciones
 * server-side futuras.
 *
 * ⚠️ Al añadir/editar un logro hay que tocar **ambos** lados (este objeto y una nueva
 * migración de seed) manteniendo los mismos UUID y umbrales.
 */
object AchievementCatalog {

    /** Todas las definiciones de logros, en orden de presentación. */
    val all: List<Achievement> = listOf(
        // --- games_played -----------------------------------------------------
        Achievement(AchievementIds.FIRST_GAME, "first_game", "Primer Paso",
            "Juega tu primera partida.", AchievementCondition.GAMES_PLAYED, threshold = 1, rewardPoints = 10),
        Achievement(AchievementIds.GAMES_10, "games_10", "Calentando Motores",
            "Juega 10 partidas.", AchievementCondition.GAMES_PLAYED, threshold = 10, rewardPoints = 20),
        Achievement(AchievementIds.GAMES_50, "games_50", "Jugador Frecuente",
            "Juega 50 partidas.", AchievementCondition.GAMES_PLAYED, threshold = 50, rewardPoints = 50),
        Achievement(AchievementIds.GAMES_100, "games_100", "Veterano",
            "Juega 100 partidas.", AchievementCondition.GAMES_PLAYED, threshold = 100, rewardPoints = 100),

        // --- total_score ------------------------------------------------------
        Achievement(AchievementIds.SCORE_5000, "score_5000", "Cinco Mil",
            "Acumula 5.000 puntos en total.", AchievementCondition.TOTAL_SCORE, threshold = 5000, rewardPoints = 40),
        Achievement(AchievementIds.SCORE_25000, "score_25000", "Cazador de Puntos",
            "Acumula 25.000 puntos en total.", AchievementCondition.TOTAL_SCORE, threshold = 25000, rewardPoints = 120),

        // --- streak_days ------------------------------------------------------
        Achievement(AchievementIds.STREAK_3, "streak_3", "Tres Días",
            "Mantén una racha de 3 días.", AchievementCondition.STREAK_DAYS, threshold = 3, rewardPoints = 20),
        Achievement(AchievementIds.STREAK_7, "streak_7", "Semana Perfecta",
            "Mantén una racha de 7 días.", AchievementCondition.STREAK_DAYS, threshold = 7, rewardPoints = 60),
        Achievement(AchievementIds.STREAK_30, "streak_30", "Imparable",
            "Mantén una racha de 30 días.", AchievementCondition.STREAK_DAYS, threshold = 30, rewardPoints = 200),

        // --- daily_goal_completed --------------------------------------------
        Achievement(AchievementIds.DAILY_1, "daily_1", "Meta Cumplida",
            "Completa tu objetivo diario por primera vez.", AchievementCondition.DAILY_GOAL_COMPLETED, threshold = 1, rewardPoints = 15),
        Achievement(AchievementIds.DAILY_7, "daily_7", "Rutina Sólida",
            "Completa el objetivo diario 7 veces.", AchievementCondition.DAILY_GOAL_COMPLETED, threshold = 7, rewardPoints = 70),

        // --- perfect_accuracy -------------------------------------------------
        Achievement(AchievementIds.PERFECT_1, "perfect_1", "Impecable",
            "Termina una partida con 100% de precisión.", AchievementCondition.PERFECT_ACCURACY, threshold = 1, rewardPoints = 25),
        Achievement(AchievementIds.PERFECT_10, "perfect_10", "Precisión Total",
            "Consigue 10 partidas perfectas.", AchievementCondition.PERFECT_ACCURACY, threshold = 10, rewardPoints = 90),

        // --- category_mastery (ámbito por categoría) -------------------------
        Achievement(AchievementIds.MASTER_MEMORY, "master_memory", "Maestro de la Memoria",
            "Juega 20 partidas de Memoria.", AchievementCondition.CATEGORY_MASTERY, threshold = 20, categoryId = 3, rewardPoints = 75),
        Achievement(AchievementIds.MASTER_CALCULATION, "master_calculation", "Maestro del Cálculo",
            "Juega 20 partidas de Cálculo Mental.", AchievementCondition.CATEGORY_MASTERY, threshold = 20, categoryId = 10, rewardPoints = 75),
        Achievement(AchievementIds.MASTER_LANGUAGE, "master_language", "Maestro del Lenguaje",
            "Juega 20 partidas de Lenguaje y Vocabulario.", AchievementCondition.CATEGORY_MASTERY, threshold = 20, categoryId = 11, rewardPoints = 75),
    )

    /** Índice por id para resolver umbral/metadatos al registrar progreso. */
    val byId: Map<String, Achievement> = all.associateBy { it.id }

    /** Definición por id, o `null` si el id no está en el catálogo. */
    fun forId(id: String): Achievement? = byId[id]
}
