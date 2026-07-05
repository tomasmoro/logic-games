-- =============================================================================
-- FASE 2 (parte 4) — Endurecimiento de seguridad (según advisors de Supabase)
-- =============================================================================

-- Fijar search_path en la función de trigger (evita hijack de search_path).
create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

-- handle_new_user es función de TRIGGER: no debe exponerse como RPC.
revoke all on function public.handle_new_user() from public, anon, authenticated;

-- El percentil solo para usuarios autenticados (nunca anónimos).
revoke all on function public.get_score_percentile(uuid, integer) from public, anon;
grant execute on function public.get_score_percentile(uuid, integer) to authenticated;

-- submit_game_result: idem, solo autenticados.
revoke all on function public.submit_game_result(uuid, integer, integer, numeric, smallint, uuid) from public, anon;
grant execute on function public.submit_game_result(uuid, integer, integer, numeric, smallint, uuid) to authenticated;

-- Eliminar extensión sin uso del schema public.
drop extension if exists pg_trgm;
