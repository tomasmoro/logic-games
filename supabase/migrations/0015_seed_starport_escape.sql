-- =============================================================================
-- Seed: juego "Neon Starport Escape" (Rush Hour espacial), categoría Pensamiento
-- Lógico. Archivo nuevo e idempotente: no reescribe migraciones previas y puede
-- re-aplicarse. El UUID coincide con GameIds.STARPORT_ESCAPE para mantener la FK
-- de progreso (al sincronizar un resultado, `game_results.game_id` /
-- `player_game_progress.game_id` referencian esta fila).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        (select id from public.categories where slug = 'logic'),
        'starport_escape', 'Neon Starport Escape',
        'Desliza las naves a lo largo de su eje para despejar el hangar 6×6 y escolta a la nave insignia hasta la esclusa en el mínimo de movimientos.',
        'logic', 1,
        -- engine_config: parámetros de puntuación (ver StarportEngine.calculateScore)
        -- para poder afinar la penalización desde backend sin recompilar el cliente.
        '{"pointsPerLevel": 1000, "penaltyPerExtraMove": 50}'::jsonb
    )
on conflict (id) do nothing;
