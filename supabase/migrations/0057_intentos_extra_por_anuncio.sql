-- =============================================================================
-- 0057 — Intentos extra a cambio de un anuncio
-- -----------------------------------------------------------------------------
-- DECISIÓN DE PRODUCTO
-- Un torneo da **un intento gratis**; quien quiera otro ve un anuncio
-- recompensado. Encaja con el resto del juego (la pista y el revivir del Sudoku
-- ya funcionan así) y con la regla de puntuación del proyecto: las ayudas se
-- pagan.
--
-- POR QUÉ HAY UN TOPE (`ad_attempts_limit`) Y NO ANUNCIOS INFINITOS
-- Con un tablero fijo y extras ilimitados, el torneo lo gana quien más anuncios
-- aguante: premiaría paciencia, no cabeza, y vaciaría de sentido el tope de
-- intentos que justifica todo lo demás (incluido que abandonar cueste, 0056). El
-- tope es **por evento**, no global, para poder calibrarlo torneo a torneo sin
-- tocar código.
--
-- POR QUÉ LOS EXTRAS VIVEN EN `event_entries` Y NO EN EL EVENTO
-- Son del JUGADOR, no del torneo: dos participantes del mismo evento tienen cupos
-- distintos según los anuncios que haya visto cada uno. El cupo real de alguien es
-- `events.attempts_limit + event_entries.extra_attempts` (ver 0058).
--
-- LÍMITE ASUMIDO: la recompensa se concede al volver del anuncio, sin verificación
-- de servidor. Es la misma confianza que ya asumen la pista y el revivir; AdMob
-- solo ofrece prueba verificable montando callbacks de servidor a servidor, que
-- hoy este backend no tiene. `grant_event_attempt` sí impone el tope, así que lo
-- peor que puede hacer un cliente manipulado es saltarse el anuncio — nunca
-- superar el cupo del torneo.
--
-- SQLSTATE nuevo: KXE04 = sin intentos extra disponibles (tope agotado).
-- =============================================================================

alter table public.events
    add column if not exists ad_attempts_limit smallint not null default 0
        check (ad_attempts_limit >= 0);

comment on column public.events.ad_attempts_limit is
    'Cuántos intentos EXTRA puede desbloquear un jugador viendo un anuncio. 0 = ninguno; el tope existe para que el torneo no lo gane quien más anuncios ve.';

alter table public.event_entries
    add column if not exists extra_attempts smallint not null default 0
        check (extra_attempts >= 0);

comment on column public.event_entries.extra_attempts is
    'Intentos extra ya desbloqueados por anuncio. El cupo real del jugador es events.attempts_limit + esto.';

-- Concede un intento extra. SECURITY DEFINER por lo mismo que el resto:
-- `event_entries` no tiene policy de escritura, y el cupo no puede quedar en manos
-- del cliente.
create or replace function public.grant_event_attempt(p_event_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid   uuid := auth.uid();
    v_event public.events%rowtype;
    v_extra smallint;
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

    select ee.extra_attempts into v_extra
    from public.event_entries ee
    where ee.event_id = p_event_id and ee.user_id = v_uid
    for update;

    if coalesce(v_extra, 0) >= v_event.ad_attempts_limit then
        raise exception 'Sin intentos extra disponibles' using errcode = 'KXE04';
    end if;

    -- La fila puede no existir todavía: se desbloquea un extra ANTES de jugar
    -- ningún intento solo si el torneo da 0 libres, pero el caso tiene que estar
    -- cubierto igual. Nace con attempts = 0 y sin marca.
    insert into public.event_entries (event_id, user_id, best_score, best_time_ms, attempts, has_mark, extra_attempts)
    values (p_event_id, v_uid, 0, null, 0, false, 1)
    on conflict (event_id, user_id) do update set
        extra_attempts = event_entries.extra_attempts + 1,
        updated_at     = now();

    return jsonb_build_object(
        'extra_attempts', coalesce(v_extra, 0) + 1,
        'ad_attempts_limit', v_event.ad_attempts_limit
    );
end;
$$;

comment on function public.grant_event_attempt is
    'Concede un intento extra de torneo (contrapartida de un anuncio recompensado), dentro del tope ad_attempts_limit.';

revoke all on function public.grant_event_attempt(uuid) from public, anon;
grant execute on function public.grant_event_attempt(uuid) to authenticated;
