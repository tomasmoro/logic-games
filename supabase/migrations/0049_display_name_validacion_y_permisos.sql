-- =============================================================================
-- 0049 — `display_name` pasa a ser un dato validado y de escritura controlada
-- -----------------------------------------------------------------------------
-- QUÉ CAMBIA Y POR QUÉ
-- La 0048 unificó la identidad en `display_name` y lo devolvió al ranking, pero
-- se llevó por delante toda la validación que `nickname` sí tenía (el CHECK de
-- forma `users_nickname_shape`, `normalize_nickname` y `blocked_nickname_patterns`).
-- El resultado es que hoy `display_name`:
--
--   * no tiene NINGUNA constraint en la BD (ni longitud ni forma), y
--   * es escribible por el propio usuario con un PATCH directo a PostgREST,
--     porque `authenticated` conserva el grant de UPDATE sobre la columna y la
--     política `users_update_own` solo acota QUÉ FILA, nunca qué valor.
--
-- Como ese nombre lo ven los demás jugadores en `get_game_ranking`, es contenido
-- generado por usuarios distribuido a terceros: las políticas de UGC de Play y la
-- guideline 1.2 de Apple exigen filtrado. Esta migración reconstruye la defensa
-- sobre la columna que heredó el rol público:
--
--   1. `normalize_display_name` — clave canónica (leet, homoglifos, separadores).
--   2. `blocked_display_name_patterns` — blocklist, recalibrada (ver §2).
--   3. CHECK de forma y longitud sobre la columna.
--   4. `display_name_rejection` — las reglas, en un solo sitio.
--   5. RPC `set_display_name` como único camino de escritura desde la app.
--   6. `handle_new_user` validando también el nombre del alta por email.
--   7. `revoke` del UPDATE directo, que es lo que cierra la puerta de atrás.
--
-- Los pasos 3 y 5 son complementarios, no redundantes: el CHECK es la red que no
-- se puede saltar ni con service_role despistado; el revoke es lo que obliga a
-- pasar por la RPC, que es donde vive la blocklist (dato mutable, imposible de
-- meter en un CHECK).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1) Clave canónica
-- -----------------------------------------------------------------------------
-- Idéntica a `normalize_nickname` (0044) salvo que aquí el espacio TAMBIÉN se
-- elimina: `display_name` admite espacios interiores (es el nombre con el que la
-- app saluda), así que sin quitarlos "p u t a" esquivaría la blocklist.
--
-- IMMUTABLE y `search_path = ''`: la consumen la RPC y los patrones, y su
-- resultado no puede depender del locale — si `lower()` cambiara de comportamiento
-- entre configuraciones, dos nombres que hoy colisionan dejarían de hacerlo y el
-- filtro se volvería una mentira silenciosa.
--
-- NO usa `unaccent()` a propósito: no es IMMUTABLE (depende de un diccionario
-- alterable en caliente). El plegado se hace a mano con translate(), que sí lo es.
create or replace function public.normalize_display_name(p_text text)
returns text
language sql
immutable
strict
set search_path = ''
as $$
    -- 1) fuera separadores decorativos: "k_o_r_t_e_x" y "K O R T E X" colapsan.
    -- 2) minúsculas.
    -- 3) plegado de homoglifos: dígitos usados como letras (leet) y cirílicos que
    --    se dibujan igual que su latina. Así "K0RTEX" y "Кortex" (К cirílica)
    --    producen la MISMA clave que "kortex".
    select translate(
        translate(lower(p_text), '_-. ', ''),
        '0134579áàäâãéèëêíìïîóòöôõúùüûñçкаеорсхувмтн',
        'oieastgaaaaaeeeeiiiiooooouuuunckaeopcxybmth'
    );
$$;

comment on function public.normalize_display_name(text) is
    'Clave canónica de un display_name: sin separadores ni espacios, en minúsculas '
    'y con homoglifos (leet + cirílicos) plegados. Es contra ESTA clave, y no '
    'contra el texto crudo, contra la que se evalúa la blocklist.';

revoke all     on function public.normalize_display_name(text) from public, anon;
grant  execute on function public.normalize_display_name(text) to authenticated;


