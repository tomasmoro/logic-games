-- =============================================================================
-- Seed: juego "Hexa Orbit", categoría Visión Espacial (slug `spatial`).
--
-- Juego táctico en tiempo real sobre un tablero hexagonal de 3 anillos (37
-- azulejos). Cada azulejo guarda 3 caminos curvos que emparejan sus 6 aristas;
-- un tap lo gira 60° en horario y reencamina el puntero de luz, que recorre las
-- curvas Bézier sin parar y a rapidez creciente. El jugador recoge orbes de
-- energía (siempre 3 vivos, con respawn inmediato) y pierde cuando el puntero
-- cruza la frontera exterior del tablero. Motor propio en `commonMain`
-- (`HexaOrbitEngine`), con el trazado proyectado de 4 azulejos como mecánica
-- central de anticipación.
--
-- El UUID coincide con `GameIds.HEXA_ORBIT` del cliente KMP: sin esta fila, la
-- sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
--
-- Archivo nuevo e idempotente (no reescribe ninguna migración ya aplicada y se
-- puede re-ejecutar sin efecto gracias al `on conflict do nothing`).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        'a9f2c86b-3e74-4d51-9c08-6b1d40e7a25f',
        (select id from public.categories where slug = 'spatial'),
        'hexa_orbit', 'Hexa Orbit',
        'Un puntero de luz recorre sin parar los caminos del tablero hexagonal. Gira las piezas para encaminarlo, recoge los orbes de energía y evita que se escape por el borde: cada segundo va más rápido.',
        'spatial', 1,
        -- engine_config: balance del juego (ver HexaOrbitBalance en el cliente, que
        -- hoy es la fuente de verdad; se expone aquí para poder afinarlo desde
        -- backend en el futuro sin recompilar).
        --
        -- Las velocidades van en RADIOS DE HEXÁGONO por segundo, no en azulejos
        -- por segundo: el puntero mantiene rapidez lineal constante sobre la
        -- curva, así que un giro cerrado (camino más corto) se atraviesa antes
        -- que una recta. Cruzar un azulejo en recta mide √3 ≈ 1,73 radios, luego
        -- `initialSpeed` 2.8 ≈ 1,6 azulejos rectos/s y el techo ≈ 5.
        --
        -- `speedRampPerSec` es una rampa LINEAL con techo en `maxSpeed`: alcanzar
        -- el techo exige ~72 s de supervivencia, muy por encima de una partida
        -- media, así que en la práctica la rampa nunca se aplana en juego real.
        --
        -- `lookaheadTiles` es la mecánica central (cuántos azulejos ilumina el haz
        -- por delante) y `collectRadius` va en radios de hexágono: es
        -- deliberadamente generoso para que varias orientaciones del azulejo
        -- sirvan para recoger un orbe, en vez de exigir clavar una curva concreta.
        '{"boardRadius": 3, "lookaheadTiles": 4, "maxOrbs": 3, "initialSpeed": 2.8, "speedRampPerSec": 0.08, "maxSpeed": 8.6, "collectRadius": 0.32, "pointsPerOrb": 100, "survivalPointsPerSec": 8, "minSpawnDistance": 2, "trailPoints": 24}'::jsonb
    )
on conflict (id) do nothing;
