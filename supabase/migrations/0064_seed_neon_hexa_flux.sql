-- =============================================================================
-- Seed: juego "Neon Hexa Flux", categoría Reconocimiento de Patrones (slug `pattern`).
--
-- Tablero hexagonal en el que se colocan piezas de 1 a 3 fichas; tres o más fichas
-- iguales en contacto se fusionan en una del nivel siguiente, y las fusiones pueden
-- encadenarse. Cada nivel combina una silueta de tablero (hexágono, anillo,
-- mariposa, islas), modificadores (hielo, metal, bombas de turno, portales) y un
-- objetivo que rota (puntuación, limpieza, supervivencia), con la dificultad en
-- olas de 5 niveles (ver KDoc de `HexaLevelGenerator` en el cliente).
--
-- Los niveles se generan en el cliente con semilla derivada del número de nivel:
-- el nivel N es el mismo tablero y la misma secuencia de piezas para todos los
-- jugadores, de modo que tiempos y puntajes son comparables sin guardar ningún
-- tablero en la base de datos.
--
-- El UUID coincide con `GameIds.NEON_HEXA_FLUX` del cliente KMP: sin esta fila, la
-- sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
--
-- Archivo nuevo e idempotente (no reescribe ninguna migración ya aplicada y se
-- puede re-ejecutar sin efecto gracias al `on conflict do nothing`).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        'e83d5f1a-2c47-4b90-a6d3-71f09b8e4c25',
        (select id from public.categories where slug = 'pattern'),
        'neon_hexa_flux', 'Neon Hexa Flux',
        'Coloca piezas en el panal y junta tres fichas iguales para fusionarlas. Encadena fusiones, rompe el hielo y cumple el objetivo de cada nivel.',
        'pattern', 1,
        -- engine_config: parámetros de la curva de dificultad y del balance de
        -- puntuación (ver `HexaLevelGenerator` y `HexaFluxScoring` en el cliente, que
        -- hoy son la fuente de verdad; se exponen aquí para poder afinarlos desde
        -- backend en el futuro sin recompilar).
        --
        -- `wave` es el multiplicador de cada posición de la ola de 5 niveles;
        -- `rampPerCycle` lo que sube la dificultad base por ola completada y
        -- `maxDifficulty` su techo. `gimmickUnlockLevels` da el primer nivel de cada
        -- modificador y `levelsPerMask` cada cuántos niveles cambia la silueta.
        '{"wave": [0.6, 0.85, 1.0, 1.35, 0.5], "rampPerCycle": 0.15, "maxDifficulty": 4, "levelsPerMask": 10, "gimmickUnlockLevels": {"ice": 3, "stone": 6, "turnBomb": 11, "portal": 16}, "mergeGroup": 3, "targetMsPerMove": 4000, "restartPenalty": 60}'::jsonb
    )
on conflict (id) do nothing;
