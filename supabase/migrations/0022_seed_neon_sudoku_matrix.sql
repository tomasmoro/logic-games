-- =============================================================================
-- Seed: juego "Neon Sudoku Matrix", categoría Pensamiento Lógico (slug `logic`).
--
-- Sudoku 9x9 clásico sobre panel holográfico neón: pistas fijas, escritura de
-- dígitos, modo notas (lápiz) y detección de choques en fila/columna/bloque.
-- Archivo nuevo e idempotente (no reescribe ninguna migración ya aplicada y se
-- puede re-ejecutar sin efecto).
--
-- El UUID coincide con `GameIds.NEON_SUDOKU_MATRIX` del cliente KMP: sin esta
-- fila, la sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
-- Es un UUID v4 aleatorio: el patrón de "nibble repetido" de los seeds antiguos
-- ya no tiene valores libres (1..f están tomados).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '9c40ca31-4a69-4d6b-a903-355098c129ee',
        (select id from public.categories where slug = 'logic'),
        'neon_sudoku_matrix', 'Neon Sudoku Matrix',
        'Completa la matriz 9x9: cada fila, cada columna y cada bloque 3x3 deben contener los dígitos del 1 al 9 sin repetirse. Toca una celda, elige un número y activa el lápiz para anotar tus hipótesis.',
        'logic', 1,
        -- engine_config: constantes de reglas y balance (ver NeonSudokuConfig en el
        -- cliente). Se exponen para poder afinar la puntuación desde backend en el
        -- futuro sin recompilar; hoy el cliente usa sus constantes como fuente de
        -- verdad. `templates` queda como gancho para servir plantillas desde la nube
        -- (hoy el cliente usa NeonSudokuTemplates.SAMPLE).
        '{"boardSize": 9, "blockSize": 3, "baseScore": 1000, "errorPenalty": 25, "timePenaltyPerSec": 1, "templates": []}'::jsonb
    )
on conflict (id) do nothing;
