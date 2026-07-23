-- =============================================================================
-- Seed: juego "Neon Grid 2048", categoría Cálculo Mental (slug `calculation`).
--
-- Reimaginación neón del 2048 clásico: tablero 4×4 donde el jugador desliza para
-- fusionar potencias de dos. Archivo nuevo e idempotente (no reescribe ninguna
-- migración ya aplicada y se puede re-ejecutar sin efecto).
--
-- El UUID coincide con `GameIds.NEON_2048` del cliente KMP: sin esta fila, la
-- sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
-- No reutiliza el patrón de nibble repetido de los seeds anteriores porque
-- `1..f` ya están tomados —incluido el `2222…` del Reflex Tap desactivado en la
-- migración 0020, que NO se puede reciclar: sus filas de progreso apuntan ahí—.
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '20482048-2048-4048-8048-204820482048',
        (select id from public.categories where slug = 'calculation'),
        'neon_2048', 'Neon Grid 2048',
        'Desliza el tablero 4×4 para juntar fichas iguales: al chocar se fusionan sumando su valor. Cada movimiento hace aparecer una ficha nueva; llega a 2048 y sigue jugando hasta quedarte sin movimientos.',
        'calculation', 1,
        -- engine_config: constantes de reglas (ver Neon2048Config en el cliente).
        -- Se exponen para poder afinar el balance desde backend en el futuro sin
        -- recompilar; hoy el cliente usa sus propias constantes como fuente de verdad.
        '{"boardSize": 4, "initialTiles": 2, "spawnLuckyProbability": 0.1, "winningValue": 2048}'::jsonb
    )
on conflict (id) do nothing;
