-- =============================================================================
-- 0056 — Abandonar una partida de torneo CONSUME intento (sin registrar marca)
-- -----------------------------------------------------------------------------
-- DECISIÓN DE PRODUCTO
-- Hasta ahora el intento se contaba al ENVIAR el resultado, así que salir a mitad
-- de partida no costaba nada: con el tablero fijo de un torneo, eso permitía
-- practicarlo indefinidamente y volver a entrar hasta clavarlo. A partir de aquí,
-- empezar y abandonar gasta intento igual que terminar.
--
-- POR QUÉ NO BASTABA CON ENVIAR EL ABANDONO COMO UN RESULTADO NORMAL
-- Porque habría registrado MARCA. En un torneo por tiempo (`rank_by_time`), una
-- partida abandonada a los diez segundos tiene el mejor cronómetro de la tabla:
-- abandonar sería la forma óptima de ganar. Por eso el abandono es un camino
-- aparte (`p_abandoned`) que toca el contador de intentos y nada más: ni fila en
-- `user_progress` (no hubo partida que registrar) ni marca.
--
-- POR QUÉ `has_mark` Y NO DEDUCIRLO DE LOS VALORES
-- La fila de quien solo ha abandonado existe (hay que contarle los intentos) pero
-- no representa ninguna partida. Deducirlo de `best_score = 0 and best_time_ms is
-- null` sería frágil: un torneo por puntos donde alguien saque 0 legítimamente
-- quedaría fuera de la tabla sin que nadie lo hubiera decidido. Una columna
-- explícita dice lo que pasa y se puede filtrar por índice.
--
-- La clasificación (`get_event_leaderboard`) pasa a filtrar por `has_mark`, pero
-- devuelve además `my_attempts` leído de la fila propia exista o no marca: quien
-- solo ha abandonado no sale en la tabla y aun así tiene que ver cuántos intentos
-- le quedan.
--
-- SE REEMPLAZA LA FIRMA de `submit_event_result` (drop + create, no overload): el
-- parámetro nuevo habría creado una segunda función con el mismo nombre y
-- PostgREST resuelve por nombres de argumento, así que convivirían dos caminos
-- casi idénticos. No hay clientes publicados usando la anterior — la Fase 7 no
-- tiene todavía ningún torneo publicado.
-- =============================================================================

alter table public.event_entries
    add column if not exists has_mark boolean not null default false;

-- Las filas existentes nacieron de envíos reales.
update public.event_entries set has_mark = true where not has_mark;

comment on column public.event_entries.has_mark is
    'false = el jugador solo ha consumido intentos (abandonos), no tiene marca: queda fuera de la clasificación.';

-- El índice del leaderboard solo necesita a quien compite de verdad.
drop index if exists public.event_entries_score_idx;
create index event_entries_score_idx
    on public.event_entries (event_id, best_score desc)
    where has_mark;

drop function if exists public.submit_event_result(uuid, integer, integer, numeric, smallint);

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

    select ee.attempts into v_attempts
    from public.event_entries ee
    where ee.event_id = p_event_id and ee.user_id = v_uid
    for update;

    if v_event.attempts_limit is not null
       and coalesce(v_attempts, 0) >= v_event.attempts_limit then
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
            'attempts_limit', v_event.attempts_limit
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
        'attempts_limit', v_event.attempts_limit
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
        select ee.attempts, ee.has_mark
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
                 'my_attempts', coalesce((select attempts from mine), 0)
             )
           end;
$$;

comment on function public.get_event_leaderboard is
    'Tabla del torneo: top N + la fila del jugador + sus intentos gastados. Proyecta solo display_name y marcas; nunca user_id.';

revoke all on function public.get_event_leaderboard(uuid, integer) from public, anon;
grant execute on function public.get_event_leaderboard(uuid, integer) to authenticated;
