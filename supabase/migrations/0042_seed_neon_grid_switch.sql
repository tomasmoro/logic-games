-- =============================================================================
-- Seed: juego "Neon Grid Switch", categoría Reconocimiento de Patrones (slug
-- `pattern`) — primer juego de esta categoría en el catálogo.
--
-- Variante de Lights Out con progresión de tamaño de matriz: cada etapa crece
-- la cuadrícula (3×3 → 4×4 → 5×5 → 6×6) y, a partir de la 6×6, sigue subiendo
-- la dificultad con más toques de desorden. Tocar una celda conmuta su estado y
-- el de sus vecinas ortogonales existentes; la etapa se resuelve al apagar
-- todas las luces. El generador SIEMPRE construye un tablero resoluble (parte
-- del resuelto y aplica toques aleatorios — ver KDoc de `GridSwitchGenerator`
-- en el cliente), así que no hay niveles imposibles. Motor propio en
-- `commonMain` (`GridSwitchEngine`).
--
-- El UUID coincide con `GameIds.NEON_GRID_SWITCH` del cliente KMP: sin esta
-- fila, la sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
--
-- El juego todavía NO está publicado en el catálogo de la app
-- (`GameCatalog.kt`: `published = false`, en pulido de UI) — el seed va igual
-- para que el motor pueda guardar partidas de prueba sin romper la FK; cuando
-- se publique no hace falta tocar esta fila.
--
-- Archivo nuevo e idempotente (no reescribe ninguna migración ya aplicada y se
-- puede re-ejecutar sin efecto gracias al `on conflict do nothing`).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '16c38bc2-a0e1-46ec-b3a0-3aa1ea0659d2',
        (select id from public.categories where slug = 'pattern'),
        'neon_grid_switch', 'Neon Grid Switch',
        'Apaga todas las luces de la cuadrícula. Cada toque conmuta esa celda y sus vecinas: encuentra el patrón antes de que la cuadrícula crezca.',
        'pattern', 1,
        -- engine_config: balance del juego (ver GridSwitchStages en el cliente, que
        -- hoy es la fuente de verdad; se expone aquí para poder afinarlo desde
        -- backend en el futuro sin recompilar).
        --
        -- `sizeCapStage` = etapa a partir de la cual el tablero deja de crecer y
        -- queda fijo en `maxGridSize` (6×6); de ahí en adelante la dificultad sube
        -- solo por `extraScrambleTouchesPerStage` toques de desorden extra por
        -- etapa. `baseScrambleTouches` no se lista aparte porque es siempre
        -- `lado²` de la etapa (ver `scrambleTouchesForStage`), no un parámetro
        -- libre.
        '{"minGridSize": 3, "maxGridSize": 6, "sizeCapStage": 4, "extraScrambleTouchesPerStage": 6}'::jsonb
    )
on conflict (id) do nothing;
