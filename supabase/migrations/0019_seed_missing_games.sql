-- =============================================================================
-- FASE 5 — Reconciliación del catálogo: siembra los juegos de GameIds (cliente
-- KMP) que faltaban en la tabla `games` de Supabase.
--
-- Motivo: varios juegos jugables ya existían en GameCatalog.kt pero sus filas no
-- estaban en la BD remota (algunos tenían archivos de seed locales 0008/0009/
-- 0011/0014/0017 que nunca se aplicaron; Crucigrama Neón y Neon Pulse no tenían
-- ninguno). Sin la fila en `games`, la sincronización remota falla por la FK
-- `user_progress.game_id → games.id`.
--
-- Idempotente (ON CONFLICT (id) DO NOTHING): seguro de re-aplicar y compatible
-- con los seeds locales antiguos si alguna vez se aplicaran. Los UUID son fijos
-- y coinciden 1:1 con GameIds del cliente.
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '55555555-5555-4555-8555-555555555555',
        (select id from public.categories where slug = 'spatial'),
        'energy_flow', 'Flujo de Energía',
        'Gira las tuberías para llevar la energía de la batería a la bombilla y cerrar el circuito.',
        'spatial', 1,
        -- gridByDifficulty = lado de la rejilla en niveles 1..5; penaltyPerExtraRotation
        -- = puntos descontados por cada giro por encima del óptimo (ver EnergyFlowEngine).
        '{"gridByDifficulty": [4, 5, 6, 7, 8], "penaltyPerExtraRotation": 25}'::jsonb
    ),
    (
        '66666666-6666-4666-8666-666666666666',
        (select id from public.categories where slug = 'spatial'),
        'polarity_collision', 'Atracción Geométrica',
        'Rota un hexágono de seis colores para capturar partículas con trayectorias físicas y curvatura magnética.',
        'spatial', 1,
        '{"durationMs": 50000, "lives": 3, "spawnIntervalSec": 1.15, "minSpawnIntervalSec": 0.42, "magnetRadiusPx": 190}'::jsonb
    ),
    (
        '77777777-7777-4777-8777-777777777777',
        (select id from public.categories where slug = 'language'),
        'crucigrama_neon', 'Crucigrama Neón',
        'Rellena un crucigrama entrelazado escribiendo con el teclado inferior las palabras que resuelven cada pista; forma también las palabras extra (bonus) que se pueden armar con las mismas letras.',
        'language', 1,
        -- Puntuación (ver CrucigramaNeonEngine.onCorrect/onExtraFound): palabra de
        -- rejilla = len*gridWordBase + combo*comboBonus + nivel*levelBonus; palabra
        -- extra = len*extraWordBase + nivel*extraLevelBonus.
        '{"gridWordBase": 150, "comboBonus": 45, "levelBonus": 20, "extraWordBase": 90, "extraLevelBonus": 10}'::jsonb
    ),
    (
        '88888888-8888-4888-8888-888888888888',
        (select id from public.categories where slug = 'language'),
        'word_connect', 'Palabras Conectadas',
        'Une las letras de una rueda arrastrando el dedo para formar todas las palabras ocultas del nivel.',
        'language', 1,
        '{"baseLetterPoints": 120, "comboBonus": 40, "levelBonus": 15}'::jsonb
    ),
    (
        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        (select id from public.categories where slug = 'language'),
        'neon_lexicon', 'Sopa de Letras Neón',
        'Desliza el dedo para trazar las palabras escondidas en la cuadrícula (horizontal, vertical o diagonal); encuéntralas todas para superar el nivel.',
        'language', 1,
        '{"wordScoreBase": 100, "levelBonus": 25}'::jsonb
    ),
    (
        'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
        (select id from public.categories where slug = 'reflexes'),
        'hypergate', 'Hypergate',
        'Un escudo central alterna entre dos polaridades de color. Toca en cualquier parte para conmutarlo y haz que su color coincida con cada proyectil justo antes del impacto: iguala para absorber, falla y chocarás. Corrida por tiempo.',
        'reflexes', 1,
        '{"absorbBaseScore": 70, "speedScoreFactor": 0.12, "mismatchPenalty": 90, "survivalBonusPerSec": 12}'::jsonb
    ),
    (
        'ffffffff-ffff-4fff-8fff-ffffffffffff',
        (select id from public.categories where slug = 'reflexes'),
        'neon_pulse', 'Neon Pulse',
        'Toca los nodos de energía antes de que su anillo de tiempo se contraiga hasta colapsar; evita los nodos trampa que aparecen en la fase avanzada. Corrida por tiempo con vidas limitadas.',
        'reflexes', 1,
        -- Constantes de dominio (ver NeonPulseModel): duración de partida, vidas,
        -- puntos por acierto y aparición de trampas.
        '{"durationMs": 30000, "lives": 3, "pointsPerHit": 100, "trapUnlockMs": 15000, "trapSpawnChance": 0.3}'::jsonb
    )
on conflict (id) do nothing;
