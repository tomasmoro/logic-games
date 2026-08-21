-- =============================================================================
-- Hexa Orbit: baja el techo de rapidez a x2 y ralentiza la rampa.
--
-- `maxSpeed` pasa de una constante independiente (8.6, ~x3.07 de la inicial) a
-- el DOBLE exacto de `initialSpeed` (5.6): por encima de x2 el jugador no llega
-- a reaccionar dentro de la ventana de alarma (`escapeAlertTiles`, ver
-- 0040_hexa_orbit_lookahead.sql) y el juego pasa a decidirse por azar. Que sea
-- el doble de `initialSpeed` y no un número suelto también significa que si el
-- balance de `initialSpeed` cambia en el futuro, el techo escala con él en la
-- misma proporción en vez de desajustarse.
--
-- `speedRampPerSec` baja de 0.08 a 0.03 (rampa más lenta): con un techo más
-- bajo, la rampa anterior lo habría alcanzado casi de inmediato y la sensación
-- de "el juego se vuelve más frenético" se habría perdido en los primeros
-- segundos. Con 0.03 se tardan más de 90 s en llegar al techo, así que la
-- mayoría de partidas terminan por fuga mucho antes de aplanarse.
--
-- Archivo nuevo (no reescribe las migraciones ya aplicadas) e idempotente:
-- `jsonb ||` sobreescribe las claves y se puede re-ejecutar.
-- =============================================================================

update public.games
set engine_config = engine_config || '{"maxSpeed": 5.6, "speedRampPerSec": 0.03}'::jsonb
where id = 'a9f2c86b-3e74-4d51-9c08-6b1d40e7a25f';
