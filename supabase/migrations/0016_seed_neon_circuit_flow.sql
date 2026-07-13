-- =============================================================================
-- Seed: juego "Neon Circuit Flow" (Flow Free de circuitos), categoría Resolución
-- de Problemas. Archivo nuevo e idempotente: no reescribe migraciones previas y
-- puede re-aplicarse. El UUID coincide con GameIds.NEON_CIRCUIT para mantener la
-- FK de progreso (al sincronizar un resultado, `game_results.game_id` /
-- `player_game_progress.game_id` referencian esta fila).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
        (select id from public.categories where slug = 'problem_solving'),
        'neon_circuit_flow', 'Neon Circuit Flow',
        'Arrastra desde cada nodo y tiende un cable de luz hasta su gemelo del mismo color, sin que dos cables se crucen. Conecta todos los pares (y llena la placa) para energizar el circuito.',
        'problem_solving', 1,
        -- engine_config: parámetros de puntuación (ver NeonCircuitEngine.calculateScore)
        -- para poder afinar la recompensa/penalización desde backend sin recompilar
        -- el cliente.
        '{"pointsPerLevel": 1000, "boardFullBonus": 500, "penaltyPerCut": 25}'::jsonb
    )
on conflict (id) do nothing;
