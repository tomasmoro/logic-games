-- =============================================================================
-- FASE 5 (progresión) — Mejor tiempo por nivel
-- -----------------------------------------------------------------------------
-- Complementa a player_game_progress (una fila por juego: récord de nivel máx) con
-- el detalle POR NIVEL: el mejor tiempo del jugador en cada nivel (menor = mejor).
-- Una fila por (usuario, juego, nivel) — reescribible (UPDATE) como el récord.
--
-- ¿Por qué una tabla aparte y no una columna en player_game_progress?  Porque el
-- tiempo es POR NIVEL (N filas por juego), no un agregado único: no cabe en la fila
-- de récord (que guarda un solo best_metric). La alimentan solo los juegos con la
-- stat activada en el cliente (GameProgression.tracksLevelTime); hoy, Flujo de Energía.
--
-- Semántica de best_time_ms: tiempo ACTIVO de la partida en milisegundos (excluye
-- pausas, igual que user_progress.completion_time_ms). Menor es mejor. Como en
-- player_game_progress, el servidor NO interpreta la marca: el cliente sube ya el
-- mínimo y resuelve la fusión (tomar el menor) en local, coherente con local-first.
-- =============================================================================

create table public.player_level_time (
    user_id      uuid        not null
                   references public.users (id) on delete cascade,
    game_id      uuid        not null
                   references public.games (id) on delete restrict,
    -- Nivel (1-based) al que corresponde el tiempo.
    level        integer     not null check (level >= 1),
    -- Mejor tiempo activo del nivel en ms (excluye pausas). Menor gana; el cliente
    -- sube ya el mínimo, el servidor no calcula nada.
    best_time_ms bigint      not null check (best_time_ms >= 0),
    -- Momento de la marca (lo fija el cliente). Desempata al fusionar entre dispositivos.
    updated_at   timestamptz not null default now(),

    -- Una fila por juego, nivel y usuario → el upsert desde la app es idempotente.
    primary key (user_id, game_id, level)
);

comment on table public.player_level_time is
    'Mejor tiempo por nivel y usuario (menor = mejor). Complementa a player_game_progress (récord de nivel máx) con el detalle por nivel. Reescribible, sincronizado entre dispositivos. La alimentan los juegos con tracksLevelTime (hoy Flujo de Energía).';
comment on column public.player_level_time.best_time_ms is
    'Tiempo activo de la partida en ms (excluye pausas). Menor es mejor; el cliente sube el mínimo, el servidor no lo interpreta.';

-- -----------------------------------------------------------------------------
-- RLS — mismo principio que player_game_progress: cada quien ve/edita SOLO lo suyo,
-- con UPDATE (marca que mejora) y DELETE (por si el usuario reinicia su progreso).
-- -----------------------------------------------------------------------------
alter table public.player_level_time enable row level security;

create policy "player_level_time_select_own"
    on public.player_level_time for select
    using (auth.uid() = user_id);

create policy "player_level_time_insert_own"
    on public.player_level_time for insert
    with check (auth.uid() = user_id);

create policy "player_level_time_update_own"
    on public.player_level_time for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

create policy "player_level_time_delete_own"
    on public.player_level_time for delete
    using (auth.uid() = user_id);