-- -----------------------------------------------------------------------------
-- 2) Blocklist
-- -----------------------------------------------------------------------------
-- Recalibrada respecto a la semilla de 0044, que tenía dos defectos que solo se
-- ven al probarla (ver `supabase/tests/0049_display_name.sql`):
--
--   * FALSOS POSITIVOS. `pene` como subcadena rechaza "Penelope"; `nazi` rechaza
--     "Nazir". Son nombres legítimos, y el usuario al que se los rechazas no
--     entiende por qué. Esos patrones pasan a `whole_word`.
--   * PATRONES IMPOSIBLES. `coño` no podía casar NUNCA, porque se evaluaba contra
--     la clave normalizada y en una clave no quedan ni `ñ` ni `ó`. Escrito en
--     forma normalizada sería `cono`, que como subcadena destroza "Conocedor" o
--     "Reconocido". Va también como `whole_word`.
--
-- `whole_word` cambia el objeto de la comparación: casa por palabra completa
-- sobre el nombre en crudo (`\m`/`\M`) y, además, contra la clave completa, para
-- que "P e n e" —cuya clave es "pene"— caiga igual sin arrastrar a "Penelope",
-- cuya clave es "penelope".
create table if not exists public.blocked_display_name_patterns (
    id         smallint generated always as identity primary key,
    -- Regex SIN anclas cuando `whole_word` es true (las pone la RPC); con las
    -- anclas que haga falta (`^…`) cuando se evalúa como subcadena de la clave.
    pattern    text    not null,
    -- false = subcadena de la clave normalizada (atrapa variantes leet sin
    -- enumerarlas). true = palabra completa, para raíces cortas que aparecen
    -- dentro de nombres legítimos.
    whole_word boolean not null default false,
    reason     text    not null default 'ofensivo',
    created_at timestamptz not null default now(),
    constraint blocked_display_name_patterns_pattern_unique unique (pattern)
);

alter table public.blocked_display_name_patterns enable row level security;
-- Sin policies: es deliberado, no un olvido. Publicar la lista de lo prohibido es
-- publicar el mapa de cómo esquivarla.

comment on table public.blocked_display_name_patterns is
    'Patrones de display_name prohibidos (ofensivos y nombres reservados). RLS '
    'activo y SIN policies: ilegible por cualquier cliente; solo la lee '
    'set_display_name, que es SECURITY DEFINER. La lista se amplía en caliente.';

insert into public.blocked_display_name_patterns (pattern, whole_word, reason) values
    -- Reservados: hacerse pasar por la marca o por soporte es el vector de
    -- suplantación más rentable. Anclados al principio para que "KortexSupport"
    -- caiga igual que "Kortex".
    ('^kortex',    false, 'reservado'),
    ('^admin',     false, 'reservado'),
    ('^moderador', false, 'reservado'),
    ('^soporte',   false, 'reservado'),
    ('^support',   false, 'reservado'),
    ('^staff',     false, 'reservado'),
    ('^oficial',   false, 'reservado'),
    ('^official',  false, 'reservado'),
    ('^sistema',   false, 'reservado'),
    -- Ofensivos por subcadena: raíces largas, sin colisiones conocidas.
    ('puta',       false, 'ofensivo'),
    ('mierda',     false, 'ofensivo'),
    ('joder',      false, 'ofensivo'),
    ('fuck',       false, 'ofensivo'),
    ('shit',       false, 'ofensivo'),
    ('bitch',      false, 'ofensivo'),
    ('hitler',     false, 'ofensivo'),
    -- Ofensivos por palabra completa: raíces cortas que viven dentro de nombres
    -- legítimos (Penelope, Nazir, Conocedor…).
    ('pene',       true,  'ofensivo'),
    ('polla',      true,  'ofensivo'),
    ('cono',       true,  'ofensivo'),
    ('coño',       true,  'ofensivo'),
    ('nazi',       true,  'ofensivo')
on conflict (pattern) do nothing;


-- -----------------------------------------------------------------------------
-- 3) Forma y longitud, en la propia columna
-- -----------------------------------------------------------------------------
-- 3..20 para empatar con `DisplayNameRules.MIN/MAX_LENGTH` del cliente: las tres
-- pantallas que editan el nombre (alta por email, onboarding y Ajustes) validan
-- con esos números, o el usuario se come un error genérico al guardar algo que la
-- app le dejó escribir.
--
-- La forma admite espacios INTERIORES simples: `display_name` es también el
-- nombre con el que saluda la app, y prohibirlos dejaría fuera a cualquier "Ana
-- María". No admite espacios dobles ni en los bordes (de ahí el `btrim` y la
-- alternancia del regex), que solo sirven para colarse arriba en listas o para
-- clonar visualmente el nombre de otro.
--
-- La lista blanca de caracteres es deliberadamente latina: aceptar cualquier
-- letra Unicode reabriría la suplantación por homoglifos (la "К" cirílica que el
-- normalizador pliega). Coste asumido: al internacionalizar habrá que ampliarla
-- por alfabeto, no de golpe.
--
-- NOT VALID a propósito: hay UNA fila anterior con 24 caracteres (nombre real
-- traído de Google antes de la 0048). Validarla la dejaría bloqueada sin que su
-- dueño haya hecho nada. Las filas nuevas y cualquier UPDATE sobre esa fila sí se
-- comprueban, que es lo que importa.
alter table public.users
    drop constraint if exists users_display_name_shape;
