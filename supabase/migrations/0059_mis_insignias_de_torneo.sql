-- =============================================================================
-- 0059 — Las insignias de torneo del jugador (vitrina del perfil)
-- -----------------------------------------------------------------------------
-- QUÉ AÑADE
-- `get_my_event_awards()`: los torneos ya CERRADOS en los que el jugador quedó
-- dentro de `reward_top_n`. Es lo que llena la sección "Torneos ganados" del
-- perfil, para que una insignia no se quede en un diálogo de un solo uso.
--
-- POR QUÉ NO SE GUARDAN LOS PREMIOS EN UNA TABLA
-- Porque son **derivables**: al pasar `ends_at` un torneo ya no admite escrituras
-- (lo impide `submit_event_result`), así que su clasificación es inmutable y el
-- puesto se puede recalcular siempre con el mismo resultado. Una tabla de premios
-- sería un duplicado que hay que rellenar con un proceso de cierre —y que puede
-- quedar desincronizado— a cambio de nada.
--
-- POR QUÉ NO SE REUTILIZA LA FEATURE DE LOGROS (`achievements`)
-- Se evaluó y se descartó por tres motivos, el tercero decisivo:
--   1. CARDINALIDAD. El catálogo de logros son 16 filas fijas con UUID escritos a
--      mano en código Y en un seed, atados por FK. Un torneo nace de un `insert` de
--      datos: meterlo ahí obligaría a una migración y una RELEASE de la app por cada
--      torneo que se programe.
--   2. SEMÁNTICA. Un logro es progreso hacia un umbral (`threshold`/`progress`, con
--      barra "7/10"). Una insignia es un puesto en una fecha; esos campos serían
--      relleno, y `achievement_condition` no puede tener un "ganó un torneo" honesto
--      porque esa condición no se evalúa con las estadísticas del propio jugador:
--      depende de las marcas de todos los demás.
--   3. CONFIANZA. `user_achievements` lo escribe el CLIENTE (local-first, sube al
--      sincronizar). Un premio de torneo tiene que derivarse en el SERVIDOR: si el
--      cliente pudiera escribirlo, cualquiera se autoconcede un trofeo. Son modelos
--      de confianza opuestos y no deben compartir tabla.
-- El punto donde sí se tocarán algún día: un logro tipo "gana 5 torneos" encaja
-- perfecto en el motor de condiciones, alimentado por un contador que salga de aquí.
--
-- POR QUÉ `security definer`
-- Calcular un puesto exige leer las marcas AJENAS del torneo, que es justo lo que
-- RLS no permite. La proyección está acotada: devuelve solo filas del propio
-- jugador (`where r.user_id = auth.uid()`) y ningún dato de los demás, ni siquiera
-- el nombre — solo el total de participantes, que es un agregado.
-- =============================================================================

create or replace function public.get_my_event_awards(p_limit integer default 50)
returns table (
    event_id       uuid,
    event_title    text,
    game_id        uuid,
    -- `position` es palabra reservada en PostgreSQL (es una función del estándar):
    -- la columna se llama `final_position`.
    final_position bigint,
    total_players  bigint,
    reward_top_n   smallint,
    badge_key      text,
    ended_at       timestamptz
)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with closed as (
        -- Torneos CERRADOS y premiados en los que el jugador dejó marca. Se filtra
        -- por su propia participación antes de rankear nada: sin esto habría que
        -- ordenar todos los torneos de la historia para descartarlos después.
        select e.*
        from public.events e
        where e.is_published
          and e.ends_at <= now()
          and e.reward_top_n > 0
          and exists (
              select 1 from public.event_entries ee
              where ee.event_id = e.id and ee.user_id = auth.uid() and ee.has_mark
          )
    ),
    ranked as (
        -- Mismo orden que `get_event_leaderboard`, particionado por torneo: los
        -- puestos de la vitrina tienen que coincidir con los que el jugador vio en
        -- la clasificación, o el perfil contaría otra historia.
        select c.id as event_id,
               ee.user_id,
               row_number() over (
                   partition by c.id
                   order by case when c.rank_by_time then ee.best_time_ms end asc,
                            case when c.rank_by_time then null else ee.best_score end desc,
                            ee.user_id
               ) as pos,
               count(*) over (partition by c.id) as total
        from closed c
        join public.event_entries ee
          on ee.event_id = c.id
         and ee.has_mark
         and (not c.rank_by_time or ee.best_time_ms is not null)
    )
    select c.id, c.title, c.game_id, r.pos, r.total, c.reward_top_n, c.reward_badge_key, c.ends_at
    from ranked r
    join closed c on c.id = r.event_id
    where r.user_id = auth.uid()
      and r.pos <= c.reward_top_n
    order by c.ends_at desc
    limit greatest(p_limit, 1);
$$;

comment on function public.get_my_event_awards is
    'Insignias de torneo del jugador: torneos cerrados donde quedó dentro de reward_top_n. Solo devuelve filas propias.';

revoke all on function public.get_my_event_awards(integer) from public, anon;
grant execute on function public.get_my_event_awards(integer) to authenticated;
