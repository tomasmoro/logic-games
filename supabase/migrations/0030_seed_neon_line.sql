-- =============================================================================
-- Seed: juego "Línea Neón" (camino hamiltoniano de un solo trazo), categoría
-- Resolución de Problemas (slug `problem_solving`). Archivo nuevo e idempotente:
-- no reescribe ninguna migración ya aplicada y puede re-ejecutarse sin efecto.
--
-- El UUID coincide con `GameIds.NEON_LINE` del cliente KMP: sin esta fila, la
-- sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
-- Es un UUID v4 aleatorio: el patrón de "nibble repetido" de los seeds antiguos
-- ya no tiene valores libres (1..f están tomados).
--
-- Comparte categoría con "Neon Circuit Flow" (migración 0016) y hasta parte de su
-- generador —ambos niveles se construyen a partir de un camino hamiltoniano—,
-- pero son juegos distintos con progreso y récord propios: allí se unen pares de
-- nodos con varios cables, aquí se cubre el tablero entero con un único trazo.
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '5f2a9c41-8e73-4b06-9d15-3a6e8c204b7f',
        (select id from public.categories where slug = 'problem_solving'),
        'neon_line', 'Línea Neón',
        'Traza una sola línea de luz que pase por todas las celdas libres sin levantar el dedo, esquivando los bloques y sin cruzarte contigo mismo. Desanda el trazo para corregir sobre la marcha.',
        'problem_solving', 1,
        -- engine_config: parámetros de puntuación (ver NeonLineEngine.calculateScore)
        -- para poder afinar la recompensa/penalización desde backend sin recompilar
        -- el cliente. `maxPenalty` = `levelPoints - 1` NO es un valor libre: es lo que
        -- garantiza que una partida muy penalizada nunca puntúe por debajo de un nivel
        -- inferior, así que si se sube `levelPoints` hay que subirlo con él.
        '{"levelPoints": 1000, "efficiencyBonus": 400, "speedBonus": 300, "targetMsPerCell": 900, "restartPenalty": 60, "maxPenalty": 999}'::jsonb
    )
on conflict (id) do nothing;
