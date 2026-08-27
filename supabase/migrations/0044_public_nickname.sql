-- =============================================================================
-- 0044 — Nickname público: la identidad que SÍ se enseña a otros jugadores
-- -----------------------------------------------------------------------------
-- PROBLEMA QUE RESUELVE (y es un problema de privacidad, no de estética)
-- `handle_new_user` (0026) rellena `display_name` con el `full_name` que envía
-- Google, sin que el jugador lo elija ni lo confirme. Y `get_game_ranking`
-- (0027/0028/0029) publica ese `display_name` a cualquier usuario autenticado.
-- Desde que existe el panel de ranking en la ANTESALA de los juegos, eso ya no se
-- ve solo al terminar una partida: se ve antes de cada partida, en una docena de
-- juegos. Resultado: el nombre y apellidos reales de quien entra con Google
-- —posiblemente un menor— se exhiben en una tabla mundial.
--
-- El comentario de 0027 justificaba publicarlo diciendo que `display_name` «es dato
-- que el propio usuario elige mostrar (Ajustes → Nombre)». Eso dejó de ser cierto
-- en 0026 y nadie revisó la consecuencia.
--
-- SOLUCIÓN
-- Un `nickname` explícito, que el jugador elige a propósito sabiendo que es público.
-- `display_name` NO se toca ni se borra: pasa a ser un dato PRIVADO (el saludo de la
-- app, la ficha de Ajustes) y deja de publicarse. La migración 0045 es la que cambia
-- el ranking para leer `nickname`.
--
-- POR QUÉ COLUMNAS EN `public.users` Y NO UNA TABLA DE PERFIL PÚBLICO
-- La objeción evidente es que RLS es a nivel de FILA, no de columna: la policy de
-- `users` es «solo mi fila», así que un cliente no puede leer el nickname ajeno sin
-- poder leer también el email. Pero el consumidor de esto —`get_game_ranking`— es
-- SECURITY DEFINER: lee la tabla saltándose RLS y proyecta únicamente lo que decide
-- devolver. No necesita ninguna policy de lectura pública, así que la tabla separada
-- no compraría nada hoy y sí añadiría un join y una entidad más.
-- Cuando exista un lobby y un cliente tenga que leer nicknames AJENOS directamente,
-- se resolverá con una RPC SECURITY DEFINER que devuelva solo (nickname, avatar_url)
-- —el mismo patrón— o con una vista de proyección. No obliga a mover datos.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Clave canónica anti-suplantación
-- -----------------------------------------------------------------------------
-- IMMUTABLE por dos motivos: lo EXIGE la columna generada de más abajo, y porque su
-- resultado no puede depender de configuración regional — si `lower()` cambiara de
-- comportamiento entre locales, dos claves que hoy colisionan dejarían de hacerlo y
-- el UNIQUE se volvería una mentira silenciosa.
--
-- NO usa `unaccent()` a propósito: esa función NO es IMMUTABLE (depende de un
-- diccionario que se puede alterar en caliente) y Postgres la rechaza en una columna
-- generada. El plegado se hace a mano con translate(), que sí lo es.
create or replace function public.normalize_nickname(p_text text)
returns text
language sql
immutable
strict
set search_path = ''
as $$
    -- 1) fuera separadores decorativos: "k_o_r_t_e_x" colapsa en "kortex".
    -- 2) minúsculas.
    -- 3) plegado de homoglifos: dígitos usados como letras (leet) y cirílicos que se
    --    dibujan igual que su latina. Así "K0RTEX", "kortex" y "Кortex" (К cirílica)
    --    producen la MISMA clave y el UNIQUE impide registrar un clon visual de un
    --    nombre ya tomado, que es la forma más barata de suplantar a alguien.
    select translate(
        translate(lower(p_text), '_-.', ''),
        '0134579áàäâãéèëêíìïîóòöôõúùüûñçкаеорсхувмтн',
        'oieastgaaaaaeeeeiiiiooooouuuunckaeopcxybmth'
    );
$$;

comment on function public.normalize_nickname(text) is
    'Clave canónica de un nickname: sin separadores, en minúsculas y con homoglifos '
    '(leet + cirílicos) plegados. IMMUTABLE porque la consume una columna generada. '
    'Es la base del UNIQUE anti-suplantación de public.users.nickname_key.';

revoke all on function public.normalize_nickname(text) from public, anon;
grant execute on function public.normalize_nickname(text) to authenticated;


-- -----------------------------------------------------------------------------
-- Columnas de identidad pública sobre la tabla que ya existe
-- -----------------------------------------------------------------------------
alter table public.users
    add column if not exists nickname            text,
    -- Cuándo se cambió por última vez. Alimenta el cooldown de renombrado: sin él, el
    -- griefing consiste en cambiarse al nombre del rival justo antes de una final.
    add column if not exists nickname_changed_at timestamptz;

