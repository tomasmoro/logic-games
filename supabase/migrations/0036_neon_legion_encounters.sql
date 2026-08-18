-- =============================================================================
-- Neon Legion: reubicación de los dos encuentros con naves.
--
-- Archivo NUEVO (no se editan la 0034 ni la 0035, ya aplicadas). Solo actualiza
-- el `engine_config` del juego; no toca esquema ni políticas.
--
-- Qué cambia y por qué:
--
--  1. ESCOLTA CON LÁSER VERTICAL — antes se sorteaban 1-2 apariciones en filas
--     al azar de la carrera, compitiendo con la decisión de las puertas. Ahora
--     es UN evento por ronda, siempre al cruzar la última puerta: entra a la vez
--     que asoma el ejército enemigo, como su escolta, y el tramo final pasa a
--     ser el clímax de la ronda. Por eso desaparece `verticalEventsPerRound` y
--     aparece `verticalLaserAtRoundEnd`.
--
--  2. `enemyGap` 0.35 → 1.0 — ese tramo final tiene que durar lo suficiente para
--     que la escolta cargue y dispare antes del choque. Con el hueco anterior el
--     combate empezaba con el láser a medio cargar: aparecía y no llegaba a
--     disparar nunca. Ahora dura 3,2 s en la ronda 1 y 1,9 s desde la 10, por
--     encima de la carga (2,0 s → 0,8 s).
--
--  3. BARRIDO HORIZONTAL — la nave ya no se queda fija arriba: baja hacia la
--     legión durante los `quizGraceSec` (los 5 s sin penalización) y, al frenar
--     encima, empieza a destruir naves con explosiones, exactamente como el
--     choque final. El drenaje de tropas que ya existía no cambia de fórmula;
--     lo que cambia es que ahora SE VE. Es dato de render, así que aquí solo se
--     dejan documentados los tramos (`sweepDescendsDuringGrace`).
-- =============================================================================

update public.games
set engine_config = engine_config
    - 'verticalEventsPerRound'
    || '{"enemyGap": 1.0, "verticalLaserAtRoundEnd": true, "sweepDescendsDuringGrace": true}'::jsonb
where id = '7b3e51f0-4d8a-4c26-9e17-f2a86c40d593';
