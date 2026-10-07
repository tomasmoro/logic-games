-- =============================================================================
-- Seed: juego "Neon Shikaku Matrix", categoría Visión Espacial (slug `spatial`).
--
-- Shikaku sobre tableros de figura irregular: hay que dividir el tablero en
-- rectángulos de modo que cada uno contenga exactamente un número y mida tantas
-- celdas como ese número. Los niveles se generan en el cliente por ingeniería
-- inversa (se construye la partición y luego se esconde, ver KDoc de
-- `ShikakuLevelGenerator`), con semilla derivada del número de nivel: el nivel N
-- es el mismo tablero para todos los jugadores, de modo que tiempos y puntajes
-- son comparables sin guardar ningún tablero en la base de datos.
--
-- El UUID coincide con `GameIds.NEON_SHIKAKU` del cliente KMP: sin esta fila, la
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
        '604aae7c-48c9-459d-a5ef-79c231a0d7b9',
        (select id from public.categories where slug = 'spatial'),
        'neon_shikaku', 'Neon Shikaku Matrix',
        'Divide el tablero en rectángulos de luz: cada uno debe encerrar un único número y medir exactamente tantas celdas como ese número.',
        'spatial', 1,
        -- engine_config: curva de dificultad y balance de puntuación (ver
        -- `ShikakuLevelGenerator.configFor` y `ShikakuScoring` en el cliente, que hoy
        -- son la fuente de verdad; se exponen aquí para poder afinarlos desde backend
        -- en el futuro sin recompilar).
        --
        -- `tiers` lista los tres primeros niveles tal cual; de `growsFromLevel` en
        -- adelante el lado crece una celda cada `levelsPerSizeStep` niveles hasta
        -- `maxSide`, y la pista máxima sube una unidad por nivel hasta `maxArea`.
        -- `targetMsPerCell` es el tiempo objetivo por celda jugable del bonus de
        -- velocidad.
        '{"tiers": [{"level": 1, "side": 5, "maxArea": 5}, {"level": 2, "side": 8, "maxArea": 12}, {"level": 3, "side": 10, "maxArea": 16}], "growsFromLevel": 4, "levelsPerSizeStep": 3, "maxSide": 12, "maxArea": 24, "targetMsPerCell": 2500, "restartPenalty": 60}'::jsonb
    )
on conflict (id) do nothing;
