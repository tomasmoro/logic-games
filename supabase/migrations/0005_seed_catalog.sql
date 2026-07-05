-- =============================================================================
-- FASE 4 — Seed del catálogo: 11 categorías cognitivas + 2 juegos de ejemplo.
-- Idempotente (ON CONFLICT DO NOTHING): seguro de re-aplicar.
-- Los UUID de los juegos son fijos y coinciden con GameIds del cliente KMP.
-- =============================================================================

insert into public.categories (slug, name, description, color_hex, sort_order) values
    ('memory',          'Memoria',                    'Recuerda y reproduce información', '#7C4DFF', 1),
    ('logic',           'Pensamiento Lógico',         'Deducción y razonamiento',         '#00E5FF', 2),
    ('problem_solving', 'Resolución de Problemas',    'Encuentra la solución',            '#FF3D8B', 3),
    ('reflexes',        'Reflejos',                   'Velocidad de reacción',            '#00E676', 4),
    ('mental_speed',    'Velocidad Mental',           'Procesa rápido',                   '#FFC107', 5),
    ('attention',       'Atención y Concentración',   'Enfócate y no te distraigas',      '#B2FF59', 6),
    ('spatial',         'Visión Espacial',            'Rota y ubica en el espacio',       '#7C4DFF', 7),
    ('calculation',     'Cálculo Mental',             'Opera con números al vuelo',       '#00E5FF', 8),
    ('language',        'Lenguaje y Vocabulario',     'Palabras y significado',           '#FF3D8B', 9),
    ('flexibility',     'Flexibilidad Cognitiva',     'Cambia de regla sobre la marcha',  '#FFC107', 10),
    ('pattern',         'Reconocimiento de Patrones', 'Detecta la regla oculta',          '#B2FF59', 11)
on conflict (slug) do nothing;

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '11111111-1111-4111-8111-111111111111',
        (select id from public.categories where slug = 'memory'),
        'sequence_memory', 'Memoria de Secuencias',
        'Repite la secuencia de tiles que se ilumina; cada ronda añade un paso.',
        'memory', 1, '{"showMs": 600, "gapMs": 220}'::jsonb
    ),
    (
        '22222222-2222-4222-8222-222222222222',
        (select id from public.categories where slug = 'reflexes'),
        'reflex_tap', 'Reflejos de Toque Rápido',
        'Toca en cuanto aparezca el objetivo; se mide tu tiempo de reacción.',
        'reflexes', 1, '{"rounds": 5, "waitMinMs": 1200, "waitMaxMs": 3200}'::jsonb
    )
on conflict (id) do nothing;
