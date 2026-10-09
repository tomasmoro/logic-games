-- =============================================================================
-- Seed: juego "Neon Trees & Tents", categoría Pensamiento Lógico (slug `logic`).
--
-- Árboles y Tiendas: hay que plantar una tienda junto a cada árbol (ortogonal),
-- sin que dos tiendas se toquen ni en diagonal, y cuadrando el número de tiendas
-- de cada fila y columna. Los niveles se generan en el cliente por colocación
-- inversa (se construye la solución y de ella se leen las pistas, ver KDoc de
-- `TentsLevelGenerator`), con semilla derivada del número de nivel: el nivel N
-- es el mismo tablero para todos los jugadores, de modo que tiempos y puntajes
-- son comparables sin guardar ningún tablero en la base de datos.
--
-- El UUID coincide con `GameIds.NEON_TENTS` del cliente KMP: sin esta fila, la
-- sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
--
-- El juego todavía NO está publicado en el catálogo de la app
-- (`GameCatalog.kt`: `published = false`) — el seed va igual para que se puedan
-- guardar partidas de prueba sin romper la FK; cuando se publique no hace falta
-- tocar esta fila.
--
-- Archivo nuevo e idempotente (no reescribe ninguna migración ya aplicada y se
-- puede re-ejecutar sin efecto gracias al `on conflict do nothing`).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '0240cbed-e818-446d-8a1c-e592a2617a85',
        (select id from public.categories where slug = 'logic'),
        'neon_trees_tents', 'Neon Trees & Tents',
        'Planta una tienda de luz junto a cada árbol: ninguna puede tocar a otra, ni en diagonal, y cada fila y columna debe sumar exactamente su número.',
        'logic', 1,
        -- engine_config: curva de dificultad y balance de puntuación (ver
        -- `TentsLevelGenerator.configFor` y `TentsScoring` en el cliente, que hoy son
        -- la fuente de verdad; se exponen aquí para poder afinarlos desde backend en
        -- el futuro sin recompilar).
        --
        -- `tiers` da, para cada tramo, el nivel desde el que aplica, el lado del
        -- tablero y las parejas árbol-tienda; el último tramo vale para todos los
        -- niveles siguientes. `targetMsPerCell` es el tiempo objetivo por casilla del
        -- bonus de velocidad.
        '{"tiers": [{"fromLevel": 1, "side": 5, "tents": 4}, {"fromLevel": 2, "side": 5, "tents": 5}, {"fromLevel": 4, "side": 6, "tents": 6}, {"fromLevel": 6, "side": 6, "tents": 7}, {"fromLevel": 8, "side": 7, "tents": 8}, {"fromLevel": 10, "side": 7, "tents": 9}, {"fromLevel": 11, "side": 7, "tents": 10}, {"fromLevel": 13, "side": 8, "tents": 11}, {"fromLevel": 16, "side": 8, "tents": 12}], "targetMsPerCell": 3000, "restartPenalty": 60}'::jsonb
    )
on conflict (id) do nothing;
