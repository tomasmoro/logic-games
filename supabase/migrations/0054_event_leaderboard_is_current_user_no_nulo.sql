-- =============================================================================
-- 0054 — `is_current_user` nunca es null en la tabla del torneo
-- -----------------------------------------------------------------------------
-- `r.user_id = auth.uid()` vale **null**, no false, cuando no hay sesión: la
-- comparación con null se propaga. Se detectó probando `get_event_leaderboard`
-- (0053) con `service_role`, donde `auth.uid()` es null.
--
-- Importa porque el cliente mapea esa clave a un `Boolean` NO nulo
-- (`LeaderboardEntry.isCurrentUser`): un null ahí no deja "sin resaltar" una fila,
-- revienta la deserialización del leaderboard entero. Se arregla en el servidor,
-- que es donde vive el contrato del jsonb, y no con un default en el cliente —
-- así cualquier consumidor futuro recibe ya el booleano completo.
--
-- Único cambio respecto a 0053: `coalesce(r.user_id = auth.uid(), false)`.
-- El cuerpo se repite entero porque `create or replace` no admite parches
-- parciales; la firma no cambia, así que no hay sobrecarga ni instante sin función.
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
                        from row_json where user_id = auth.uid())
             )
           end;
$$;

revoke all on function public.get_event_leaderboard(uuid, integer) from public, anon;
grant execute on function public.get_event_leaderboard(uuid, integer) to authenticated;