alter table public.users
    add constraint users_display_name_shape check (
        display_name is null
        or (
            char_length(btrim(display_name)) between 3 and 20
            and btrim(display_name) ~ '^[A-Za-z0-9ÁÉÍÓÚÜÑáéíóúüñ_-]+( [A-Za-z0-9ÁÉÍÓÚÜÑáéíóúüñ_-]+)*$'
        )
    ) not valid;

comment on column public.users.display_name is
    'Nombre del jugador. Es PÚBLICO: get_game_ranking lo muestra a los vecinos de '
    'tabla. Solo se escribe vía set_display_name (0049); authenticated NO tiene '
    'UPDATE sobre esta columna.';


-- -----------------------------------------------------------------------------
-- 4) El validador compartido
-- -----------------------------------------------------------------------------
-- Vive aparte, y no dentro de `set_display_name`, porque hay DOS caminos por los
-- que un nombre llega a la columna y ambos tienen que aplicar las mismas reglas:
--
--   * `set_display_name` — la app fijando o cambiando el nombre.
--   * `handle_new_user`  — el alta por email, que escribe el `display_name` que
--     viajó como metadato del registro (ver §6).
--
-- Sin compartirlo, el segundo camino sería un agujero: bastaba registrarse por
-- email con un nombre ofensivo para saltarse la blocklist entera.
--
-- Devuelve NULL si el nombre es aceptable, o el CÓDIGO del motivo si no. Códigos,
-- no mensajes: el texto de UI vive en strings.xml (CLAUDE.md §10).
--
-- SECURITY DEFINER para poder leer `blocked_display_name_patterns`, que es
-- ilegible para cualquier cliente. NO se concede EXECUTE a nadie: sus dos
-- llamantes son DEFINER y se ejecutan como el dueño, así que no necesitan el
-- grant — y sin grant, nadie puede usarla como oráculo para ir tanteando qué
-- patrones tiene la lista.
create or replace function public.display_name_rejection(p_name text)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_clean text := btrim(coalesce(p_name, ''));
    v_key   text;
begin
    if char_length(v_clean) < 3  then return 'too_short'; end if;
    if char_length(v_clean) > 20 then return 'too_long';  end if;
    if v_clean !~ '^[A-Za-z0-9ÁÉÍÓÚÜÑáéíóúüñ_-]+( [A-Za-z0-9ÁÉÍÓÚÜÑáéíóúüñ_-]+)*$' then
        return 'invalid_chars';
    end if;

    v_key := public.normalize_display_name(v_clean);

    -- `whole_word` compara contra el nombre en crudo por palabra completa Y contra
    -- la clave entera; el resto, por subcadena de la clave. Ver §2.
    if exists (
        select 1
        from public.blocked_display_name_patterns b
        where case
                when b.whole_word then lower(v_clean) ~ ('\m' || b.pattern || '\M')
                                    or v_key          ~ ('^'  || b.pattern || '$')
                else v_key ~ b.pattern
              end
    ) then
        return 'blocked';
    end if;

    return null;
end;
$$;

comment on function public.display_name_rejection(text) is
    'Valida un display_name (longitud 3..20, forma y blocklist) y devuelve NULL si '
    'es aceptable o el código del motivo si no. Fuente única de las reglas: la usan '
    'set_display_name y handle_new_user. Sin EXECUTE para nadie a propósito: sus '
    'llamantes son SECURITY DEFINER y no lo necesitan.';

revoke all on function public.display_name_rejection(text) from public, anon, authenticated;


