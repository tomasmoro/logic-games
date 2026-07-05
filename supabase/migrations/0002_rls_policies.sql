-- =============================================================================
-- FASE 2 (parte 1) — Row Level Security + alta automática de usuario
-- -----------------------------------------------------------------------------
-- Principio: como public.users.id == auth.users.id, toda comprobación de
-- pertenencia se reduce a `auth.uid() = user_id`.
-- Catálogos (categories, games, achievements, daily_challenges) → lectura
-- pública para usuarios autenticados; la escritura queda para el service_role
-- (que ignora RLS), es decir, sembrado/administración desde backend.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0) Alta automática del perfil al registrarse en Supabase Auth
--    Trigger sobre auth.users → inserta la fila espejo en public.users.
--    SECURITY DEFINER para poder escribir en public.users desde el esquema auth.
-- -----------------------------------------------------------------------------
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_provider auth_provider;
begin
    v_provider := case new.raw_app_meta_data->>'provider'
                    when 'google' then 'google'::auth_provider
                    when 'apple'  then 'apple'::auth_provider
                    else 'email'::auth_provider
                  end;

    insert into public.users (id, email, auth_provider, last_login)
    values (new.id, new.email, v_provider, now())
    on conflict (id) do nothing;

    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();


-- -----------------------------------------------------------------------------
-- 1) Activar RLS en todas las tablas de dominio
-- -----------------------------------------------------------------------------
alter table public.users             enable row level security;
alter table public.categories        enable row level security;
alter table public.games             enable row level security;
alter table public.user_progress     enable row level security;
alter table public.daily_challenges  enable row level security;
alter table public.achievements      enable row level security;
alter table public.user_achievements enable row level security;

-- La tabla particionada: aseguramos RLS también en cada partición para que el
-- acceso DIRECTO a una partición (PostgREST expone el esquema public) quede
-- denegado por defecto. El acceso legítimo siempre va por el padre.
alter table public.user_progress_2026_07 enable row level security;
alter table public.user_progress_2026_08 enable row level security;
alter table public.user_progress_2026_09 enable row level security;
alter table public.user_progress_default enable row level security;


-- -----------------------------------------------------------------------------
-- 2) USERS — cada quien ve/edita solo su fila
-- -----------------------------------------------------------------------------
create policy "users_select_own"
    on public.users for select
    using (auth.uid() = id);

create policy "users_update_own"
    on public.users for update
    using (auth.uid() = id)
    with check (auth.uid() = id);

-- Insert propio como red de seguridad (además del trigger de alta).
create policy "users_insert_own"
    on public.users for insert
    with check (auth.uid() = id);


-- -----------------------------------------------------------------------------
-- 3) CATÁLOGOS — solo lectura para usuarios autenticados
--    (escritura: service_role, que bypassa RLS)
-- -----------------------------------------------------------------------------
create policy "categories_read_all"
    on public.categories for select
    to authenticated
    using (true);

create policy "games_read_all"
    on public.games for select
    to authenticated
    using (true);

create policy "achievements_read_all"
    on public.achievements for select
    to authenticated
    using (true);

create policy "daily_challenges_read_all"
    on public.daily_challenges for select
    to authenticated
    using (true);


-- -----------------------------------------------------------------------------
-- 4) USER_PROGRESS — historial propio, inmutable
--    Lectura y alta solo de filas propias. Sin UPDATE/DELETE: el historial no
--    se reescribe (integridad de estadísticas/percentiles).
-- -----------------------------------------------------------------------------
create policy "user_progress_select_own"
    on public.user_progress for select
    using (auth.uid() = user_id);

create policy "user_progress_insert_own"
    on public.user_progress for insert
    with check (auth.uid() = user_id);


-- -----------------------------------------------------------------------------
-- 5) USER_ACHIEVEMENTS — logros propios
--    Insert/update propios para poder registrar progreso y desbloqueo.
-- -----------------------------------------------------------------------------
create policy "user_achievements_select_own"
    on public.user_achievements for select
    using (auth.uid() = user_id);

create policy "user_achievements_insert_own"
    on public.user_achievements for insert
    with check (auth.uid() = user_id);

create policy "user_achievements_update_own"
    on public.user_achievements for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);
