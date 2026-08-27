-- =============================================================================
-- 0045 — El ranking mundial publica el NICKNAME, no el nombre real
-- -----------------------------------------------------------------------------
-- QUÉ CAMBIA
-- Una sola cosa: de dónde sale el nombre que se enseña a los demás. Antes
-- `u.display_name`, que desde 0026 se rellena solo con el `full_name` de Google;
-- ahora `u.nickname`, que el jugador elige a propósito sabiendo que es público
-- (ver 0044 para el porqué completo).
--
-- NO hay fallback a `display_name` cuando el nickname es null, y es deliberado:
-- mantenerlo perpetuaría exactamente la fuga que esta migración cierra. Sin
-- nickname, la entrada viaja con `null` y la app pinta su genérico "Jugador" —un
-- comportamiento que ya existe y está probado, porque `display_name` también podía
-- venir null.
--
-- POR QUÉ NO SE TOCA NI LA FIRMA NI LA CLAVE JSON
-- Esto es lo importante para no romper a nadie. La app YA PUBLICADA llama a esta
-- RPC en dos sitios muy calientes: al terminar cada partida y en la antesala de una
-- docena de juegos (`WorldRankingPreviewPanel`). Si se cambiara la firma, PostgREST
-- no resolvería la llamada; si se renombrara la clave `display_name` a `nickname`,
-- el cliente viejo dejaría de encontrarla.
--
-- Manteniendo ambas, las versiones ya instaladas empiezan a mostrar nicknames **el
-- mismo día que se aplica esta migración, sin publicar una actualización**. Renombrar
-- la clave a `nickname` queda para una versión futura del cliente, si algún día
-- compensa; hoy no compensa.
--
-- Por el mismo motivo se usa `create or replace` en vez de `drop` + `create`: la
-- firma es idéntica a la de 0029, así que no se crea sobrecarga y no hay ni un
-- instante en el que la función no exista.
-- =============================================================================

