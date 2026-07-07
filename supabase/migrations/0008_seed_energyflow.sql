-- =============================================================================
-- Seed: juego "Flujo de Energía", categoría Visión Espacial.
-- Archivo nuevo (nunca se edita una migración ya aplicada). Idempotente
-- (ON CONFLICT DO NOTHING): seguro de re-aplicar.
-- El UUID es fijo y coincide con GameIds.ENERGY_FLOW del cliente KMP, para que la
-- FK de user_progress.game_id resuelva al sincronizar el progreso.
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '55555555-5555-4555-8555-555555555555',
        (select id from public.categories where slug = 'spatial'),
        'energy_flow', 'Flujo de Energía',
        'Gira las tuberías para llevar la energía de la batería a la bombilla y cerrar el circuito.',
        'spatial', 1,
        -- engine_config: parámetros por dificultad. gridByDifficulty = tamaño (lado)
        -- de la rejilla cuadrada en niveles 1..5; penaltyPerExtraRotation = puntos que
        -- se descuentan por cada giro por encima del óptimo. El cliente ya deriva esto
        -- de EnergyFlowGenerator/EnergyFlowEngine; se versiona aquí junto al catálogo
        -- para tener una única fuente documentada de la config del juego.
        '{"gridByDifficulty": [4, 5, 6, 7, 8], "penaltyPerExtraRotation": 25}'::jsonb
    )
on conflict (id) do nothing;
