-- =============================================================================
-- 0026 — El alta automática del perfil rellena `display_name`
-- =============================================================================
-- PROBLEMA QUE RESUELVE
-- `handle_new_user` (definida en 0002) creaba la fila espejo de `public.users`
-- con `display_name` a NULL siempre. Consecuencias:
--   * Quien entra con Google acaba con perfil "Sin definir" pese a que Google ya
--     nos manda su nombre en el ID token.
--   * El alta por email no tenía forma de fijar el nombre en el mismo paso: el
--     cliente tendría que hacer un UPDATE posterior, que puede fallar (offline,
--     app cerrada) y dejar el perfil a medias.
--
-- SOLUCIÓN
-- Leer el nombre de `raw_user_meta_data`, que es donde acaban tanto los datos que
-- el cliente pasa en `signUp(data = ...)` como los que GoTrue copia del proveedor
-- OAuth. Se prueban tres claves por orden de preferencia:
--   1. `display_name` — lo que el usuario escribió en NUESTRO formulario de alta.
--      Manda sobre lo demás: es una elección explícita.
--   2. `full_name`    — clave estándar de OIDC que rellena Google.
--   3. `name`         — variante que usan otros proveedores; red de seguridad.
--
-- `nullif(trim(...), '')` evita guardar cadenas en blanco como si fueran nombre:
-- preferimos NULL, que la UI ya sabe mostrar como "Sin definir".
--
-- SE MANTIENE `security definer` y el `search_path` fijo de la versión original
-- (la función escribe en `public` desde un trigger del esquema `auth`).
--
-- IDEMPOTENTE: es un `create or replace` de la función; el trigger de 0002 sigue
-- apuntando a ella y no hace falta recrearlo. No toca filas existentes — los
-- perfiles ya creados sin nombre se arreglan desde Ajustes → Nombre de usuario.
-- =============================================================================

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

    v_display_name := coalesce(
        nullif(trim(new.raw_user_meta_data->>'display_name'), ''),
        nullif(trim(new.raw_user_meta_data->>'full_name'), ''),
        nullif(trim(new.raw_user_meta_data->>'name'), '')
    );

    insert into public.users (id, email, display_name, auth_provider, last_login)
    values (new.id, new.email, v_display_name, v_provider, now())
    on conflict (id) do nothing;

    return new;
end;
$$;

comment on function public.handle_new_user() is
    'Crea el perfil espejo en public.users al registrarse. Toma display_name de '
    'raw_user_meta_data: display_name (formulario propio) > full_name (Google) > name.';
