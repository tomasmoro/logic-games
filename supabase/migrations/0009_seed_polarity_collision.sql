-- =============================================================================
-- Seed: juego "Atracción Geométrica" (Polarity Collision), categoría Visión Espacial.
-- Archivo nuevo e idempotente: no reescribe migraciones previas y puede re-aplicarse.
-- El UUID coincide con GameIds.POLARITY_COLLISION para mantener la FK de progreso.
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '66666666-6666-4666-8666-666666666666',
        (select id from public.categories where slug = 'spatial'),
        'polarity_collision', 'Atracción Geométrica',
        'Rota un hexágono de seis colores para capturar partículas con trayectorias físicas y curvatura magnética.',
        'spatial', 1,
        -- engine_config documenta parámetros jugables para ajustar dificultad desde backend.
        '{"durationMs": 50000, "lives": 3, "spawnIntervalSec": 1.15, "minSpawnIntervalSec": 0.42, "magnetRadiusPx": 190}'::jsonb
    )
on conflict (id) do nothing;

