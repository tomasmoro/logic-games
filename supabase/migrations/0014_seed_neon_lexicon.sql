-- =============================================================================
-- Seed: juego "Sopa de Letras Neón" (Neon Lexicon), categoría Lenguaje y Vocabulario.
-- Archivo nuevo e idempotente: no reescribe migraciones previas y puede re-aplicarse.
-- El UUID coincide con GameIds.NEON_LEXICON para mantener la FK de progreso (al
-- sincronizar un resultado, `player_game_progress.game_id` referencia esta fila).
-- =============================================================================

insert into public.games (id, category_id, slug, name, description, game_type, difficulty_level, engine_config) values
    (
        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        (select id from public.categories where slug = 'language'),
        'neon_lexicon', 'Sopa de Letras Neón',
        'Desliza el dedo para trazar las palabras escondidas en la cuadrícula (horizontal, vertical o diagonal); encuéntralas todas para superar el nivel.',
        'language', 1,
        -- engine_config: parámetros de puntuación (ver NeonLexiconEngine.onWordFound)
        -- para poder afinar la recompensa desde backend sin recompilar el cliente.
        '{"wordScoreBase": 100, "levelBonus": 25}'::jsonb
    )
on conflict (id) do nothing;
