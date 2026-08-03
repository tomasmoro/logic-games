-- =============================================================================
-- 0029 — El ranking mundial puede ordenarse por TIEMPO
-- -----------------------------------------------------------------------------
-- PROBLEMA QUE RESUELVE
-- `get_game_ranking` (0027, acotada por dificultad en 0028) ordena siempre por
-- `score`. En Neon Hyper-Cube eso no responde a la pregunta que se hace cualquiera
-- que termina un cubo: **quién lo ha resuelto más rápido**. Dentro de un nivel
-- todos los jugadores se enfrentan a una mezcla de la misma profundidad, así que el
-- reto es idéntico y el tiempo es la comparación limpia; el puntaje, en cambio,
-- mezcla velocidad con estilo (eficiencia de movimientos, deshaceres usados) y dos
-- jugadores igual de rápidos pueden acabar en puestos muy distintos.
--
-- SOLUCIÓN
-- `p_rank_by_time`: cuando llega `true`, el universo se ordena por el MEJOR TIEMPO
-- de cada jugador (mínimo `completion_time_ms`, ascendente) en vez de por su mejor
-- puntaje. El resto del contrato no cambia: mismo jsonb de salida, misma ventana de
-- vecinos, mismo acotado opcional por dificultad — que en este juego es lo que
-- separa las tablas por nivel (ver `GameRankingScopes`), imprescindible aquí: con
-- una tabla única, "el más rápido del mundo" sería siempre quien jugó el nivel 1.
--
-- `p_completion_time_ms` acompaña a `p_score` para poder decidir el récord global
-- con la métrica correcta. Se pasa aparte en vez de reutilizar `p_score` para no
-- dejar un parámetro que significa dos cosas según otro parámetro.
--
-- Solo se descartan filas con `completion_time_ms <= 0`: son partidas de juegos que
-- no miden tiempo (o datos antiguos), y colarlas pondría un imbatible "0 ms" en lo
-- alto de la tabla.
--
-- POR QUÉ `drop` Y NO SOLO `create or replace`
-- Misma razón que en 0028: añadir parámetros cambia la firma y `create or replace`
-- crearía una SOBRECARGA. PostgREST tendría dos candidatas para
-- `/rpc/get_game_ranking` y podría seguir sirviendo la versión vieja sin que nadie
-- lo note, así que la de 4 argumentos se borra explícitamente.
-- =============================================================================

drop function if exists public.get_game_ranking(uuid, integer, uuid, smallint);

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
               u.display_name,
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
                        'display_name',    w.display_name,   -- null ⇒ la app pinta "Jugador"
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
    'SECURITY DEFINER: lee marcas ajenas pero solo expone display_name y la marca, '
    'nunca user_id.';

-- `anon` explícito además de `public`: Supabase concede EXECUTE a anon/authenticated
-- por DEFAULT PRIVILEGES al crear la función, y un `revoke ... from public` NO borra
-- esos grants nominales (ver 0004, 0027 y 0028).
revoke all     on function public.get_game_ranking(uuid, integer, uuid, smallint, boolean, integer) from public, anon;
grant  execute on function public.get_game_ranking(uuid, integer, uuid, smallint, boolean, integer) to authenticated;
