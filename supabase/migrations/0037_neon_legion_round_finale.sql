-- =============================================================================
-- Neon Legion: afinado del final de ronda.
--
-- Archivo NUEVO (no se editan la 0034/0035/0036, ya aplicadas). Solo actualiza
-- el `engine_config`; no toca esquema ni políticas.
--
--  1. HUECO FINAL CONDICIONAL — el `enemyGap` de 1.0 se puso para que la escolta
--     tuviera sitio donde cargar el láser, pero en las rondas 1-2 (que no llevan
--     escolta) eso eran ~3,2 s mirando una pista vacía. Ahora el hueco depende
--     de la ronda: `enemyGapBase` 0.4 sin escolta (~0,8 s de aproximación) y
--     `enemyGapEscorted` 1.0 con ella. Se sustituye la clave `enemyGap`.
--
--  2. ENTRADA SIMULTÁNEA — la escolta ya no se lanza al cruzar la última puerta
--     sino al hacerse VISIBLE el ejército enemigo (`enemyVisibleY`), que es el
--     mismo umbral con el que el render decide empezar a dibujarlo. Escolta y
--     ejército son la misma amenaza y ahora entran juntos en escena (0,12-0,18 s
--     tras la última puerta). Desde ahí la escolta dispone de 2,6 s (ronda 3) a
--     1,8 s (ronda 10+) para cargar y disparar, holgado sobre su carga.
--
--  3. La escala visual de la escolta (+40 % la nave, +50 % el rayo) es dato de
--     render y no viaja en engine_config; se deja anotada para que el balance y
--     lo que se ve en pantalla se puedan leer juntos.
-- =============================================================================

update public.games
set engine_config = engine_config
    - 'enemyGap'
    || '{"enemyGapBase": 0.4, "enemyGapEscorted": 1.0, "enemyVisibleY": -0.15, "verticalShipScale": 1.4, "verticalLaserScale": 1.5}'::jsonb
where id = '7b3e51f0-4d8a-4c26-9e17-f2a86c40d593';
