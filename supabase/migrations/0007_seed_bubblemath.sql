-- =============================================================================
-- Seed: juego "Burbujas de Cálculo", categoría Cálculo Mental.
-- Archivo nuevo (nunca se edita una migración ya aplicada). Idempotente
-- (ON CONFLICT DO NOTHING): seguro de re-aplicar.
-- El UUID es fijo y coincide con GameIds.BUBBLE_MATH del cliente KMP, para que la
-- FK de user_progress.game_id resuelva al sincronizar el progreso.
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '44444444-4444-4444-8444-444444444444',
        (select id from public.categories where slug = 'calculation'),
        'bubble_math', 'Burbujas de Cálculo',
        'Explota la burbuja cuya operación da el número objetivo antes de que toque el suelo.',
        'calculation', 1,
        -- engine_config: parámetros de la física y la puntuación. baseFallMs = tiempo
        -- de caída en la ronda 1; fallStepMs = cuánto se acelera por ronda (con suelo
        -- en minFallMs); lives = vidas iniciales. El cliente ya deriva esto de
        -- BubbleMathEngine; se versiona aquí junto al catálogo para tener una única
        -- fuente documentada de la config del juego.
        '{"lives": 3, "baseFallMs": 7000, "fallStepMs": 280, "minFallMs": 2600}'::jsonb
    )
on conflict (id) do nothing;
