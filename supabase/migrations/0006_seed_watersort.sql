-- =============================================================================
-- Seed: juego "Ordena las Pociones" (Water Sort), categoría Pensamiento Lógico.
-- Archivo nuevo (nunca se edita una migración ya aplicada). Idempotente
-- (ON CONFLICT DO NOTHING): seguro de re-aplicar.
-- El UUID es fijo y coincide con GameIds.WATER_SORT del cliente KMP, para que la
-- FK de user_progress.game_id resuelva al sincronizar el progreso.
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '33333333-3333-4333-8333-333333333333',
        (select id from public.categories where slug = 'logic'),
        'water_sort', 'Ordena las Pociones',
        'Vierte los colores entre tubos hasta que cada uno quede de un solo color.',
        'logic', 1,
        -- engine_config: parámetros por dificultad. capacity = segmentos por tubo;
        -- colorsByDifficulty = nº de colores en niveles 1..5; emptyTubes = tubos de
        -- maniobra vacíos. El cliente ya deriva esto de LevelConfig; se guarda aquí
        -- para tener la config del juego versionada junto al catálogo.
        '{"capacity": 4, "emptyTubes": 2, "colorsByDifficulty": [3, 4, 5, 6, 8]}'::jsonb
    )
on conflict (id) do nothing;
