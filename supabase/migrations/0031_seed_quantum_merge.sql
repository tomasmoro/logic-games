-- =============================================================================
-- Seed: juego "Quantum Merge", categoría Visión Espacial (slug `spatial`).
--
-- Esferas de energía que caen por gravedad en un contenedor abierto, rebotan
-- entre sí y se fusionan en la siguiente de la escala cuando dos del mismo tier
-- se tocan (mecánica tipo Suika). La física —colisión círculo-círculo, impulsos
-- con restitución y fricción— es un motor 2D propio escrito en `commonMain`
-- (`QuantumMergeEngine`), sin librerías externas. Archivo nuevo e idempotente
-- (no reescribe ninguna migración ya aplicada y se puede re-ejecutar sin efecto).
--
-- El UUID coincide con `GameIds.QUANTUM_MERGE` del cliente KMP: sin esta fila, la
-- sincronización remota de una partida fallaría por la FK
-- `user_progress.game_id → games.id` (y lo mismo en `player_game_progress`).
--
-- Numerado 0031 y no 0030: el hueco 0030 lo ocupa el seed de "Línea Neón", que
-- ya está aplicado en el proyecto pero cuyo archivo vive en otra rama. Saltar el
-- número evita dos archivos distintos con el mismo prefijo al fusionar (como ya
-- pasó con los dos 0026) y conserva el orden real de aplicación.
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        'c17b40de-92a5-4f38-8b61-0d7e5a3c9142',
        (select id from public.categories where slug = 'spatial'),
        'quantum_merge', 'Quantum Merge',
        'Apunta y suelta esferas de energía dentro del reactor. Dos esferas iguales que se tocan se fusionan en la siguiente de la escala; si alguna se queda quieta por encima de la línea de peligro, el reactor desborda.',
        'spatial', 1,
        -- engine_config: geometría del mundo simulado y balance (ver QuantumWorld y
        -- QuantumMergeEngine en el cliente, que hoy son la fuente de verdad; se
        -- exponen aquí para poder afinarlos desde backend en el futuro sin
        -- recompilar).
        --
        -- `worldWidth`/`worldHeight` son unidades de mundo FIJAS, no píxeles: la
        -- simulación es idéntica en cualquier dispositivo y el render solo escala
        -- (por eso la gravedad se expresa en esas mismas unidades por segundo²).
        --
        -- `tiers` = 11: la escala llega hasta la singularidad. La razón entre
        -- radios consecutivos se relaja de ~1,24 a ~1,19 para que dos esferas del
        -- tier máximo quepan lado a lado en un contenedor de 100 de ancho — sin
        -- eso, la última fusión sería inalcanzable.
        --
        -- `spawnTiersByDifficulty` = 2 + nivel: la dificultad NO acelera la caída
        -- (este juego no va de reflejos), sino que amplía los tipos que entrega el
        -- dispensador, que es lo que complica emparejar y obliga a planificar.
        '{"worldWidth": 100, "worldHeight": 132, "dangerLineY": 18, "gravity": 320, "restitution": 0.2, "friction": 0.35, "fixedStepHz": 120, "solverIterations": 10, "tiers": 11, "spawnTiersByDifficulty": 2, "overflowGraceSeconds": 2.2, "annihilationBonus": 1500}'::jsonb
    )
on conflict (id) do nothing;
