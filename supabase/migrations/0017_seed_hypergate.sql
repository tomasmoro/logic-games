-- =============================================================================
-- Seed: juego "Hypergate" (escudo de polaridad), categoría Reflejos. Entrena
-- reacción pura y flexibilidad cognitiva (conmutar de regla al vuelo). Archivo
-- nuevo e idempotente: no reescribe migraciones previas y puede re-aplicarse. El
-- UUID coincide con GameIds.HYPERGATE para mantener la FK de progreso (al
-- sincronizar un resultado, `game_results.game_id` / `player_game_progress.game_id`
-- referencian esta fila).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
        (select id from public.categories where slug = 'reflexes'),
        'hypergate', 'Hypergate',
        'Un escudo central alterna entre dos polaridades de color. Toca en cualquier parte para conmutarlo y haz que su color coincida con cada proyectil justo antes del impacto: iguala para absorber, falla y chocarás. Corrida por tiempo.',
        'reflexes', 1,
        -- engine_config: parámetros de puntuación (ver HypergateEngine) para poder
        -- afinar recompensa/penalización desde backend sin recompilar el cliente.
        '{"absorbBaseScore": 70, "speedScoreFactor": 0.12, "mismatchPenalty": 90, "survivalBonusPerSec": 12}'::jsonb
    )
on conflict (id) do nothing;
