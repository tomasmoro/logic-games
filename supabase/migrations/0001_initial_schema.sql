-- =============================================================================
-- LOGIC GAMES · FASE 1 — Esquema relacional (PostgreSQL / Supabase)
-- Objetivo: 3FN, alta concurrencia, tabla de historial particionada.
-- Convenciones:
--   * PK: uuid v4 (gen_random_uuid) salvo catálogos estables que usan slug/serial.
--   * timestamps: timestamptz (siempre UTC).
--   * FKs con ON DELETE explícito.
--   * snake_case, plural en tablas.
-- =============================================================================

-- Extensiones necesarias -------------------------------------------------------
create extension if not exists "pgcrypto";      -- gen_random_uuid()
create extension if not exists "citext";         -- email case-insensitive

-- Tipos enumerados (ENUM) para integridad y ahorro de espacio -----------------
-- Nota: usar ENUM en lugar de CHECK cuando el dominio es cerrado y estable.
do $$ begin
  create type auth_provider as enum ('email', 'google', 'apple', 'guest');
exception when duplicate_object then null; end $$;

do $$ begin
  create type plan_type as enum ('free', 'premium');
exception when duplicate_object then null; end $$;

do $$ begin
  -- Alineado con las 11 categorías cognitivas del producto.
  create type game_type as enum (
    'memory', 'logic', 'problem_solving', 'reflexes', 'mental_speed',
    'attention', 'spatial', 'calculation', 'language', 'flexibility', 'pattern'
  );
exception when duplicate_object then null; end $$;

do $$ begin
  create type achievement_condition as enum (
    'games_played', 'total_score', 'streak_days',
    'category_mastery', 'perfect_accuracy', 'daily_goal_completed'
  );
exception when duplicate_object then null; end $$;


-- =============================================================================
-- 1) USERS  (perfil público enlazado 1:1 con auth.users de Supabase)
-- -----------------------------------------------------------------------------
-- IMPORTANTE: Supabase ya gestiona credenciales/OAuth en el esquema `auth`.
-- Esta tabla NO almacena contraseñas: es el perfil de dominio que referencia
-- auth.users(id). El campo `id` es a la vez PK y FK para forzar la relación 1:1.
-- =============================================================================
create table public.users (
    id             uuid primary key
                     references auth.users (id) on delete cascade,
    email          citext not null,                 -- case-insensitive, único
    display_name   text,
    avatar_url     text,
    auth_provider  auth_provider not null default 'email',
    plan_type      plan_type     not null default 'free',
    premium_until  timestamptz,                      -- null = sin suscripción vigente
    streak_days    integer       not null default 0 check (streak_days >= 0),
    last_login     timestamptz,
    created_at     timestamptz   not null default now(),
    updated_at     timestamptz   not null default now(),

    constraint users_email_unique unique (email)
);

comment on table  public.users is 'Perfil de dominio 1:1 con auth.users. Sin credenciales.';
comment on column public.users.premium_until is 'Fecha de expiración del premium; plan_type se deriva/valida contra esto.';


-- =============================================================================
-- 2) CATEGORIES  (catálogo de las 11 categorías cognitivas)
-- =============================================================================
create table public.categories (
    id           smallint generated always as identity primary key,
    slug         text        not null,               -- 'memory', 'logic', ...
    name         text        not null,
    description  text,
    color_hex    char(7)     not null default '#7C4DFF'
                   check (color_hex ~ '^#[0-9A-Fa-f]{6}$'),
    icon_key     text,                                -- clave de icono en la app
    sort_order   smallint    not null default 0,
    created_at   timestamptz not null default now(),

    constraint categories_slug_unique unique (slug),
    constraint categories_name_unique unique (name)
);

comment on column public.categories.color_hex is 'Color primario en formato #RRGGBB (validado por CHECK).';


-- =============================================================================
-- 3) GAMES  (catálogo de los 30 minijuegos)
-- =============================================================================
create table public.games (
    id                uuid primary key default gen_random_uuid(),
    category_id       smallint    not null
                        references public.categories (id) on delete restrict,
    slug              text        not null,           -- 'sequence_memory', ...
    name              text        not null,
    description       text,
    game_type         game_type   not null,
    difficulty_level  smallint    not null default 1
                        check (difficulty_level between 1 and 5),
    -- Config declarativa del motor (nº rondas, tiempos, etc.) sin crear tablas
    -- por juego. JSONB permite evolucionar sin migraciones y sigue en 3FN
    -- porque es un atributo del propio juego, no datos de otra entidad.
    engine_config     jsonb       not null default '{}'::jsonb,
    is_active         boolean     not null default true,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),

    constraint games_slug_unique unique (slug)
);

-- Listar juegos por categoría (pantalla de categoría) es la query más frecuente.
create index games_category_id_idx on public.games (category_id) where is_active;

comment on column public.games.engine_config is 'Parámetros del GameEngine (rondas, ms por turno...). Atributo del juego → 3FN.';


