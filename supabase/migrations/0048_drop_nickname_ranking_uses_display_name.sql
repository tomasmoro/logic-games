-- =============================================================================
-- 0048 — Se elimina `nickname`: el ranking vuelve a `display_name`, un solo dato
-- -----------------------------------------------------------------------------
-- QUÉ CAMBIA Y POR QUÉ
-- Las migraciones 0044–0046 separaron la identidad pública (`nickname`) de la
-- privada (`display_name`) para no publicar en el ranking el `full_name` que
-- Google rellena solo. La decisión de producto ahora es la contraria: **un único
-- nombre**, elegido por el jugador en el alta, que sirve tanto para el saludo de
-- la app como para el ranking mundial. Dos campos se consideran demasiada
-- superficie (dos validaciones, dos pantallas de edición, una tabla de bloqueados,
-- un cooldown) para lo que aporta a esta escala.
--
-- Contrapartida asumida explícitamente: `display_name` pasa a ser PÚBLICO otra
-- vez. Las filas que hoy tienen el nombre real de Google (alta anterior a esta
-- migración) NO se tocan — se conocen esos usuarios y son todos adultos. La
-- protección para el futuro es que `handle_new_user` deja de copiar `full_name`
-- (ver abajo): las altas nuevas nacen sin nombre y la app se lo pide.
--
-- ESTA MIGRACIÓN REVIERTE 0044, 0045 y 0046. No se editan esos archivos (ya
-- aplicados): se deshacen aquí.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1) El ranking vuelve a leer `display_name`
-- -----------------------------------------------------------------------------
-- Cuerpo idéntico al de 0029 (la versión previa a 0045): lo único que 0045 tocó
-- fue `u.nickname` en lugar de `u.display_name` y la clave del JSON, que SIGUE
-- llamándose `display_name`. Por eso:
--   * `create or replace` y no `drop` + `create`: la firma no cambia, no se crea
--     sobrecarga y no hay un instante sin función.
--   * el cliente ya publicado no necesita actualizarse: la clave `display_name`
--     del jsonb es la misma; solo cambia de dónde sale el valor.
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
-- esos grants nominales (ver 0004, 0027, 0028 y 0045).
revoke all     on function public.get_game_ranking(uuid, integer, uuid, smallint, boolean, integer) from public, anon;
grant  execute on function public.get_game_ranking(uuid, integer, uuid, smallint, boolean, integer) to authenticated;


-- -----------------------------------------------------------------------------
-- 2) El alta automática deja de copiar el nombre real de Google
-- -----------------------------------------------------------------------------
-- Este es el cambio que sustituye a `nickname` como salvaguarda de privacidad: si
-- el perfil nace SIN nombre, `display_name` solo se rellena cuando el jugador
-- escribe uno a propósito —en el formulario de alta por email, o en la pantalla
-- de onboarding que la app muestra tras entrar con Google—. Ninguno de esos
-- caminos publica un nombre que el usuario no haya tecleado.
--
-- Se conserva SOLO la clave `display_name` de `raw_user_meta_data` (la que manda
-- nuestro propio formulario de alta por email). Se quitan `full_name` y `name`,
-- que eran las que aportaba el proveedor OAuth.
--
-- `security definer` y `search_path` fijos: la función escribe en `public` desde
-- un trigger del esquema `auth` (igual que en 0002 y 0026).
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_provider     auth_provider;
    v_display_name text;
begin
    v_provider := case new.raw_app_meta_data->>'provider'
                    when 'google' then 'google'::auth_provider
                    when 'apple'  then 'apple'::auth_provider
                    else 'email'::auth_provider
                  end;

    -- Solo lo que el usuario escribió en NUESTRO formulario. Sin fallback a
    -- `full_name`/`name` del proveedor: un alta con Google nace con NULL y la app
    -- pide el nombre en el onboarding.
    v_display_name := nullif(trim(new.raw_user_meta_data->>'display_name'), '');

    insert into public.users (id, email, display_name, auth_provider, last_login)
    values (new.id, new.email, v_display_name, v_provider, now())
    on conflict (id) do nothing;

    return new;
end;
$$;

comment on function public.handle_new_user() is
    'Crea el perfil espejo en public.users al registrarse. display_name se toma '
    'SOLO de raw_user_meta_data.display_name (formulario de alta propio); las altas '
    'con Google nacen sin nombre y lo eligen en el onboarding (migración 0048).';


-- -----------------------------------------------------------------------------
-- 3) Se elimina toda la infraestructura de `nickname` (0044/0046)
-- -----------------------------------------------------------------------------
-- Orden de borrado forzado por las dependencias: primero lo que cuelga de la
-- columna generada `nickname_key` (constraint no —esa es de `nickname`—, índice
-- sí), luego `nickname_key`, luego `nickname`, y por último `normalize_nickname`,
-- que ya no la usa nadie. `blocked_nickname_patterns` es independiente.

-- El CHECK de forma vive sobre `nickname`; el índice único, sobre `nickname_key`.
alter table public.users drop constraint if exists users_nickname_shape;
drop index if exists public.users_nickname_key_unique;

-- `nickname_key` es `generated always as (public.normalize_nickname(nickname))`:
-- hay que quitarla antes que `nickname` y antes que la función.
alter table public.users drop column if exists nickname_key;
alter table public.users drop column if exists nickname;
alter table public.users drop column if exists nickname_changed_at;

-- Ya sin columna generada que dependa de ella.
drop function if exists public.normalize_nickname(text);

-- Las dos RPC de nickname: sin cliente que las llame tras esta versión de la app.
drop function if exists public.check_nickname_available(text);
drop function if exists public.claim_nickname(text);

-- La blocklist solo la consumían esas RPC.
drop table if exists public.blocked_nickname_patterns;
