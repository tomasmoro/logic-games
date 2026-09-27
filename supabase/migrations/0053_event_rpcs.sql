-- =============================================================================
-- 0053 — RPCs de eventos: enviar marca y leer la tabla del torneo
-- -----------------------------------------------------------------------------
-- Dos funciones, el mismo reparto de responsabilidades que ya usa el ranking
-- mundial (0027): una que ESCRIBE aplicando las reglas del evento y otra que LEE
-- el leaderboard proyectando solo lo publicable.
--
-- POR QUÉ NO BASTA CON `submit_game_result` (0003)
-- Aquella es `security invoker`: inserta la partida y devuelve el percentil, y
-- toda su seguridad es la RLS de `user_progress` ("solo filas propias"). Un torneo
-- necesita además tres validaciones que el cliente NO puede hacerse a sí mismo sin
-- que dejen de ser validaciones:
--   1. que el evento esté ABIERTO en este instante (reloj del servidor, no el del
--      teléfono — que el jugador controla),
--   2. que la dificultad sea la que el torneo fija,
--   3. que no se haya agotado el tope de intentos.
-- Y tiene que escribir en `event_entries`, que a propósito no tiene policy de
-- INSERT/UPDATE (0051): si la tuviera, el cliente podría escribir su puesto a mano.
-- De ahí `security definer` — con la misma disciplina que el resto del proyecto:
-- `search_path` fijado y proyección de salida acotada.
--
-- POR QUÉ NO HAY UN `get_active_events`
-- El calendario es un catálogo con RLS de lectura (`is_published`), así que el
-- cliente lo pide por PostgREST (`events?is_published=eq.true&ends_at=gte...`) y su
-- propia fila por `event_entries` (policy `event_entries_read_own`). Dos consultas
-- indexadas y cero superficie nueva que mantener. La RPC solo aparece donde hace
-- falta agregar datos ajenos, que es justo lo que RLS no deja leer.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Códigos de error propios (SQLSTATE clase 'KX')
-- -----------------------------------------------------------------------------
-- El cliente necesita DISTINGUIR los rechazos para decir algo útil ("el torneo ya
-- cerró" ≠ "te quedaste sin intentos"). Con un `raise exception` genérico llegaría
-- un P0001 con un texto en español que habría que parsear — frágil y sin traducir.
-- Los SQLSTATE de 5 caracteres fuera de las clases estándar son válidos y viajan
-- intactos hasta el cliente Supabase:
--   KXE01 — el evento no está abierto ahora mismo (o no existe / no publicado)
--   KXE02 — tope de intentos agotado
--   KXE03 — la dificultad enviada no es la que fija el torneo


-- =============================================================================
-- 1) submit_event_result — registrar un intento del torneo
-- =============================================================================
create or replace function public.submit_event_result(
    p_event_id             uuid,
    p_score                integer,
    p_completion_time_ms   integer,
    p_accuracy_percentage  numeric,
    p_difficulty_level     smallint default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid            uuid := auth.uid();
    v_event          public.events%rowtype;
    v_attempts       smallint;
    v_progress_id    uuid;
    v_improved       boolean;
    v_time           integer;
    v_rank           bigint;
    v_total          bigint;
begin
    if v_uid is null then
        raise exception 'No autenticado' using errcode = '28000';
    end if;

    -- Un solo SELECT valida existencia, publicación y ventana: si el evento no
    -- está abierto AHORA (reloj del servidor), no hay fila y se rechaza igual que
    -- si no existiera. No se distingue "no existe" de "cerrado" a propósito: el
    -- cliente no tiene por qué poder sondear qué eventos hay en borrador.
    select * into v_event
    from public.events e
    where e.id = p_event_id
      and e.is_published
      and now() >= e.starts_at
      and now() <  e.ends_at
    for share;                      -- que no se despublique a mitad del envío

    if not found then
        raise exception 'El torneo no está abierto' using errcode = 'KXE01';
    end if;

    if v_event.difficulty_level is not null
       and coalesce(p_difficulty_level, v_event.difficulty_level) <> v_event.difficulty_level then
        raise exception 'Dificultad no permitida en este torneo' using errcode = 'KXE03';
    end if;

    -- Tope de intentos. Se lee de `event_entries` (una fila) y no contando
    -- `user_progress`, que obligaría a recorrer el historial en el camino crítico
    -- de cada partida.
    select ee.attempts into v_attempts
    from public.event_entries ee
    where ee.event_id = p_event_id and ee.user_id = v_uid
    for update;                     -- serializa dos envíos simultáneos del mismo jugador

    if v_event.attempts_limit is not null
       and coalesce(v_attempts, 0) >= v_event.attempts_limit then
        raise exception 'Sin intentos disponibles' using errcode = 'KXE02';
    end if;

    -- Un tiempo de 0 ms significa "esta partida no se cronometró", no "instantánea".
    -- Guardarlo como 0 pondría a ese jugador el primero en un torneo por tiempo.
    v_time := nullif(p_completion_time_ms, 0);

    -- El log inmutable sigue siendo la fuente de verdad: la partida del torneo
    -- cuenta también para misión diaria, logros y percentil global.
    insert into public.user_progress (
        user_id, game_id, event_id, score,
        completion_time_ms, accuracy_percentage, difficulty_level
    ) values (
        v_uid, v_event.game_id, p_event_id, p_score,
        greatest(coalesce(p_completion_time_ms, 0), 0), p_accuracy_percentage,
        coalesce(p_difficulty_level, v_event.difficulty_level, 1)
    )
    returning id into v_progress_id;

    -- ¿Mejora la marca según el criterio DE ESTE torneo?
    -- La fila de `event_entries` representa UNA partida —la mejor—, no un collage
    -- del mejor puntaje de un intento y el mejor tiempo de otro: enseñar
    -- "1.250 pts · 3:41" cuando nadie hizo nunca esa partida sería mentir al
    -- jugador y al resto de la tabla.
    if v_event.rank_by_time then
        v_improved := v_time is not null
                      and (v_attempts is null
                           or not exists (
                               select 1 from public.event_entries ee
                               where ee.event_id = p_event_id and ee.user_id = v_uid
                                 and ee.best_time_ms is not null
                                 and ee.best_time_ms <= v_time));
    else
        v_improved := v_attempts is null
                      or not exists (
                          select 1 from public.event_entries ee
                          where ee.event_id = p_event_id and ee.user_id = v_uid
                            and ee.best_score >= p_score);
    end if;

    -- `scoring_mode` se ignora aquí porque hoy solo existe 'best_run'. Cuando entre
    -- 'sum_top_n'/'total', este bloque es EL único sitio que cambia: la lectura del
    -- leaderboard ya trabaja sobre las columnas agregadas, no sobre el historial.
    insert into public.event_entries (
        event_id, user_id, best_score, best_time_ms, attempts
    ) values (
        p_event_id, v_uid, greatest(p_score, 0), v_time, 1
    )
    on conflict (event_id, user_id) do update set
        best_score   = case when v_improved then excluded.best_score   else event_entries.best_score   end,
        best_time_ms = case when v_improved then excluded.best_time_ms else event_entries.best_time_ms end,
        attempts     = event_entries.attempts + 1,
        updated_at   = now();

    -- Puesto tras el envío. `row_number` (no `rank`) y desempate estable por
    -- `user_id`, por el mismo motivo que en `get_game_ranking` (0027): que el
    -- puesto no baile entre consultas cuando hay empates.
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
          and (not v_event.rank_by_time or ee.best_time_ms is not null)
    ) r
    where r.user_id = v_uid;

    return jsonb_build_object(
        'progress_id',    v_progress_id,
        'improved',       coalesce(v_improved, false),
        'rank',           v_rank,
        'total_players',  coalesce(v_total, 0),
        'attempts_used',  coalesce(v_attempts, 0) + 1,
        'attempts_limit', v_event.attempts_limit
    );