-- =============================================================================
-- 4) USER_PROGRESS  (historial de partidas — TABLA DE ALTO VOLUMEN)
-- -----------------------------------------------------------------------------
-- Estrategia de escala:
--   * PARTICIONADO por RANGO sobre created_at (mensual). Mantiene índices
--     pequeños, permite purga/archivado por DROP PARTITION y acelera queries
--     por rango temporal (gráficas de progreso).
--   * La PK incluye created_at porque en tablas particionadas la clave primaria
--     debe contener la columna de partición.
--   * Índice (game_id, score) para el cálculo de percentiles de FASE 2.
-- =============================================================================
create table public.user_progress (
    id                   uuid        not null default gen_random_uuid(),
    user_id              uuid        not null
                           references public.users (id) on delete cascade,
    game_id              uuid        not null
                           references public.games (id) on delete restrict,
    daily_challenge_id   uuid,                        -- FK definida más abajo
    score                integer     not null check (score >= 0),
    completion_time_ms   integer     not null check (completion_time_ms >= 0),
    accuracy_percentage  numeric(5,2) not null
                           check (accuracy_percentage between 0 and 100),
    difficulty_level     smallint    not null default 1
                           check (difficulty_level between 1 and 5),
    created_at           timestamptz not null default now(),

    primary key (id, created_at)
) partition by range (created_at);

-- Índices sobre la tabla padre → heredados por cada partición ------------------
-- 1) Perfil/gráficas del usuario en orden temporal.
create index user_progress_user_created_idx
    on public.user_progress (user_id, created_at desc);

-- 2) Percentiles: recorrer todos los scores de un juego (FASE 2 RPC).
create index user_progress_game_score_idx
    on public.user_progress (game_id, score);

-- 3) Leaderboard de desafío diario.
create index user_progress_challenge_idx
    on public.user_progress (daily_challenge_id, score desc)
    where daily_challenge_id is not null;

-- Particiones iniciales (crear el resto vía cron/pg_partman en producción) -----
create table public.user_progress_2026_07 partition of public.user_progress
    for values from ('2026-07-01') to ('2026-08-01');
create table public.user_progress_2026_08 partition of public.user_progress
    for values from ('2026-08-01') to ('2026-09-01');
create table public.user_progress_2026_09 partition of public.user_progress
    for values from ('2026-09-01') to ('2026-10-01');
-- Partición "colchón" para inserts fuera de rango previsto (evita errores).
create table public.user_progress_default partition of public.user_progress default;

comment on table public.user_progress is 'Historial de partidas. Particionada por mes (created_at) para escala y purga.';


-- =============================================================================
-- 5) DAILY_CHALLENGES  (un desafío por día y por juego)
-- =============================================================================
create table public.daily_challenges (
    id             uuid        primary key default gen_random_uuid(),
    challenge_date date        not null,
    game_id        uuid        not null
                     references public.games (id) on delete restrict,
    target_score   integer     not null check (target_score >= 0),
    seed           bigint,                             -- semilla determinista del juego
    created_at     timestamptz not null default now(),

    -- Un único desafío por (fecha, juego). Si quieres 1 solo desafío global por
    -- día, cambia el UNIQUE a (challenge_date) únicamente.
    constraint daily_challenges_date_game_unique unique (challenge_date, game_id)
);

create index daily_challenges_date_idx on public.daily_challenges (challenge_date desc);

-- FK diferida de user_progress → daily_challenges (declarada aquí por orden).
alter table public.user_progress
    add constraint user_progress_daily_challenge_fk
    foreign key (daily_challenge_id)
    references public.daily_challenges (id) on delete set null;


-- =============================================================================
-- 6) ACHIEVEMENTS  (catálogo de logros)
-- =============================================================================
create table public.achievements (
    id              uuid        primary key default gen_random_uuid(),
    slug            text        not null,
    name            text        not null,
    description     text        not null,
    icon_key        text,
    -- Regla desbloqueo normalizada: tipo de condición + umbral + ámbito opcional.
    condition_type  achievement_condition not null,
    threshold       integer     not null check (threshold > 0),
    category_id     smallint    references public.categories (id) on delete set null,
    reward_points   integer     not null default 0 check (reward_points >= 0),
    is_active       boolean     not null default true,
    created_at      timestamptz not null default now(),

    constraint achievements_slug_unique unique (slug)
);

comment on column public.achievements.category_id is 'Ámbito opcional: logro ligado a una categoría (ej. maestría en Memoria).';


-- =============================================================================
-- 7) USER_ACHIEVEMENTS  (N:M usuario ↔ logro desbloqueado)
-- =============================================================================
create table public.user_achievements (
    user_id         uuid        not null
                      references public.users (id) on delete cascade,
    achievement_id  uuid        not null
                      references public.achievements (id) on delete cascade,
    unlocked_at     timestamptz not null default now(),
    -- Progreso hacia el logro (para barras "7/10"); al llegar al umbral se marca.
    progress        integer     not null default 0 check (progress >= 0),

    primary key (user_id, achievement_id)
);

-- "Mis logros" ordenados por desbloqueo reciente.
create index user_achievements_user_idx
    on public.user_achievements (user_id, unlocked_at desc);


-- =============================================================================
-- TRIGGER util: mantener updated_at (users, games)
-- =============================================================================
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

create trigger users_set_updated_at
    before update on public.users
    for each row execute function public.set_updated_at();

create trigger games_set_updated_at
    before update on public.games
    for each row execute function public.set_updated_at();
