-- =============================================================================
-- Seed: juego "Neon Legion", categoría Velocidad Mental (slug `mental_speed`).
-- Es el PRIMER juego de esa categoría: hasta esta migración ninguna fila de
-- `games` apuntaba a ella.
--
-- Runner infinito de carriles: el jugador guía un ejército de puntos de luz,
-- cruza puertas matemáticas (+n, −n, ×n, ÷n) que alteran sus tropas, esquiva
-- láseres de naves (verticales por carril y un barrido horizontal que abre un
-- examen de opciones múltiples) y choca contra un ejército enemigo al final de
-- cada ronda. El enemigo se dimensiona RELATIVO al recorrido óptimo de la ronda
-- (ceil(óptimo × factor creciente 0.55→0.92)), no con una constante por ronda:
-- así la partida siempre puede perderse aunque el ejército crezca de forma
-- multiplicativa. Motor propio en `commonMain` (`LegionEngine`).
--
-- El UUID coincide con `GameIds.NEON_LEGION` del cliente KMP: sin esta fila, la
-- sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
--
-- Archivo nuevo e idempotente (no reescribe ninguna migración ya aplicada y se
-- puede re-ejecutar sin efecto gracias al `on conflict do nothing`).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '7b3e51f0-4d8a-4c26-9e17-f2a86c40d593',
        (select id from public.categories where slug = 'mental_speed'),
        'neon_legion', 'Neon Legion',
        'Guía a tu legión de luz por los carriles: cruza las puertas que multiplican tus tropas, esquiva los láseres y aplasta al ejército enemigo al final de cada ronda.',
        'mental_speed', 1,
        -- engine_config: balance del juego (ver LegionBalance en el cliente, que
        -- hoy es la fuente de verdad; se expone aquí para poder afinarlo desde
        -- backend en el futuro sin recompilar).
        --
        -- `speedGrowthPerRound` es multiplicativo con techo en `speedCapRound`:
        -- +6 % compuesto por ronda hasta ~1,7× la velocidad base; a partir de ahí
        -- la dificultad la ponen los láseres y el enemigo, no el reloj.
        --
        -- `enemyFactorMin/Max`: fracción del recorrido ÓPTIMO de la ronda que
        -- exige el enemigo (interpolada hasta `enemyFactorCapRound`); los Jefes
        -- (cada `bossEvery` rondas) suman `bossFactorBonus` con techo 0.97 —
        -- exigir el 100 % del óptimo sería imposible.
        --
        -- `laserChargeBaseSec` baja 0,1 s por ronda con suelo en
        -- `laserChargeFloorSec` (irreaccionable por debajo). `quizOptions` sube
        -- en escalones 3→4→5 con la ronda (rondas 5-8, 9-13 y 14+).
        '{"initialTroops": 10, "baseSpeed": 0.28, "speedGrowthPerRound": 0.06, "speedCapRound": 10, "laneExpansionRound": 7, "gateRowsBase": 5, "gateRowsMax": 14, "bossEvery": 10, "enemyFactorMin": 0.55, "enemyFactorMax": 0.92, "enemyFactorCapRound": 20, "bossFactorBonus": 0.04, "laserChargeBaseSec": 2.0, "laserChargeFloorSec": 0.8, "doubleLaserMinRound": 10, "sweepMinRound": 5, "quizTimeLimitSec": 10, "quizGraceSec": 5, "quizMaxDrainFraction": 0.5, "quizOptions": [3, 4, 5], "revivePenalty": 800}'::jsonb
    )
on conflict (id) do nothing;