end;
$$;

comment on function public.submit_event_result is
    'Registra un intento de torneo validando ventana, dificultad y tope de intentos en el servidor; actualiza la mejor marca y devuelve el puesto.';

revoke all on function public.submit_event_result(uuid, integer, integer, numeric, smallint) from public, anon;
grant execute on function public.submit_event_result(uuid, integer, integer, numeric, smallint) to authenticated;


-- =============================================================================
-- 2) get_event_leaderboard — la tabla del torneo
-- -----------------------------------------------------------------------------
-- `security definer` por lo mismo que `get_game_ranking`: RLS impide leer las
-- marcas ajenas, y la función las necesita para rankear. La proyección está
-- acotada igual de estrictamente — SOLO `display_name` y las marcas. **Nunca
-- `user_id`**, para no convertir el leaderboard en un directorio de usuarios.
--
-- Devuelve top N + la fila del jugador aunque quede fuera del top (que es el caso
-- normal): sin ella, el 99 % de la gente abriría la pantalla sin encontrarse.
-- =============================================================================
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
        -- `ev` entra por CROSS JOIN (una fila) en vez de como subconsulta escalar:
        -- dentro de la definición de una ventana no caben subconsultas.
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
          -- En un torneo por tiempo, quien no tiene ninguna partida cronometrada
          -- queda fuera de la tabla en vez de aparecer con un hueco inordenable.
          and (not ev.rank_by_time or ee.best_time_ms is not null)
    ),
    row_json as (
        select r.pos,
               jsonb_build_object(
                   'rank',          r.pos,
                   'display_name',  u.display_name,
                   'best_score',    r.best_score,
                   'best_time_ms',  r.best_time_ms,
                   'is_current_user', r.user_id = auth.uid()
               ) as j,
               r.user_id,
               r.total,
               r.attempts
        from ranked r
        join public.users u on u.id = r.user_id
    )
    select case
             when not exists (select 1 from ev) then null   -- evento inexistente o en borrador
             else jsonb_build_object(
                 'total_players', coalesce((select max(total) from row_json), 0),
                 'top', coalesce(
                     (select jsonb_agg(j order by pos)
                      from row_json where pos <= greatest(p_limit, 1)),
                     '[]'::jsonb),
                 -- null si el jugador aún no tiene marca: la UI pinta "aún no has
                 -- participado" en vez de un puesto inventado.
                 'me', (select jsonb_set(j, '{attempts}', to_jsonb(attempts))
                        from row_json where user_id = auth.uid())
             )
           end;
$$;

comment on function public.get_event_leaderboard is
    'Tabla del torneo: top N + la fila del jugador. Proyecta solo display_name y marcas; nunca user_id.';

revoke all on function public.get_event_leaderboard(uuid, integer) from public, anon;
grant execute on function public.get_event_leaderboard(uuid, integer) to authenticated;
