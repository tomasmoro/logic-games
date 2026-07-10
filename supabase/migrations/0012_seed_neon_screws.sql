-- =============================================================================
-- Seed: juego "Tornillos Neón" (Neon Screws & Bolts), categoría Visión Espacial.
-- Archivo nuevo e idempotente: no reescribe migraciones previas y puede re-aplicarse.
-- El UUID coincide con GameIds.NEON_SCREWS para mantener la FK de progreso (al
-- sincronizar un resultado, `player_game_progress.game_id` referencia esta fila).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '99999999-9999-4999-8999-999999999999',
        (select id from public.categories where slug = 'spatial'),
        'neon_screws', 'Tornillos Neón',
        'Desatornilla y recoloca los pernos para soltar todas las placas del tablero: las placas tapan agujeros y cuelgan si les queda un solo tornillo.',
        'spatial', 1,
        -- engine_config: parámetros de puntuación (ver ScrewGameEngine.calculateScore)
        -- para poder afinar la recompensa desde backend sin recompilar el cliente.
        '{"basePerLevel": 1000, "penaltyPerExtraMove": 50}'::jsonb
    )
on conflict (id) do nothing;
