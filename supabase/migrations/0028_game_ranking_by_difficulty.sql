-- =============================================================================
-- 0028 — El ranking mundial se separa por dificultad
-- -----------------------------------------------------------------------------
-- PROBLEMA QUE RESUELVE
-- `get_game_ranking` (0027) rankeaba por juego a secas. En los juegos con
-- dificultad elegible (Neon Defuser, Neon Sudoku Matrix) eso premiaba jugar en
-- FÁCIL: los dos usan `BASE_SCORE = 1000` para las cuatro dificultades y el único
-- descuento es el tiempo, así que un tablero fácil resuelto en 60 s (880 pts)
-- aplastaba a un experto resuelto en 5 minutos (400 pts). El jugador que aceptaba
-- el reto de verdad quedaba estructuralmente fuera del top.
--
-- SOLUCIÓN
-- `p_difficulty_level` opcional. Cuando llega, el universo del ranking se acota a
-- las partidas de ESA dificultad: los expertos compiten entre expertos, como en
-- cualquier buscaminas donde el récord de Experto es una tabla aparte de la de
-- Principiante. Es también la única forma honesta con este esquema de puntos —
-- reescalar `BASE_SCORE` por dificultad seguiría dejando una dificultad óptima
-- para farmear, porque el reto real no escala de forma lineal.
--
-- Cuando llega NULL (la inmensa mayoría de los juegos, que arrancan siempre en
-- `difficulty_level = 1`) el comportamiento es idéntico al de 0027: una sola tabla.
-- Qué juegos mandan dificultad lo decide el cliente (`GameRankingScopes`), no el
-- backend: es una decisión de diseño de cada juego, no del esquema.
--
-- POR QUÉ `drop` Y NO `create or replace`
-- Añadir un parámetro cambia la firma, así que `create or replace` habría creado
-- una SOBRECARGA en vez de sustituir: PostgREST se encontraría dos candidatas para
-- `/rpc/get_game_ranking` y resolvería por el juego de argumentos recibido, con el
-- riesgo de seguir sirviendo la versión vieja sin que nadie lo note. Se borra la
-- de 3 argumentos explícitamente.
--
-- El resto del contrato (jsonb de salida, `security definer`, proyección acotada a
-- display_name/score, revoke a public y anon) se mantiene igual que en 0027.
-- =============================================================================

drop function if exists public.get_game_ranking(uuid, integer, uuid);

create or replace function public.get_game_ranking(
    p_game_id          uuid,
    p_score            integer,
    p_progress_id      uuid     default null,
    -- null = tabla única para todo el juego; no null = tabla de esa dificultad.
    p_difficulty_level smallint default null
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with bests as (
        -- Una fila por jugador con su mejor marca en este juego (y, si procede, en
        -- esta dificultad).
        select up.user_id, max(up.score)::integer as best_score
        from public.user_progress up
        where up.game_id = p_game_id
          and (p_difficulty_level is null or up.difficulty_level = p_difficulty_level)
        group by up.user_id
    ),
    ranked as (
        -- `row_number` (no `rank`) para que el puesto sea ÚNICO: la ventana de
        -- vecinos se calcula por rango de posiciones y con empates `rank` podría
        -- devolver decenas de filas con el mismo número. El desempate por
        -- `user_id` es arbitrario pero ESTABLE: el mismo empate se resuelve
        -- siempre igual, así el puesto no baila entre partidas.
        select b.user_id,
               b.best_score,
               row_number() over (order by b.best_score desc, b.user_id) as pos
        from bests b
    ),
    agg as (
        select count(*)::bigint as total from ranked
    ),
    me as (
        select r.pos, r.best_score from ranked r where r.user_id = auth.uid()
    ),
    bounds as (
        -- Ventana de 5 puestos: 1 por encima y 3 por debajo. Si el jugador es el
        -- nº1 no hay nadie encima, así que la ventana se desliza hacia abajo
        -- (1..5) en vez de dejar un hueco en la lista.
        select greatest(1, me.pos - 1) as from_pos from me
    ),
    window_rows as (
        select r.pos,
               r.best_score,
               u.display_name,
               (r.user_id = auth.uid()) as is_me
        from ranked r
        cross join bounds b
        left join public.users u on u.id = r.user_id
        where r.pos between b.from_pos and b.from_pos + 4
    ),
    better as (
        -- % honesto pese al desempate arbitrario de `row_number`: cuenta solo a
        -- quienes tienen una marca ESTRICTAMENTE peor, nunca a los empatados.
        select count(*)::bigint as below
        from ranked r, me
        where r.best_score < me.best_score
    ),
    prev_global as (
        -- Mejor marca ANTES de esta partida, en el mismo universo que el ranking:
        -- con dificultad, el récord mundial es "el récord de Experto", no el del
        -- juego entero (si no, en Experto nunca se batiría nada). Se excluye la
        -- fila recién insertada por su id (lo devuelve `submit_game_result`); sin
        -- esa exclusión la partida se compararía consigo misma y jamás sería récord.
        select max(up.score)::integer as best
        from public.user_progress up
        where up.game_id = p_game_id
          and (p_difficulty_level is null or up.difficulty_level = p_difficulty_level)
          and (p_progress_id is null or up.id <> p_progress_id)
    )
    select jsonb_build_object(
        'rank',             me.pos,
        'total_players',    agg.total,
        'better_than_pct',  round(coalesce(better.below::numeric / nullif(agg.total, 0) * 100, 0), 2),
        -- Estricto (`>`): igualar la mejor marca del mundo no es récord nuevo.
        'is_global_record', (p_score > coalesce(prev_global.best, -1)),
        'entries', coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'rank',            w.pos,
                        'display_name',    w.display_name,   -- null ⇒ la app pinta "Jugador"
                        'score',           w.best_score,
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

comment on function public.get_game_ranking(uuid, integer, uuid, smallint) is
    'Ranking por jugador (mejor marca) de un juego, opcionalmente acotado a una '
    'dificultad: puesto, total, % superado, récord global y ventana de vecinos '
    '(1 arriba + 3 abajo). SECURITY DEFINER: lee scores ajenos pero solo expone '
    'display_name y score, nunca user_id.';

-- `anon` explícito además de `public`: Supabase concede EXECUTE a anon/authenticated
-- por DEFAULT PRIVILEGES al crear la función, y un `revoke ... from public` NO borra
-- esos grants nominales (ver 0004 y 0027).
revoke all     on function public.get_game_ranking(uuid, integer, uuid, smallint) from public, anon;
grant  execute on function public.get_game_ranking(uuid, integer, uuid, smallint) to authenticated;