-- Columna GENERADA y almacenada (no un índice funcional) para que la RPC de
-- disponibilidad pueda consultarla sin recalcular, y para que el error de unicidad
-- señale una columna con nombre legible en vez de una expresión.
alter table public.users
    add column if not exists nickname_key text
    generated always as (public.normalize_nickname(nickname)) stored;

-- Forma y longitud. Se valida aquí además de en la RPC: el CHECK es la red que no se
-- puede saltar con un PATCH directo a PostgREST, la RPC es la que da el mensaje
-- bonito. Admite NULL porque el nickname es opcional hasta que el jugador lo elige
-- (todas las filas existentes lo tienen a NULL, así que el CHECK valida al instante).
alter table public.users
    drop constraint if exists users_nickname_shape;
alter table public.users
    add constraint users_nickname_shape check (
        nickname is null
        or (char_length(nickname) between 3 and 16
            and nickname ~ '^[A-Za-z0-9ÁÉÍÓÚÜÑáéíóúüñ_-]+$')
    );

-- En Postgres los NULL no colisionan entre sí en un índice único, así que esto impone
-- unicidad a quien tiene nickname sin estorbar a los miles que todavía no lo tienen.
create unique index if not exists users_nickname_key_unique
    on public.users (nickname_key);

comment on column public.users.nickname is
    'Identidad PÚBLICA elegida por el jugador: es lo único que ven los demás en el '
    'ranking. display_name queda como dato privado y ya no se publica (ver 0045).';
comment on column public.users.nickname_key is
    'Clave canónica generada (homoglifos y leet plegados). El UNIQUE va aquí, no en '
    'nickname, para que "K0RTEX" no pueda coexistir con "Kortex".';


-- -----------------------------------------------------------------------------
-- Lista de patrones prohibidos
-- -----------------------------------------------------------------------------
-- TABLA y no un CHECK, por dos razones: (1) se amplía con un INSERT cuando aparece
-- una palabra nueva, sin migración ni despliegue; (2) el cliente NUNCA debe leerla —
-- publicar la lista de lo prohibido es a la vez un manual de evasión y un problema de
-- imagen. Por eso queda con RLS activo y CERO policies: deny-by-default absoluto para
-- anon y authenticated; solo la alcanzan las funciones SECURITY DEFINER de abajo.
create table if not exists public.blocked_nickname_patterns (
    id         smallint generated always as identity primary key,
    -- Regex evaluada contra `nickname_key`, que ya viene normalizado: así el patrón
    -- de una palabra base atrapa también sus variantes en leet sin enumerarlas.
    pattern    text not null,
    reason     text not null default 'ofensivo',
    created_at timestamptz not null default now(),
    constraint blocked_nickname_patterns_pattern_unique unique (pattern)
);

alter table public.blocked_nickname_patterns enable row level security;
-- Sin policies: es deliberado, no un olvido.

comment on table public.blocked_nickname_patterns is
    'Patrones de nickname prohibidos (ofensivos y nombres reservados). RLS activo y '
    'SIN policies: ilegible por cualquier cliente; solo la leen las RPC DEFINER.';

-- Semilla mínima: nombres reservados que permitirían hacerse pasar por la marca o por
-- personal de soporte —el vector de suplantación más rentable— y unas pocas raíces
-- ofensivas. La lista se amplía en caliente; no pretende ser exhaustiva.
insert into public.blocked_nickname_patterns (pattern, reason) values
    ('^kortex',        'reservado'),
    ('^admin',         'reservado'),
    ('^moderador',     'reservado'),
    ('^soporte',       'reservado'),
    ('^support',       'reservado'),
    ('^staff',         'reservado'),
    ('^oficial',       'reservado'),
    ('^official',      'reservado'),
    ('^sistema',       'reservado'),
    ('puta',           'ofensivo'),
    ('mierda',         'ofensivo'),
    ('polla',          'ofensivo'),
    ('pene',           'ofensivo'),
    ('coño',           'ofensivo'),
    ('joder',          'ofensivo'),
    ('fuck',           'ofensivo'),
    ('shit',           'ofensivo'),
    ('bitch',          'ofensivo'),
    ('nazi',           'ofensivo'),
    ('hitler',         'ofensivo')
on conflict (pattern) do nothing;


