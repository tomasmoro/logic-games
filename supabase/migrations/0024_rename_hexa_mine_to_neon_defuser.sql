-- =============================================================================
-- Rename: "Hexa Mine" (malla hexagonal) → "Neon Defuser" (celdas cuadradas).
--
-- El juego se migró de una malla hexagonal de 6 vecinos a una rejilla cuadrada
-- clásica de 8 vecinos, y se subió la densidad de minas porque las partidas
-- resultaban demasiado fáciles. El nombre y el slug antiguos ("hexa_mine")
-- describían la geometría, así que dejaban de ser ciertos.
--
-- Se conserva **el mismo UUID** (`GameIds.NEON_DEFUSER` en el cliente) a
-- propósito: ya puede haber filas en `user_progress` / `player_game_progress`
-- apuntando a él, y cambiarlo las huérfanaría sin ganar nada — el identificador
-- no describe la geometría del juego.
--
-- Archivo nuevo e idempotente (no reescribe la migración 0023 ya aplicada; el
-- `update` se puede re-ejecutar sin efecto adicional).
-- =============================================================================

update public.games
set
    slug = 'neon_defuser',
    name = 'Neon Defuser',
    description = 'Desactiva el panel sin detonar ninguna mina. Toca una celda para revelarla: el número indica cuántas de sus 8 celdas contiguas ocultan una mina. Un toque prolongado coloca un escudo donde crees que hay peligro. El primer toque siempre es seguro.',
    -- engine_config: geometría y balance por dificultad (ver MineDifficulty y
    -- DefuserConfig en el cliente). `maxAdjacent` pasa de 6 a 8 al abandonar la
    -- malla hexagonal: es lo que fija el rango de la escala de color de peligro.
    -- Las densidades (~16 %, ~22 %, ~26 %) van por encima del Buscaminas clásico
    -- (~12 / 16 / 21 %) precisamente para corregir la falta de dificultad.
    engine_config = '{"maxAdjacent": 8, "baseScore": 1000, "timePenaltyPerSec": 2, "difficulties": [{"level": "facil", "columns": 8, "rows": 10, "mines": 13}, {"level": "medio", "columns": 9, "rows": 12, "mines": 24}, {"level": "dificil", "columns": 10, "rows": 14, "mines": 37}]}'::jsonb,
    updated_at = now()
where id = '7b3f1e02-9d4c-4a8e-b1c6-2f5a9d0e4c37';
