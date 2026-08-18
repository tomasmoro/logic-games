-- =============================================================================
-- Neon Legion: velocidad congelada en la ronda 5 y filas de puertas por signo.
--
-- Archivo NUEVO (no se editan las 0034-0038, ya aplicadas). Solo actualiza el
-- `engine_config`; no toca esquema ni políticas.
--
--  1. `speedCapRound` 10 → 5: la velocidad de caída (y con ella la cadencia de
--     los cálculos) queda FIJADA en la ronda 5. La velocidad es el eje de
--     dificultad más romo del juego —acelerar no obliga a pensar mejor, solo
--     recorta el tiempo de leer— así que a partir de la 5 lo que sigue creciendo
--     es lo que exige cabeza: más puertas, cuentas más duras, el enemigo más
--     cerca del óptimo y los láseres.
--
--  2. FILAS POR SIGNO — antes toda fila era mixta (una suma, varias restan), así
--     que la decisión se resolvía buscando la única puerta que sumaba: cero
--     cálculo, y menos aún desde que todas se pintan del mismo color. Ahora cada
--     fila tiene una CLASE y todas sus puertas comparten signo:
--       · `rowPositiveWeight` (0.40) — todas suman. El emparejamiento buscado es
--         `×2` contra `+n`: cuál gana depende del tamaño actual del ejército.
--       · `rowMixedWeight` (0.40) — unas suman y otras restan (la fila de
--         respiro entre las difíciles).
--       · el resto (0.20) — todas restan, se elige la menos mala; el `÷2` contra
--         un `−n` cercano a la mitad vuelve a exigir cálculo.
--
--  3. `minDistinctFraction` 0.15 → 0.08: los emparejamientos que de verdad hacen
--     pensar quedan MUY cerca en valor, y el umbral anterior los rechazaba justo
--     por ser los difíciles.
--
--  4. `minRowGrowth` 1.06 → 1.0: con el 1,06, una ronda que ya había alcanzado
--     su objetivo seguía creciendo un 6 % por fila y, compuesto sobre catorce
--     filas (×2,3), se saltaba el techo de 1000 que sostiene la legibilidad.
-- =============================================================================

update public.games
set engine_config = engine_config
    || '{"speedCapRound": 5, "minRowGrowth": 1.0, "minDistinctFraction": 0.08, "rowPositiveWeight": 0.4, "rowMixedWeight": 0.4, "rowNegativeWeight": 0.2}'::jsonb
where id = '7b3e51f0-4d8a-4c26-9e17-f2a86c40d593';
