-- =============================================================================
-- 0046 — `check_nickname_available` deja de mentir cuando no hay sesión
-- -----------------------------------------------------------------------------
-- QUÉ ESTABA MAL
-- La comprobación de "¿lo tiene ya otro?" se escribió así:
--
--     where u.nickname_key = v_key and u.id <> auth.uid()
--
-- La intención era excluir al propio usuario, para que la pantalla de edición no
-- marque en rojo el nombre que YA es suyo. Pero con `auth.uid()` a NULL, la
-- comparación `u.id <> NULL` no es `true` ni `false`: es NULL. Ninguna fila la
-- satisface, el EXISTS da false y la función responde «disponible» sobre un nombre
-- que está cogido.
--
-- IMPACTO REAL: NINGUNO para los jugadores. La función solo tiene `execute`
-- concedido a `authenticated` (`anon` está revocado), así que `auth.uid()` nunca es
-- NULL en la ruta real; y `claim_nickname` la invoca DESPUÉS de comprobar la sesión.
-- Además el índice único seguía siendo el árbitro final, así que ni siquiera en el
-- peor caso se podían duplicar dos nicknames.
--
-- POR QUÉ SE ARREGLA IGUAL
-- Porque una función cuya corrección depende de quién la llame es una trampa para el
-- siguiente que la use (un job, un test, una Edge Function con service_role), y
-- porque tal como estaba **no se podía verificar**: cualquier prueba desde el MCP o
-- desde psql daba un falso «disponible» y parecía que la lógica no funcionaba.
--
-- `is distinct from` es el operador que compara tratando NULL como un valor más:
-- con sesión se comporta igual que `<>` (excluye al propio usuario) y sin sesión
-- devuelve true para toda fila real, que es justo lo que se quiere — sin sesión no
-- hay "propio usuario" al que excluir.
-- =============================================================================

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

    -- `is distinct from` y no `<>`: ver la cabecera. Con sesión excluye al propio
    -- usuario; sin sesión no excluye a nadie, en vez de anular la comprobación entera.
    if exists (
        select 1 from public.users u
        where u.nickname_key = v_key and u.id is distinct from auth.uid()
    ) then
        return jsonb_build_object('available', false, 'reason', 'taken');
    end if;

    return jsonb_build_object('available', true, 'reason', 'ok');
end;
$$;

revoke all     on function public.check_nickname_available(text) from public, anon;
grant  execute on function public.check_nickname_available(text) to authenticated;
