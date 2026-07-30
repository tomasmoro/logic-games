-- =============================================================================
-- Seed: juego "Hexa Mine", categoría Atención y Concentración (slug `attention`).
--
-- Buscaminas sobre malla HEXAGONAL: cada celda tiene exactamente 6 vecinos (no 8
-- como en la cuadrícula cuadrada), lo que cambia el conteo de minas y la
-- propagación en cascada. Tap corto revela, tap prolongado coloca un escudo.
-- Archivo nuevo e idempotente (no reescribe ninguna migración ya aplicada y se
-- puede re-ejecutar sin efecto).
--
-- El UUID coincide con `GameIds.HEXA_MINE` del cliente KMP: sin esta fila, la
-- sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
-- Es un UUID v4 aleatorio, igual que el de Neon Sudoku Matrix (el patrón de
-- "nibble repetido" de los seeds antiguos ya no tiene valores libres).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '7b3f1e02-9d4c-4a8e-b1c6-2f5a9d0e4c37',
        (select id from public.categories where slug = 'attention'),
        'hexa_mine', 'Hexa Mine',
        'Desactiva el panel hexagonal sin detonar ninguna mina. Toca una celda para revelarla: el número indica cuántas de sus 6 vecinas ocultan una mina. Un toque prolongado coloca un escudo donde crees que hay peligro. El primer toque siempre es seguro.',
        'attention', 1,
        -- engine_config: geometría y balance por dificultad (ver MineDifficulty y
        -- HexaMineConfig en el cliente). Se exponen para poder afinar el balance
        -- desde backend en el futuro sin recompilar; hoy el cliente usa sus
        -- constantes como fuente de verdad.
        -- `maxAdjacent` es 6 —y no 8— precisamente por la topología hexagonal: es
        -- el dato que hace que la paleta de peligro solo necesite colores 1..6.
        '{"maxAdjacent": 6, "baseScore": 1000, "timePenaltyPerSec": 2, "difficulties": [{"level": "facil", "columns": 6, "rows": 8, "mines": 8}, {"level": "medio", "columns": 7, "rows": 10, "mines": 15}, {"level": "dificil", "columns": 8, "rows": 12, "mines": 26}]}'::jsonb
    )
on conflict (id) do nothing;
