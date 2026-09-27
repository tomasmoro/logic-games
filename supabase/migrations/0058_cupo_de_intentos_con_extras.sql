-- =============================================================================
-- 0058 — El cupo de intentos pasa a ser `attempts_limit + extra_attempts`
-- -----------------------------------------------------------------------------
-- Cierra lo que abrió 0057: los intentos comprados con anuncio no servían de nada
-- mientras `submit_event_result` siguiera midiendo contra `attempts_limit` a
-- secas. Aquí el tope pasa a ser el cupo REAL del jugador.
--
-- Cambios respecto a 0056 (el resto del cuerpo es idéntico):
--   * se lee también `extra_attempts` y el tope se compara contra la suma;
--   * `attempts_limit` de la respuesta devuelve ese cupo real, no el del evento:
--     es lo que la UI enseña como "te quedan N", y el número del evento mentiría
--     a quien acaba de ver un anuncio;
--   * `get_event_leaderboard` añade `my_extra_attempts`.
--
-- Nota: `attempts_limit` null (torneo sin tope) sigue funcionando — la condición
-- del `if` lo comprueba antes de mirar el cupo.
-- =============================================================================

create or replace function public.submit_event_result(
    p_event_id             uuid,
    p_score                integer,
    p_completion_time_ms   integer,
    p_accuracy_percentage  numeric,
    p_difficulty_level     smallint default null,
    -- true = la partida se abandonó: gasta intento y NO registra marca.
    p_abandoned            boolean  default false
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid         uuid := auth.uid();
    v_event       public.events%rowtype;
    v_attempts    smallint;
    v_extra       smallint;
    v_allowed     smallint;
    v_progress_id uuid;
    v_improved    boolean;
    v_time        integer;
    v_rank        bigint;
    v_total       bigint;
begin
    if v_uid is null then
        raise exception 'No autenticado' using errcode = '28000';
    end if;

    select * into v_event
    from public.events e
    where e.id = p_event_id
      and e.is_published
      and now() >= e.starts_at
      and now() <  e.ends_at
    for share;

    if not found then
        raise exception 'El torneo no está abierto' using errcode = 'KXE01';
    end if;

    -- En un abandono no se valida la dificultad: no se jugó nada que pudiera
    -- estar en la dificultad equivocada.
    if not p_abandoned
       and v_event.difficulty_level is not null
       and coalesce(p_difficulty_level, v_event.difficulty_level) <> v_event.difficulty_level then
        raise exception 'Dificultad no permitida en este torneo' using errcode = 'KXE03';
    end if;

    select ee.attempts, ee.extra_attempts into v_attempts, v_extra
    from public.event_entries ee
    where ee.event_id = p_event_id and ee.user_id = v_uid
    for update;

    -- El cupo real de ESTE jugador: los intentos libres del torneo más los que ya
    -- se haya ganado viendo anuncios (0057).
    v_allowed := v_event.attempts_limit + coalesce(v_extra, 0);

    if v_event.attempts_limit is not null
       and coalesce(v_attempts, 0) >= v_allowed then
        raise exception 'Sin intentos disponibles' using errcode = 'KXE02';
    end if;

    -- ABANDONO: solo consume intento (ver la cabecera de esta migración).
    if p_abandoned then
        insert into public.event_entries (event_id, user_id, best_score, best_time_ms, attempts, has_mark)
        values (p_event_id, v_uid, 0, null, 1, false)
        on conflict (event_id, user_id) do update set
            attempts   = event_entries.attempts + 1,
            updated_at = now();

        select r.pos, r.total into v_rank, v_total
        from (
            select ee.user_id,
                   row_number() over (
                       order by case when v_event.rank_by_time then ee.best_time_ms end asc,
                                case when v_event.rank_by_time then null else ee.best_score end desc,
                                ee.user_id
                   ) as pos,
                   count(*) over () as total
            from public.event_entries ee
            where ee.event_id = p_event_id
              and ee.has_mark
              and (not v_event.rank_by_time or ee.best_time_ms is not null)
        ) r
        where r.user_id = v_uid;

        return jsonb_build_object(
            'progress_id',    null,
            'improved',       false,
            'abandoned',      true,
            'rank',           v_rank,
            'total_players',  coalesce(v_total, 0),
            'attempts_used',  coalesce(v_attempts, 0) + 1,
            'attempts_limit', v_allowed
        );
    end if;

    -- 0 ms = partida no cronometrada, no "instantánea".
    v_time := nullif(p_completion_time_ms, 0);

    insert into public.user_progress (
        user_id, game_id, event_id, score,
        completion_time_ms, accuracy_percentage, difficulty_level
    ) values (
        v_uid, v_event.game_id, p_event_id, p_score,
        greatest(coalesce(p_completion_time_ms, 0), 0), p_accuracy_percentage,
        coalesce(p_difficulty_level, v_event.difficulty_level, 1)
    )
    returning id into v_progress_id;

    -- Quien solo había abandonado no tiene marca todavía (`has_mark` = false), así
    -- que su primera partida enviada siempre mejora aunque la fila ya exista.
    if v_event.rank_by_time then
        v_improved := v_time is not null
                      and not exists (
                          select 1 from public.event_entries ee
                          where ee.event_id = p_event_id and ee.user_id = v_uid
                            and ee.has_mark
                            and ee.best_time_ms is not null
                            and ee.best_time_ms <= v_time);
    else
        v_improved := not exists (
                          select 1 from public.event_entries ee
                          where ee.event_id = p_event_id and ee.user_id = v_uid
                            and ee.has_mark
                            and ee.best_score >= p_score);
    end if;

    insert into public.event_entries (
        event_id, user_id, best_score, best_time_ms, attempts, has_mark
    ) values (
        p_event_id, v_uid, greatest(p_score, 0), v_time, 1, true
    )
    on conflict (event_id, user_id) do update set
        best_score   = case when v_improved then excluded.best_score   else event_entries.best_score   end,
        best_time_ms = case when v_improved then excluded.best_time_ms else event_entries.best_time_ms end,
        attempts     = event_entries.attempts + 1,
        has_mark     = true,
        updated_at   = now();

    select r.pos, r.total into v_rank, v_total
    from (
        select ee.user_id,
               row_number() over (
                   order by case when v_event.rank_by_time then ee.best_time_ms end asc,
                            case when v_event.rank_by_time then null else ee.best_score end desc,
                            ee.user_id
               ) as pos,
               count(*) over () as total
        from public.event_entries ee
        where ee.event_id = p_event_id
          and ee.has_mark
          and (not v_event.rank_by_time or ee.best_time_ms is not null)
    ) r
    where r.user_id = v_uid;

    return jsonb_build_object(
        'progress_id',    v_progress_id,
        'improved',       coalesce(v_improved, false),
        'abandoned',      false,
        'rank',           v_rank,
        'total_players',  coalesce(v_total, 0),
        'attempts_used',  coalesce(v_attempts, 0) + 1,
        'attempts_limit', v_allowed
    );
end;
$$;

comment on function public.submit_event_result is
    'Registra un intento de torneo (o su abandono) validando ventana, dificultad y tope de intentos; actualiza la mejor marca y devuelve el puesto.';

revoke all on function public.submit_event_result(uuid, integer, integer, numeric, smallint, boolean) from public, anon;
grant execute on function public.submit_event_result(uuid, integer, integer, numeric, smallint, boolean) to authenticated;


-- La clasificación ignora a quien solo ha gastado intentos, pero le sigue diciendo
-- cuántos ha gastado (`my_attempts`).
create or replace function public.get_event_leaderboard(
    p_event_id uuid,
    p_limit    integer default 10
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with ev as (
        select e.rank_by_time
        from public.events e
        where e.id = p_event_id and e.is_published
    ),
    ranked as (
        select ee.user_id,
               ee.best_score,
               ee.best_time_ms,
               ee.attempts,
               row_number() over (
                   order by case when ev.rank_by_time then ee.best_time_ms end asc,
                            case when ev.rank_by_time then null else ee.best_score end desc,
                            ee.user_id
               ) as pos,
               count(*) over () as total
        from public.event_entries ee
        cross join ev
        where ee.event_id = p_event_id
          and ee.has_mark
          and (not ev.rank_by_time or ee.best_time_ms is not null)
    ),
    row_json as (
        select r.pos,
               jsonb_build_object(
                   'rank',            r.pos,
                   'display_name',    u.display_name,
                   'best_score',      r.best_score,
                   'best_time_ms',    r.best_time_ms,
                   'is_current_user', coalesce(r.user_id = auth.uid(), false)
               ) as j,
               r.user_id,
               r.total,
               r.attempts
        from ranked r
        join public.users u on u.id = r.user_id
    ),
    mine as (
        select ee.attempts, ee.extra_attempts
        from public.event_entries ee
        where ee.event_id = p_event_id and ee.user_id = auth.uid()
    )
    select case
             when not exists (select 1 from ev) then null
             else jsonb_build_object(
                 'total_players', coalesce((select max(total) from row_json), 0),
                 'top', coalesce(
                     (select jsonb_agg(j order by pos)
                      from row_json where pos <= greatest(p_limit, 1)),
                     '[]'::jsonb),
                 'me', (select jsonb_set(j, '{attempts}', to_jsonb(attempts))
                        from row_json where user_id = auth.uid()),
                 'my_attempts', coalesce((select attempts from mine), 0),
                 -- Extras ya desbloqueados: con esto el cliente sabe si aún puede
                 -- comprar otro intento con un anuncio.
                 'my_extra_attempts', coalesce((select extra_attempts from mine), 0)
             )
           end;
$$;

comment on function public.get_event_leaderboard is
    'Tabla del torneo: top N + la fila del jugador + sus intentos gastados. Proyecta solo display_name y marcas; nunca user_id.';

revoke all on function public.get_event_leaderboard(uuid, integer) from public, anon;
grant execute on function public.get_event_leaderboard(uuid, integer) to authenticated;
