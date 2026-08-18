-- =============================================================================
-- Ajuste de balance de "Neon Legion" tras la primera sesión de juego.
--
-- Archivo NUEVO (no se edita la 0034, ya aplicada). Solo actualiza el
-- `engine_config` del juego; no toca esquema ni políticas.
--
-- Qué se corrigió y por qué:
--
--  1. VELOCIDAD: `baseSpeed` 0.28 → 0.252 (un 10 % más lenta) y `gateRowSpacing`
--     0.45 → 0.65. Con las filas tan juntas la ronda se resolvía a reflejos y no
--     daba tiempo a comparar las puertas, que es la decisión que el juego pide.
--     Con el nuevo espaciado hay ~2,6 s entre cálculos en la ronda 1.
--
--  2. ESCALA DEL EJÉRCITO: el generador elegía "la mejor puerta" con libertad
--     (un ×2 o un +100 %), así que el recorrido óptimo se DUPLICABA por fila:
--     con 5-14 filas por ronda, compuesto entre rondas, en la ronda 5 el
--     ejército pasaba de 30 millones y los rótulos dejaban de significar algo.
--     Ahora cada ronda tiene un TAMAÑO OBJETIVO (`targetTroopsBase` ×
--     `targetGrowthPerRound`, con techo `troopsSoftCap`) que el generador
--     reparte entre sus filas y recalcula fila a fila (autocorrector). El
--     ejército queda acotado a 0..1000 toda la partida: 40 al acabar la ronda 1,
--     ~176 en la 5 y el techo de 1000 desde la 10.
--
--  3. El `×2` ya solo aparece con ejércitos pequeños (`multiplyMaxBase`) y
--     siempre que duplicar no se pase del objetivo de la ronda; sin esa segunda
--     condición la curva no era monótona (la ronda 3 acababa por encima de la 4).
--
-- El color de las puertas (todas iguales, para que haya que LEER la operación en
-- vez de elegir por color) es decisión de render y no viaja en engine_config.
-- =============================================================================

update public.games
set engine_config = '{"initialTroops": 10, "baseSpeed": 0.252, "speedGrowthPerRound": 0.06, "speedCapRound": 10, "gateRowSpacing": 0.65, "laneExpansionRound": 7, "gateRowsBase": 5, "gateRowsMax": 14, "troopsSoftCap": 1000, "targetTroopsBase": 40, "targetGrowthPerRound": 1.45, "multiplyMaxBase": 60, "minRowGrowth": 1.06, "maxRowGrowth": 1.8, "bossEvery": 10, "enemyFactorMin": 0.55, "enemyFactorMax": 0.92, "enemyFactorCapRound": 20, "bossFactorBonus": 0.04, "laserChargeBaseSec": 2.0, "laserChargeFloorSec": 0.8, "doubleLaserMinRound": 10, "sweepMinRound": 5, "quizTimeLimitSec": 10, "quizGraceSec": 5, "quizMaxDrainFraction": 0.5, "quizOptions": [3, 4, 5], "revivePenalty": 800}'::jsonb
where id = '7b3e51f0-4d8a-4c26-9e17-f2a86c40d593';
