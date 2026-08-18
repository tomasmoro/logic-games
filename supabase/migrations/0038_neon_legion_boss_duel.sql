-- =============================================================================
-- Neon Legion: duelo contra el Jefe + subida de dificultad.
--
-- Archivo NUEVO (no se editan las 0034-0037, ya aplicadas). Solo actualiza el
-- `engine_config`; no toca esquema ni políticas.
--
--  1. `gateRowSpacing` 0.65 → 0.585: los cálculos llegan un 10 % más seguido.
--     Es la palanca de dificultad más directa del juego — cambia el ritmo de
--     TODAS las rondas a la vez.
--
--  2. La posición de la legión ya NO se reinicia entre rondas: se queda donde el
--     jugador tenga el dedo (`keepAimBetweenRounds`). Recolocarla en el centro
--     le arrancaba el control justo al empezar la ronda, con la primera fila ya
--     cayendo.
--
--  3. DUELO CONTRA EL JEFE (rondas 10, 20, 30…) — sustituye al choque
--     instantáneo por un intercambio por tiempo: la legión le quita
--     `bossDamagePerShipSec` de vida por nave y segundo, mientras el Jefe barre
--     cada `bossShotIntervalSec` fulminando el `bossShotKillFraction` de las
--     naves. Su vida es `bossHpPerTroop` × las tropas de entrada.
--
--     NOTA DE BALANCE (importante si se retoca): al depender la vida de las
--     tropas del propio jugador, vida y daño escalan juntos y se cancelan — el
--     duelo sale SIEMPRE igual (victoria en ~5,2 s conservando ~49 % de la
--     legión), se llegue con 40 naves o con 900. Es un peaje espectacular, no un
--     examen. Para que la ronda del Jefe filtre de verdad habría que anclar
--     `bossHp` a algo independiente del jugador (p. ej. al objetivo de la ronda,
--     `targetTroopsBase × targetGrowthPerRound^(ronda-1)`), y entonces llegar
--     con más naves sí decidiría el resultado.
-- =============================================================================

update public.games
set engine_config = engine_config
    || '{"gateRowSpacing": 0.585, "keepAimBetweenRounds": true, "bossHpPerTroop": 4, "bossShotIntervalSec": 2.0, "bossShotKillFraction": 0.3, "bossDamagePerShipSec": 1.0, "bossBeamDurationSec": 0.45, "bossDriftCycleSec": 3.2}'::jsonb
where id = '7b3e51f0-4d8a-4c26-9e17-f2a86c40d593';
