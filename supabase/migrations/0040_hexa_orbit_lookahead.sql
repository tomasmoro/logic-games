-- =============================================================================
-- Hexa Orbit: alarga el haz proyectado y separa el aviso de la alarma.
--
-- El horizonte del trazado (`lookaheadTiles`) pasa de 4 a 9 azulejos: con 4, a
-- rapidez alta no daba tiempo a preparar más de un giro antes de que el puntero
-- llegara, así que el juego se volvía reactivo en vez de táctico.
--
-- El cambio obliga a introducir `escapeAlertTiles`. Con un horizonte de 9 sobre
-- un tablero de 3 anillos (la frontera está a 3-7 azulejos), el haz alcanza el
-- borde CASI SIEMPRE; si la alarma roja siguiera atada a "hay fuga dentro del
-- horizonte" estaría encendida de forma permanente y dejaría de informar. Por
-- eso el haz avisa a 9 azulejos pero solo *alarma* dentro de 4 — que es la
-- ventana de reacción que el juego ya tenía, de modo que la dificultad no
-- cambia: solo se ve más lejos.
--
-- `escapeAlertTiles` es además el criterio de la métrica de precisión del
-- resultado (`accuracy_percentage` = % del tiempo sin fuga inminente); medirla
-- contra el horizonte completo la dejaría clavada cerca de 0 en cualquier
-- partida y no distinguiría a nadie.
--
-- Archivo nuevo (no reescribe `0039_seed_hexa_orbit.sql`, ya aplicada) e
-- idempotente: `jsonb ||` sobreescribe las claves y se puede re-ejecutar.
-- =============================================================================

update public.games
set engine_config = engine_config || '{"lookaheadTiles": 9, "escapeAlertTiles": 4}'::jsonb
where id = 'a9f2c86b-3e74-4d51-9c08-6b1d40e7a25f';
