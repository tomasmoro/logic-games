-- =============================================================================
-- Seed: juego "Neon Block Grid" (Block Puzzle 8×8), categoría Visión Espacial.
-- Archivo nuevo e idempotente: no reescribe migraciones previas y puede re-aplicarse.
-- El UUID coincide con GameIds.NEON_BLOCK_GRID para mantener la FK de progreso (al
-- sincronizar un resultado, `player_game_progress.game_id` referencia esta fila).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        (select id from public.categories where slug = 'spatial'),
        'neon_block_grid', 'Neon Block Grid',
        'Arrastra poliominós al tablero 8×8 y completa filas o columnas para romperlas; la partida termina cuando ninguna pieza de la mano cabe.',
        'spatial', 1,
        -- engine_config: parámetros de puntuación (ver BlockGridEngine/scoreForLines)
        -- para poder afinar la recompensa desde backend sin recompilar el cliente.
        '{"pointsPerBlock": 1, "lineScoreBase": 10, "comboExponent": 2}'::jsonb
    )
on conflict (id) do nothing;
