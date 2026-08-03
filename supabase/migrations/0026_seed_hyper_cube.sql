-- =============================================================================
-- Seed: juego "Neon Hyper-Cube", categoría Visión Espacial (slug `spatial`).
--
-- Cubo mágico 3x3 holográfico renderizado en 2D con proyección propia (sin
-- librerías 3D): se arrastra sobre una fila para girar esa capa 90° y fuera del
-- cubo para orbitar la cámara. Archivo nuevo e idempotente (no reescribe ninguna
-- migración ya aplicada y se puede re-ejecutar sin efecto).
--
-- El UUID coincide con `GameIds.HYPER_CUBE` del cliente KMP: sin esta fila, la
-- sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
-- Es un UUID v4 aleatorio: el patrón de "nibble repetido" de los seeds antiguos
-- ya no tiene valores libres (1..f están tomados).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '3d5c8a17-6b24-4e9f-9a80-1c7e5b2d4f63',
        (select id from public.categories where slug = 'spatial'),
        'hyper_cube', 'Neon Hyper-Cube',
        'Un cubo holográfico de 3x3 se ha desordenado. Arrastra sobre una fila para girarla y orbita alrededor para ver las caras ocultas, hasta dejar cada cara de un solo color.',
        'spatial', 1,
        -- engine_config: parámetros de rampa y balance (ver HyperCubeEngine en el
        -- cliente, que hoy es la fuente de verdad; se exponen aquí para poder
        -- afinarlos desde backend en el futuro sin recompilar).
        --
        -- `maxLevel` = 8 y `scrambleDepthByLevel` = nivel + 1: la progresión se
        -- detiene deliberadamente en mezclas de 9 giros porque más allá de ~10
        -- giros aleatorios el cubo queda indistinguible de uno completamente
        -- mezclado, y el juego pasaría a exigir saber resolver un Rubik de
        -- memoria en vez de visión espacial. El cubo entero se ofrece aparte en
        -- el modo libre (`freeScrambleDepth`), que NO produce récord de nivel.
        '{"maxLevel": 8, "scrambleDepthOffset": 1, "freeScrambleDepth": 20, "levelPoints": 500, "efficiencyBonus": 600, "speedBonus": 300, "targetSecondsPerTurn": 8}'::jsonb
    )
on conflict (id) do nothing;
