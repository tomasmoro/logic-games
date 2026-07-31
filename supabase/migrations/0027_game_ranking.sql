-- =============================================================================
-- 0027 — Ranking de jugadores por juego (comparativa de fin de partida)
-- -----------------------------------------------------------------------------
-- QUÉ AÑADE
-- `get_game_ranking(game, score, progress_id)`: en UNA llamada devuelve todo lo
-- que la tarjeta de fin de partida necesita para la "comparativa con el mundo":
--   * el puesto del jugador y el tamaño del ranking,
--   * el % de jugadores que supera,
--   * si la partida acaba de batir el RÉCORD GLOBAL del juego,
--   * la ventana del ranking alrededor del jugador (1 arriba + 3 abajo) con
--     nombre y puntos de cada uno.
--
-- POR QUÉ UN RANKING DE JUGADORES Y NO DE PARTIDAS
-- `get_score_percentile` (0003) compara la partida contra TODAS las partidas
-- históricas. Sirve para el mensaje "eres mejor que el X%", pero no se puede
-- listar: un mismo usuario aparecería 40 veces con sus 40 intentos. Aquí el
-- ranking es **por jugador y por su mejor marca** (`max(score)`), que es lo que
-- el jugador entiende por "puesto". Todos los juegos son HIGHER_IS_BETTER
-- (`GameProgression.kt`), así que ordenar por score desc vale para el catálogo
-- entero; si algún día entra un juego LOWER_IS_BETTER habrá que parametrizar el
-- sentido del orden aquí y en 0003.
--
-- POR QUÉ `security definer`
-- Igual que el percentil: RLS impide a un usuario leer `user_progress` ajeno.
-- La función necesita esos scores para rankear, pero la proyección está acotada
-- a propósito: devuelve SOLO `display_name` y `score` de los vecinos.
-- **Nunca devuelve `user_id` ni el email**, para no convertir el leaderboard en
-- un directorio de usuarios ni permitir correlacionar filas entre juegos.
-- `display_name` es dato que el propio usuario elige mostrar (Ajustes → Nombre).
--
-- POR QUÉ jsonb Y NO `returns table`
-- La respuesta mezcla escalares (puesto, total, %) con una LISTA (los vecinos).
-- Con `returns table` habría que repetir los escalares en cada fila o hacer dos
-- RPC (= dos round-trips en el momento más sensible de la UX, el fin de partida).
-- Un único objeto jsonb lo resuelve en una llamada y se decodifica de una pieza
-- en el cliente (`RemoteProgressDataSource.GameRankingRow`).
--
-- COSTE / ESCALA
-- Agrega `max(score)` por usuario recorriendo las filas del juego a través del
-- índice `user_progress_game_score_idx (game_id, score)`. Es aceptable al volumen
-- actual y solo se ejecuta una vez por partida terminada. Cuando el historial
-- crezca, el reemplazo natural es una vista materializada de mejores marcas por
-- (game_id, user_id) refrescada de forma incremental: la firma de la RPC no
-- cambiaría, así que el cliente no se entera.
-- =============================================================================

create or replace function public.get_game_ranking(
    p_game_id     uuid,
    p_score       integer,
    p_progress_id uuid default null
)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with bests as (
        -- Una fila por jugador con su mejor marca en este juego.
        select up.user_id, max(up.score)::integer as best_score
        from public.user_progress up
        where up.game_id = p_game_id
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
        -- Mejor marca del juego ANTES de esta partida: se excluye la fila recién
        -- insertada por su id (lo devuelve `submit_game_result`). Sin esa
        -- exclusión la partida se compararía consigo misma y jamás sería récord.
        select max(up.score)::integer as best
        from public.user_progress up
        where up.game_id = p_game_id
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
    -- `me` vacío (el jugador no tiene ninguna partida en este juego) ⇒ cero filas
    -- ⇒ la función devuelve NULL y el cliente trata el ranking como no disponible.
    from me, agg, better, prev_global;
$$;

comment on function public.get_game_ranking(uuid, integer, uuid) is
    'Ranking por jugador (mejor marca) de un juego: puesto, total, % superado, '
    'récord global y ventana de vecinos (1 arriba + 3 abajo). SECURITY DEFINER: '
    'lee scores ajenos pero solo expone display_name y score, nunca user_id.';

-- `anon` explícito además de `public`: Supabase concede EXECUTE a anon/authenticated
-- por DEFAULT PRIVILEGES al crear la función, y un `revoke ... from public` NO borra
-- esos grants nominales (misma corrección que 0004 hizo con las RPC de la FASE 2).
-- Sin sesión la función no tendría nada que devolver (auth.uid() null ⇒ NULL), pero
-- una RPC `security definer` que lee scores ajenos no debe quedar abierta a anon.
revoke all     on function public.get_game_ranking(uuid, integer, uuid) from public, anon;
grant  execute on function public.get_game_ranking(uuid, integer, uuid) to authenticated;
