-- =============================================================================
-- FASE 2 (parte 2) — Cálculo de percentiles (RPC en PostgreSQL)
-- -----------------------------------------------------------------------------
-- ¿Por qué RPC en SQL y no una Edge Function que traiga los datos?
--   * El percentil es una agregación sobre TODO el historial del juego.
--     Traerlo a Deno significaría transferir millones de filas → inviable.
--   * En SQL corre junto a los datos y aprovecha el índice user_progress
--     (game_id, score). Devuelve solo el número final.
--   * SECURITY DEFINER: necesita leer scores de TODOS los usuarios (RLS lo
--     impediría al usuario normal), pero solo expone un AGREGADO, nunca filas.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- get_score_percentile(game_id, score)
--   Devuelve en qué percentil queda `p_score` respecto al histórico del juego.
--   better_than_pct = % de partidas históricas con score ESTRICTAMENTE menor.
--   → mensaje "Eres mejor que el X% de los jugadores".
-- -----------------------------------------------------------------------------
create or replace function public.get_score_percentile(
    p_game_id uuid,
    p_score   integer
)
returns table (
    better_than_pct numeric,   -- % de jugadores que superas (0..100)
    total_players   bigint,    -- tamaño de la muestra histórica
    rank            bigint     -- posición (1 = mejor)
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with stats as (
        select
            count(*)                                    as total,
            count(*) filter (where up.score <  p_score) as below,
            count(*) filter (where up.score >  p_score) as above
        from public.user_progress up
        where up.game_id = p_game_id
    )
    select
        round(coalesce(below::numeric / nullif(total, 0) * 100, 0), 2) as better_than_pct,
        total                                                          as total_players,
        (above + 1)                                                    as rank
    from stats;
$$;

comment on function public.get_score_percentile is
    'Agregado de percentil por juego. SECURITY DEFINER: lee todos los scores pero solo devuelve estadística, nunca filas individuales.';

revoke all on function public.get_score_percentile(uuid, integer) from public;
grant execute on function public.get_score_percentile(uuid, integer) to authenticated;


-- -----------------------------------------------------------------------------
-- submit_game_result(...)  [conveniencia — 1 sola llamada desde la app]
--   Inserta la partida en el historial (como el propio usuario) y devuelve
--   inmediatamente su percentil. Evita 2 round-trips (insert + percentil).
--   SECURITY INVOKER: el insert respeta RLS (solo puede insertar filas propias).
--   El percentil se obtiene llamando a la función DEFINER de arriba.
-- -----------------------------------------------------------------------------
create or replace function public.submit_game_result(
    p_game_id              uuid,
    p_score                integer,
    p_completion_time_ms   integer,
    p_accuracy_percentage  numeric,
    p_difficulty_level     smallint default 1,
    p_daily_challenge_id   uuid     default null
)
returns table (
    progress_id     uuid,
    better_than_pct numeric,
    total_players   bigint,
    rank            bigint
)
language plpgsql
volatile
security invoker
set search_path = public, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_id  uuid;
begin
    if v_uid is null then
        raise exception 'No autenticado' using errcode = '28000';
    end if;

    insert into public.user_progress (
        user_id, game_id, daily_challenge_id, score,
        completion_time_ms, accuracy_percentage, difficulty_level
    ) values (
        v_uid, p_game_id, p_daily_challenge_id, p_score,
        p_completion_time_ms, p_accuracy_percentage, coalesce(p_difficulty_level, 1)
    )
    returning id into v_id;

    return query
    select v_id, p.better_than_pct, p.total_players, p.rank
    from public.get_score_percentile(p_game_id, p_score) p;
end;
$$;

comment on function public.submit_game_result is
    'Registra la partida (RLS: solo filas propias) y devuelve el percentil en una única llamada.';

revoke all on function public.submit_game_result(uuid, integer, integer, numeric, smallint, uuid) from public;
grant execute on function public.submit_game_result(uuid, integer, integer, numeric, smallint, uuid) to authenticated;