create or replace function public.get_game_ranking(
    p_game_id             uuid,
    p_score               integer,
    p_progress_id         uuid     default null,
    -- null = tabla única para todo el juego; no null = tabla de esa dificultad.
    p_difficulty_level    smallint default null,
    -- false = ranking por puntos (mayor gana); true = por tiempo (menor gana).
    p_rank_by_time        boolean  default false,
    -- Tiempo de ESTA partida; solo se usa cuando se rankea por tiempo.
    p_completion_time_ms  integer  default null
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with bests as (
        -- Una fila por jugador con su mejor marca en este juego (y, si procede, en
        -- esta dificultad). "Mejor" depende del criterio: máximo puntaje, o mínimo
        -- tiempo cuando se rankea por tiempo.
        select up.user_id,
               case when p_rank_by_time
                    then min(up.completion_time_ms) filter (where up.completion_time_ms > 0)
                    else max(up.score)
               end::integer as best_metric
        from public.user_progress up
        where up.game_id = p_game_id
          and (p_difficulty_level is null or up.difficulty_level = p_difficulty_level)
        group by up.user_id
    ),
    eligible as (
        -- Rankeando por tiempo, un jugador sin ninguna partida cronometrada queda
        -- fuera de la tabla (su `best_metric` es null), en vez de aparecer con un
        -- hueco imposible de ordenar.
        select * from bests where best_metric is not null
    ),
    ranked as (
        -- `row_number` (no `rank`) para que el puesto sea ÚNICO: la ventana de
        -- vecinos se calcula por rango de posiciones y con empates `rank` podría
        -- devolver decenas de filas con el mismo número. El desempate por
        -- `user_id` es arbitrario pero ESTABLE: el mismo empate se resuelve
        -- siempre igual, así el puesto no baila entre partidas.
        select e.user_id,
               e.best_metric,
               row_number() over (
                   order by case when p_rank_by_time then e.best_metric end asc,
                            case when p_rank_by_time then null else e.best_metric end desc,
                            e.user_id
               ) as pos
        from eligible e
    ),
    agg as (
        select count(*)::bigint as total from ranked
    ),
    me as (
        select r.pos, r.best_metric from ranked r where r.user_id = auth.uid()
    ),
    bounds as (
        -- Ventana de 5 puestos: 1 por encima y 3 por debajo. Si el jugador es el
        -- nº1 no hay nadie encima, así que la ventana se desliza hacia abajo
        -- (1..5) en vez de dejar un hueco en la lista.
        select greatest(1, me.pos - 1) as from_pos from me
    ),
    window_rows as (
        select r.pos,
               r.best_metric,
               u.nickname,
               (r.user_id = auth.uid()) as is_me
        from ranked r
        cross join bounds b
        left join public.users u on u.id = r.user_id
        where r.pos between b.from_pos and b.from_pos + 4
    ),
    better as (
        -- % honesto pese al desempate arbitrario de `row_number`: cuenta solo a
        -- quienes tienen una marca ESTRICTAMENTE peor, nunca a los empatados. Peor
        -- es "más lento" o "menos puntos" según el criterio.
        select count(*)::bigint as below
        from ranked r, me
        where case when p_rank_by_time
                   then r.best_metric > me.best_metric
                   else r.best_metric < me.best_metric
              end
    ),
    prev_global as (
        -- Mejor marca ANTES de esta partida, en el mismo universo que el ranking.
        -- Se excluye la fila recién insertada por su id (lo devuelve
        -- `submit_game_result`); sin esa exclusión la partida se compararía consigo
        -- misma y jamás sería récord.
        select case when p_rank_by_time
                    then min(up.completion_time_ms) filter (where up.completion_time_ms > 0)
                    else max(up.score)
               end::integer as best
        from public.user_progress up
        where up.game_id = p_game_id
          and (p_difficulty_level is null or up.difficulty_level = p_difficulty_level)
          and (p_progress_id is null or up.id <> p_progress_id)
    )
    select jsonb_build_object(
        'rank',             me.pos,
        'total_players',    agg.total,
        'better_than_pct',  round(coalesce(better.below::numeric / nullif(agg.total, 0) * 100, 0), 2),
        -- Estricto: igualar la mejor marca del mundo no es récord nuevo. Sin marca
        -- previa (primer jugador del universo) siempre lo es.
        'is_global_record', case
            when p_rank_by_time then
                p_completion_time_ms is not null
                and p_completion_time_ms > 0
                and (prev_global.best is null or p_completion_time_ms < prev_global.best)
            else p_score > coalesce(prev_global.best, -1)
        end,
        'entries', coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'rank',            w.pos,
                        -- La CLAVE sigue llamandose display_name a proposito (ver cabecera): lo que
                        -- cambia es de donde sale el valor. null ⇒ la app pinta "Jugador".
                        'display_name',    w.nickname,
                        -- Sigue llamándose `score` por compatibilidad con el cliente;
                        -- rankeando por tiempo su unidad son milisegundos (el cliente
                        -- lo sabe por `rankedByTime` y lo formatea como tiempo).
                        'score',           w.best_metric,
                        'is_current_user', w.is_me
                    )
                    order by w.pos
                )
                from window_rows w
            ),
            '[]'::jsonb
        )
    )
    -- `me` vacío (el jugador no tiene ninguna partida en este juego/dificultad) ⇒
    -- cero filas ⇒ NULL y el cliente trata el ranking como no disponible.
    from me, agg, better, prev_global;
$$;

comment on function public.get_game_ranking(uuid, integer, uuid, smallint, boolean, integer) is
    'Ranking por jugador (mejor marca) de un juego, opcionalmente acotado a una '
    'dificultad y ordenable por tiempo (menor gana) en vez de por puntos: puesto, '
    'total, % superado, récord global y ventana de vecinos (1 arriba + 3 abajo). '
    'SECURITY DEFINER: lee marcas ajenas pero solo expone el NICKNAME y la marca, '
    'nunca user_id ni el display_name privado.';

-- Los grants no se pierden con `create or replace`, pero se reafirman por si esta
-- migración se aplica sobre un entorno donde la función se recreó a mano (mismo
-- motivo documentado en 0004/0027/0028: el revoke a `public` no borra el grant
-- nominal que Supabase concede a `anon` por DEFAULT PRIVILEGES).
revoke all     on function public.get_game_ranking(uuid, integer, uuid, smallint, boolean, integer) from public, anon;
grant  execute on function public.get_game_ranking(uuid, integer, uuid, smallint, boolean, integer) to authenticated;
