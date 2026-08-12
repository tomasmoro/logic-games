-- =============================================================================
-- "Quantum Merge": esferas otra vez mayores (×1,2) y tres niveles de dificultad.
--
-- Cambios del cliente que este `engine_config` documenta:
--   * La escala de radios vuelve a crecer un 20% (acumulado ×1,56 sobre el
--     calibrado original).
--   * Nuevo eje **elegible** Fácil / Medio / Difícil. Cada nivel sube el tamaño
--     de las esferas un 10% más, baja la línea de peligro (menos altura de
--     apilado) y añade un tipo más al dispensador.
--
-- Sustituye los valores sueltos que sembró 0032 (`dangerLineY`, `dropY`,
-- `tierRadiusScale`), que describían una geometría única y ya no aplican: ahora
-- la geometría depende del nivel. Se dejan bajo la clave `difficulties` para que
-- la tabla siga siendo un reflejo fiel del juego. El cliente sigue siendo la
-- fuente de verdad y no lee este JSON.
--
-- Nota para el ranking: `GameRankingScopes` separa la tabla mundial por nivel
-- (`get_game_ranking(p_difficulty_level)`, migración 0028), porque aquí subir la
-- dificultad REDUCE el puntaje alcanzable y una tabla única premiaría jugar en
-- Fácil. No hace falta cambio de esquema: el backend ya obedece ese parámetro.
--
-- Idempotente: `-` borra claves y `||` fusiona, así que re-ejecutarla no cambia nada.
-- =============================================================================

update public.games
set engine_config = (engine_config - 'dangerLineY' - 'dropY' - 'tierRadiusScale')
    || '{
          "difficulties": [
            {"level": 1, "name": "Fácil",   "radiusScale": 1.0, "dangerLineY": 28, "spawnTiers": 3},
            {"level": 2, "name": "Medio",   "radiusScale": 1.1, "dangerLineY": 34, "spawnTiers": 4},
            {"level": 3, "name": "Difícil", "radiusScale": 1.2, "dangerLineY": 40, "spawnTiers": 5}
          ],
          "rankedByDifficulty": true
       }'::jsonb,
    updated_at = now()
where slug = 'quantum_merge';
