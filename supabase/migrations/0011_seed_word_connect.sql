-- =============================================================================
-- Seed: juego "Palabras Conectadas" (Word Connect), categoría Lenguaje y Vocabulario.
-- Archivo nuevo e idempotente: no reescribe migraciones previas y puede re-aplicarse.
-- El UUID coincide con GameIds.WORD_CONNECT para mantener la FK de progreso (al
-- sincronizar un resultado, `player_game_progress.game_id` referencia esta fila).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        '88888888-8888-4888-8888-888888888888',
        (select id from public.categories where slug = 'language'),
        'word_connect', 'Palabras Conectadas',
        'Une las letras de una rueda arrastrando el dedo para formar todas las palabras ocultas del nivel.',
        'language', 1,
        -- engine_config: parámetros de puntuación (ver WordConnectEngine.onCorrect) para
        -- poder afinar la recompensa desde backend sin recompilar el cliente.
        '{"baseLetterPoints": 120, "comboBonus": 40, "levelBonus": 15}'::jsonb
    )
on conflict (id) do nothing;
