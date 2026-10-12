-- =============================================================================
-- 0065 — `user_progress.difficulty_level` admite cualquier nivel (1..1000)
-- -----------------------------------------------------------------------------
-- PROBLEMA
-- La restricción original (0001) limitaba `difficulty_level` a 1..5, pensando en
-- "dificultad elegida". Pero los juegos LEVELED (Water Sort, Energy Flow,
-- Hyper-Cube, Shikaku…) guardan ahí el NIVEL jugado para separar su ranking por
-- nivel (ver `GameRankingScopes`, `get_game_ranking(p_difficulty_level)`). Pasado
-- el nivel 5 el insert de `submit_game_result` violaba el check, la partida no
-- se subía y el jugador se quedaba sin sincronizar ni ranking.
--
-- SOLUCIÓN
-- Se amplía el tope a 1000: sigue siendo una red de seguridad contra datos
-- corruptos, pero deja de ser un límite de contenido. `smallint` admite hasta
-- 32767, así que no hace falta cambiar el tipo.
--
-- ALCANCE
-- Solo `user_progress`. `games.difficulty_level` (dificultad del catálogo) y
-- `events.difficulty_level` (0051) no cambian: no guardan el nivel jugado.
-- =============================================================================

alter table public.user_progress
    drop constraint if exists user_progress_difficulty_level_check;

alter table public.user_progress
    add constraint user_progress_difficulty_level_check
    check (difficulty_level between 1 and 1000);

comment on column public.user_progress.difficulty_level is
    'Dificultad elegida o NIVEL jugado (según el juego); separa las tablas de ranking por nivel. 1..1000.';
