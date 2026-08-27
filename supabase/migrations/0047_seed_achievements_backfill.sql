-- =============================================================================
-- 0047 — Seed del catálogo de logros (RECONSTRUCCIÓN, no funcionalidad nueva)
-- -----------------------------------------------------------------------------
-- POR QUÉ EXISTE ESTE ARCHIVO
-- Las 16 filas de `public.achievements` SÍ están en producción: se aplicaron el
-- 09/07/2026 como la migración `0012_seed_achievements`, que consta en el historial
-- de la base de datos. Lo que nunca llegó al repositorio es el ARCHIVO .sql.
--
-- Eso es drift de esquema, y del tipo que peor se detecta:
--   * Producción funciona, así que nada falla a la vista.
--   * Pero `supabase db reset`, una branch de Supabase o cualquier entorno nuevo
--     nacen con la tabla VACÍA. Y como `user_achievements.achievement_id` es FK a
--     ella, en cuanto exista el evaluador de logros TODO desbloqueo fallaría con
--     violación de clave ajena — un fallo que solo se vería en desarrollo y nunca
--     en producción, que es la peor forma posible de tenerlo.
--   * Los mismos UUID viven duplicados en `AchievementCatalog.kt` sin ningún
--     contrato escrito que los ate. El KDoc de `Achievement.kt` y `BACKLOG.md`
--     llevan meses citando un `0012_seed_achievements.sql` que no existe.
--
-- POR QUÉ CON EL NÚMERO 0047 Y NO 0012
-- El 0012 del repositorio ya lo ocupa `0012_seed_neon_screws.sql` y las migraciones
-- aplicadas no se renombran. El número no importa para la corrección: basta con que
-- se aplique después de 0001 (que crea la tabla). En producción esta migración es un
-- no-op gracias al `on conflict`.
--
-- VALORES: copia literal de lo verificado en producción el 27/08/2026, y deben
-- coincidir uno a uno con `AchievementCatalog.all` en el cliente.
-- IDEMPOTENTE: `on conflict (id) do nothing`, igual que los seeds de juegos.
-- =============================================================================

insert into public.achievements
    (id, slug, name, description, condition_type, threshold, category_id, reward_points)
values
    -- Partidas jugadas
    ('a0000001-0000-4000-8000-000000000001', 'first_game', 'Primer Paso',
     'Juega tu primera partida.', 'games_played', 1, null, 10),
    ('a0000001-0000-4000-8000-000000000002', 'games_10', 'Calentando Motores',
     'Juega 10 partidas.', 'games_played', 10, null, 20),
    ('a0000001-0000-4000-8000-000000000003', 'games_50', 'Jugador Frecuente',
     'Juega 50 partidas.', 'games_played', 50, null, 50),
    ('a0000001-0000-4000-8000-000000000004', 'games_100', 'Veterano',
     'Juega 100 partidas.', 'games_played', 100, null, 100),

    -- Puntuación acumulada
    ('a0000002-0000-4000-8000-000000000001', 'score_5000', 'Cinco Mil',
     'Acumula 5.000 puntos en total.', 'total_score', 5000, null, 40),
    ('a0000002-0000-4000-8000-000000000002', 'score_25000', 'Cazador de Puntos',
     'Acumula 25.000 puntos en total.', 'total_score', 25000, null, 120),

    -- Rachas
    ('a0000003-0000-4000-8000-000000000001', 'streak_3', 'Tres Días',
     'Mantén una racha de 3 días.', 'streak_days', 3, null, 20),
    ('a0000003-0000-4000-8000-000000000002', 'streak_7', 'Semana Perfecta',
     'Mantén una racha de 7 días.', 'streak_days', 7, null, 60),
    ('a0000003-0000-4000-8000-000000000003', 'streak_30', 'Imparable',
     'Mantén una racha de 30 días.', 'streak_days', 30, null, 200),

    -- Objetivo diario
    ('a0000004-0000-4000-8000-000000000001', 'daily_1', 'Meta Cumplida',
     'Completa tu objetivo diario por primera vez.', 'daily_goal_completed', 1, null, 15),
    ('a0000004-0000-4000-8000-000000000002', 'daily_7', 'Rutina Sólida',
     'Completa el objetivo diario 7 veces.', 'daily_goal_completed', 7, null, 70),

    -- Precisión perfecta
    ('a0000005-0000-4000-8000-000000000001', 'perfect_1', 'Impecable',
     'Termina una partida con 100% de precisión.', 'perfect_accuracy', 1, null, 25),
    ('a0000005-0000-4000-8000-000000000002', 'perfect_10', 'Precisión Total',
     'Consigue 10 partidas perfectas.', 'perfect_accuracy', 10, null, 90),

    -- Maestría por categoría. `category_id` es FK a public.categories (0005):
    -- 3 = Memoria, 10 = Cálculo Mental, 11 = Lenguaje y Vocabulario.
    ('a0000006-0000-4000-8000-000000000003', 'master_memory', 'Maestro de la Memoria',
     'Juega 20 partidas de Memoria.', 'category_mastery', 20, 3, 75),
    ('a0000006-0000-4000-8000-000000000010', 'master_calculation', 'Maestro del Cálculo',
     'Juega 20 partidas de Cálculo Mental.', 'category_mastery', 20, 10, 75),
    ('a0000006-0000-4000-8000-000000000011', 'master_language', 'Maestro del Lenguaje',
     'Juega 20 partidas de Lenguaje y Vocabulario.', 'category_mastery', 20, 11, 75)
on conflict (id) do nothing;

-- Nota sobre la OTRA migración huérfana detectada el 27/08/2026:
-- `game_ranking_revoke_anon` (aplicada el 31/07) tampoco tiene archivo, pero NO hace
-- falta reconstruirla: revocaba `execute` a `anon` sobre la firma de 3 argumentos de
-- `get_game_ranking`, firma que 0028 y 0029 ya eliminaron, y el `revoke` equivalente
-- está incorporado en los propios archivos 0027, 0028 y 0029. Un entorno nuevo llega
-- al estado correcto sin ella.