-- -----------------------------------------------------------------------------
-- 5) La RPC: único camino de escritura desde la app
-- -----------------------------------------------------------------------------
-- SECURITY DEFINER porque necesita el UPDATE sobre `users` que al llamante se le
-- acaba de revocar, y porque el validador que invoca lee una tabla sin policies.
-- No devuelve nunca filas ajenas ni la lista de patrones: solo un veredicto sobre
-- el nombre propio.
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

    update public.users set display_name = v_clean, updated_at = now() where id = v_uid;

    if not found then
        -- El perfil espejo no existe todavía (trigger `handle_new_user` con retraso
        -- justo tras un alta). El cliente reintenta; no se inserta aquí para no
        -- duplicar la lógica de alta.
        return jsonb_build_object('ok', false, 'reason', 'no_profile');
    end if;

    return jsonb_build_object('ok', true, 'display_name', v_clean);
end;
$$;

comment on function public.set_display_name(text) is
    'Fija el display_name del usuario de la sesión; valida con display_name_rejection. '
    'Único camino de escritura desde la app: authenticated no tiene UPDATE sobre '
    'public.users. Devuelve {ok, reason} con CÓDIGO de motivo, no mensaje.';

-- `anon` explícito además de `public`: Supabase concede EXECUTE a anon/authenticated
-- por DEFAULT PRIVILEGES al crear la función, y un `revoke ... from public` NO borra
-- esos grants nominales (ver 0004, 0027, 0028, 0045 y 0048).
revoke all     on function public.set_display_name(text) from public, anon;
grant  execute on function public.set_display_name(text) to authenticated;


-- -----------------------------------------------------------------------------
-- 6) El alta por email también valida
-- -----------------------------------------------------------------------------
-- El otro camino por el que un nombre llega a la columna. El formulario de alta
-- manda el nombre como metadato del registro y este trigger lo copia; sin este
-- cambio, registrarse por email con un nombre ofensivo se saltaba la blocklist
-- entera, porque el filtro solo vivía en `set_display_name`.
--
-- Un nombre rechazado NO aborta el alta: se guarda NULL y la app pide el nombre
-- en el onboarding, exactamente igual que en un alta con Google (que desde la
-- 0048 nace sin nombre). Abortar convertiría un nombre feo en "no puedo crearme
-- la cuenta", que es un problema peor y encima confuso.
--
-- Se conserva el resto del cuerpo de la 0048 sin cambios: `display_name` sigue
-- saliendo SOLO de `raw_user_meta_data.display_name` (nuestro formulario), nunca
-- del `full_name` que rellena el proveedor OAuth.
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

    v_display_name := nullif(trim(new.raw_user_meta_data->>'display_name'), '');

    -- Mismas reglas que la RPC. Si no pasa, la cuenta se crea sin nombre.
    if v_display_name is not null
       and public.display_name_rejection(v_display_name) is not null then
        v_display_name := null;
    end if;

    insert into public.users (id, email, display_name, auth_provider, last_login)
    values (new.id, new.email, v_display_name, v_provider, now())
    on conflict (id) do nothing;

    return new;
end;
$$;

comment on function public.handle_new_user() is
    'Crea el perfil espejo en public.users al registrarse. display_name se toma '
    'SOLO de raw_user_meta_data.display_name (formulario de alta propio) y pasa por '
    'display_name_rejection: si no supera las reglas se guarda NULL y la app lo pide '
    'en el onboarding (migraciones 0048 y 0049).';


-- -----------------------------------------------------------------------------
-- 7) Se cierra la escritura directa a `public.users`
-- -----------------------------------------------------------------------------
-- Esto es lo que convierte la validación en una garantía y no en una sugerencia.
-- Sin el revoke, la RPC es solo el camino EDUCADO: el PATCH directo a
-- /rest/v1/users seguiría aceptando cualquier cosa.
--
-- Se revoca el UPDATE ENTERO, no solo `display_name`, porque el mismo grant abría
-- agujeros peores: `plan_type`/`premium_until` deciden si el AdManager muestra
-- anuncios, así que cualquier usuario podía regalarse Premium —y quitarse la
-- publicidad— con la misma llamada. `streak_days` permitía inventarse la racha.
--
-- Verificado antes de revocar: el único UPDATE del cliente sobre esta tabla era
-- `AuthRepositoryImpl.updateDisplayName` (ahora vía RPC), y las dos funciones que
-- escriben en `public.users` (`handle_new_user`, alta) son SECURITY DEFINER de
-- `postgres`, así que no dependen de estos grants.
revoke update on public.users from authenticated;
revoke all    on public.users from anon;

-- El INSERT de `authenticated` (política `users_insert_own`) se mantiene como red
-- por si el trigger de alta fallara, pero sin poder fabricarse un plan: la fila
-- propia ya existe, así que en la práctica siempre choca contra la PK.
revoke insert (plan_type, premium_until) on public.users from authenticated;
