-- =============================================================================
-- 0050 — `set_display_name` deja de ser un callejón sin salida
-- -----------------------------------------------------------------------------
-- QUÉ PASÓ
-- Apareció en pruebas una cuenta con fila en `auth.users` pero SIN su fila espejo
-- en `public.users`. Con la 0049 tal cual, ese usuario quedaba atrapado: entra,
-- la app no le encuentra nombre y le muestra la pantalla de onboarding, y al
-- guardar el `update` de `set_display_name` no toca ninguna fila y devuelve
-- `no_profile`. Sin fila no hay nombre, y sin nombre no se sale de la pantalla:
-- un bucle del que el usuario NO puede escapar por sí mismo.
--
-- El comentario de la 0049 decía que no se insertaba aquí "para no duplicar la
-- lógica de alta". El razonamiento era erróneo: el trigger `handle_new_user` es
-- la vía normal, pero cuando esa vía falla —o la fila desaparece después— no
-- queda ninguna otra, porque el trigger solo corre en el INSERT de `auth.users`
-- y ese usuario ya está creado. Hace falta un camino de recuperación.
--
-- QUÉ CAMBIA
--   1. `auth_provider_from_meta` — el mapeo de proveedor, en un solo sitio.
--   2. `set_display_name` hace UPSERT: si el perfil no existe lo crea a partir de
--      `auth.users`, en vez de rendirse con `no_profile`.
--   3. Backfill de los huérfanos que ya existan (idempotente).
--
-- `no_profile` NO desaparece: sigue devolviéndose si ni siquiera hay fila en
-- `auth.users` para ese uid, que ya sería un token de un usuario borrado.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1) El mapeo de proveedor, compartido
-- -----------------------------------------------------------------------------
-- Lo necesitan ahora DOS sitios (`handle_new_user` al dar de alta y
-- `set_display_name` al recuperar un perfil perdido). Se extrae en vez de
-- copiarlo para que añadir un proveedor —Apple, por ejemplo— se haga una sola
-- vez. Toma el jsonb crudo y no el uuid, para que el trigger pueda seguir
-- usándolo sobre `new.raw_app_meta_data` sin ir a buscar la fila.
--
-- IMMUTABLE: es una función pura sobre su argumento, sin acceso a tablas.
create or replace function public.auth_provider_from_meta(p_meta jsonb)
returns public.auth_provider
language sql
immutable
set search_path = ''
as $$
    select case p_meta->>'provider'
             when 'google' then 'google'::public.auth_provider
             when 'apple'  then 'apple'::public.auth_provider
             else 'email'::public.auth_provider
           end;
$$;

comment on function public.auth_provider_from_meta(jsonb) is
    'Traduce raw_app_meta_data->>provider al enum auth_provider. Compartida por '
    'handle_new_user (alta) y set_display_name (recuperación del perfil).';

revoke all on function public.auth_provider_from_meta(jsonb) from public, anon, authenticated;


-- -----------------------------------------------------------------------------
-- 2) La RPC crea el perfil si falta
-- -----------------------------------------------------------------------------
-- UPSERT en vez de UPDATE. El `insert ... select ... from auth.users` sirve para
-- dos cosas a la vez: rellena email y proveedor con los datos reales de la
-- cuenta, y actúa de guarda —si no hay fila en `auth.users` para ese uid, el
-- SELECT no devuelve nada, no se inserta nada y `found` queda en false—.
--
-- El `on conflict do update` cubre la carrera de dos peticiones simultáneas: la
-- segunda encuentra la fila recién creada por la primera y actualiza en vez de
-- reventar con violación de PK.
--
-- Puede leer `auth.users` porque es SECURITY DEFINER y su dueño es `postgres`;
-- el llamante no tiene —ni necesita— ese permiso.
create or replace function public.set_display_name(p_name text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid    uuid := auth.uid();
    v_clean  text := btrim(coalesce(p_name, ''));
    v_reason text;
begin
    if v_uid is null then
        return jsonb_build_object('ok', false, 'reason', 'no_session');
    end if;

    v_reason := public.display_name_rejection(v_clean);
    if v_reason is not null then
        return jsonb_build_object('ok', false, 'reason', v_reason);
    end if;

    insert into public.users (id, email, display_name, auth_provider, last_login)
    select a.id,
           a.email,
           v_clean,
           public.auth_provider_from_meta(a.raw_app_meta_data),
           now()
    from auth.users a
    where a.id = v_uid
    on conflict (id) do update
        set display_name = excluded.display_name,
            updated_at   = now();

    if not found then
        -- Ni siquiera existe la cuenta: token de un usuario ya borrado.
        return jsonb_build_object('ok', false, 'reason', 'no_profile');
    end if;

    return jsonb_build_object('ok', true, 'display_name', v_clean);
end;
$$;

comment on function public.set_display_name(text) is
    'Fija el display_name del usuario de la sesión; valida con display_name_rejection '
    'y CREA el perfil espejo si no existe (a partir de auth.users), para que una '
    'cuenta sin fila en public.users no quede atrapada en el onboarding. Único '
    'camino de escritura desde la app. Devuelve {ok, reason} con CÓDIGO de motivo.';

revoke all     on function public.set_display_name(text) from public, anon;
grant  execute on function public.set_display_name(text) to authenticated;


-- -----------------------------------------------------------------------------
-- 3) El trigger de alta usa el mapeo compartido
-- -----------------------------------------------------------------------------
-- Mismo cuerpo que en la 0049; solo cambia el `case` inline por la función.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_display_name text;
begin
    v_display_name := nullif(trim(new.raw_user_meta_data->>'display_name'), '');

    -- Mismas reglas que la RPC. Si no pasa, la cuenta se crea sin nombre y la app
    -- lo pide en el onboarding (migración 0049).
    if v_display_name is not null
       and public.display_name_rejection(v_display_name) is not null then
        v_display_name := null;
    end if;

    insert into public.users (id, email, display_name, auth_provider, last_login)
    values (
        new.id,
        new.email,
        v_display_name,
        public.auth_provider_from_meta(new.raw_app_meta_data),
        now()
    )
    on conflict (id) do nothing;

    return new;
end;
$$;

comment on function public.handle_new_user() is
    'Crea el perfil espejo en public.users al registrarse. display_name se toma '
    'SOLO de raw_user_meta_data.display_name (formulario propio) y pasa por '
    'display_name_rejection: si no supera las reglas se guarda NULL y la app lo pide '
    'en el onboarding (migraciones 0048, 0049 y 0050).';


-- -----------------------------------------------------------------------------
-- 4) Backfill de los perfiles que ya faltan
-- -----------------------------------------------------------------------------
-- La autocuración de §2 solo actúa cuando el usuario intenta poner su nombre. Este
-- backfill arregla de una vez a los que ya están huérfanos, para que no dependan de
-- pasar por esa pantalla (el saludo, el plan y la sincronización de progreso
-- también leen `public.users`).
--
-- `display_name` queda a NULL a propósito: no hay forma honesta de recuperar el
-- que tuvieran, y NULL es justo lo que hace que la app se lo pida. El progreso
-- borrado por el CASCADE no se puede recuperar aquí, pero el cliente es
-- local-first y vuelve a subir lo que tenga en el dispositivo al sincronizar.
insert into public.users (id, email, display_name, auth_provider, last_login)
select a.id,
       a.email,
       null,
       public.auth_provider_from_meta(a.raw_app_meta_data),
       now()
from auth.users a
left join public.users u on u.id = a.id
where u.id is null
on conflict (id) do nothing;