-- -----------------------------------------------------------------------------
-- RPC: ¿está libre este nickname?
-- -----------------------------------------------------------------------------
-- DEFINER porque necesita leer `blocked_nickname_patterns` (ilegible para el cliente)
-- y comprobar unicidad global contra filas de `users` que RLS le oculta al llamante.
--
-- Devuelve un veredicto, JAMÁS la fila que colisiona: mismo criterio que
-- `get_score_percentile` y `get_game_ranking`, que leen de todos y solo exponen un
-- agregado. No es un oráculo de enumeración preocupante —los nicknames son públicos
-- por definición, se ven en el ranking— pero tampoco hace falta revelar de quién es.
create or replace function public.check_nickname_available(p_nickname text)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_key text;
begin
    if p_nickname is null or char_length(trim(p_nickname)) = 0 then
        return jsonb_build_object('available', false, 'reason', 'empty');
    end if;

    if char_length(p_nickname) < 3 or char_length(p_nickname) > 16
       or p_nickname !~ '^[A-Za-z0-9ÁÉÍÓÚÜÑáéíóúüñ_-]+$' then
        return jsonb_build_object('available', false, 'reason', 'invalid');
    end if;

    v_key := public.normalize_nickname(p_nickname);

    -- Un nombre que se queda sin nada tras normalizar ("___", "---") no es un nombre.
    if v_key is null or char_length(v_key) = 0 then
        return jsonb_build_object('available', false, 'reason', 'invalid');
    end if;

    if exists (select 1 from public.blocked_nickname_patterns b where v_key ~ b.pattern) then
        return jsonb_build_object('available', false, 'reason', 'blocked');
    end if;

    -- El propio nickname del usuario cuenta como disponible: así la pantalla de
    -- edición no marca en rojo lo que ya es suyo.
    if exists (
        select 1 from public.users u
        where u.nickname_key = v_key and u.id <> auth.uid()
    ) then
        return jsonb_build_object('available', false, 'reason', 'taken');
    end if;

    return jsonb_build_object('available', true, 'reason', 'ok');
end;
$$;

comment on function public.check_nickname_available(text) is
    'Veredicto de disponibilidad de un nickname (available + reason). SECURITY '
    'DEFINER: consulta la blocklist y los nicknames de todos, pero nunca revela de '
    'quién es el que colisiona.';

revoke all     on function public.check_nickname_available(text) from public, anon;
grant  execute on function public.check_nickname_available(text) to authenticated;


-- -----------------------------------------------------------------------------
-- RPC: reclamar (o cambiar) el nickname
-- -----------------------------------------------------------------------------
-- DEFINER por lo mismo que la anterior, y además porque es la ÚNICA puerta de
-- escritura: `users_update_own` permitiría un PATCH directo a PostgREST que se
-- saltaría la blocklist y el cooldown. La validación no puede vivir solo en el
-- cliente ni solo en una policy.
create or replace function public.claim_nickname(p_nickname text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid      uuid := auth.uid();
    v_check    jsonb;
    v_current  text;
    v_changed  timestamptz;
    v_clean    text := trim(p_nickname);
begin
    if v_uid is null then
        raise exception 'No autenticado' using errcode = '28000';
    end if;

    v_check := public.check_nickname_available(v_clean);
    if not (v_check->>'available')::boolean then
        return jsonb_build_object('ok', false, 'reason', v_check->>'reason');
    end if;

    select u.nickname, u.nickname_changed_at
      into v_current, v_changed
      from public.users u
     where u.id = v_uid;

    -- Cooldown de 30 días entre cambios, en el mismo orden de magnitud que Discord o
    -- Steam. No aplica a la primera vez: estrenar nickname nunca debe hacer esperar.
    -- Re-guardar el mismo nombre es un no-op y tampoco consume cooldown.
    if v_current is not null and public.normalize_nickname(v_current) is distinct from public.normalize_nickname(v_clean) then
        if v_changed is not null and v_changed > now() - interval '30 days' then
            return jsonb_build_object(
                'ok', false,
                'reason', 'cooldown',
                'retry_after', v_changed + interval '30 days'
            );
        end if;
    end if;

    update public.users
       set nickname            = v_clean,
           nickname_changed_at = now(),
           updated_at          = now()
     where id = v_uid;

    return jsonb_build_object('ok', true, 'nickname', v_clean);

exception
    -- Carrera real: dos usuarios reclamando la misma clave a la vez. El CHECK previo
    -- no puede evitarla (no hay bloqueo entre el select y el update), así que el
    -- índice único es el árbitro y aquí solo se traduce a un veredicto legible.
    when unique_violation then
        return jsonb_build_object('ok', false, 'reason', 'taken');
end;
$$;

comment on function public.claim_nickname(text) is
    'Reclama o cambia el nickname del usuario autenticado. Única vía de escritura: '
    'valida forma, blocklist, unicidad y cooldown de 30 días, cosas que un PATCH '
    'directo a PostgREST se saltaría.';

revoke all     on function public.claim_nickname(text) from public, anon;
grant  execute on function public.claim_nickname(text) to authenticated;
