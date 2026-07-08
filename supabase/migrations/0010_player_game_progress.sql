-- =============================================================================
-- FASE 5 (progresión) — Récord y punto de reanudación por juego
-- -----------------------------------------------------------------------------
-- Complementa a user_progress (LOG inmutable de partidas) con el ESTADO agregado
-- por juego y usuario: su mejor marca y dónde retomar. Una fila por (usuario,
-- juego) — a diferencia de user_progress, aquí SÍ se reescribe (UPDATE).
--
-- ¿Por qué una tabla y no derivarlo del historial?  Se eligió (frente al mínimo
-- viable) para que el récord y la reanudación SIGAN A LA CUENTA entre dispositivos
-- con una fila pequeña y barata de sincronizar, sin recorrer todo el historial.
--
-- Semántica de best_metric: es la mejor marca en la UNIDAD NATURAL de cada juego
-- (longitud de secuencia en Memoria, ms de reacción en Reflejos, nivel en juegos
-- LEVELED). NO se normaliza: se guarda el valor crudo (normalizarlo sería con
-- pérdida, p. ej. para el tiempo de Reflejos). La app conoce la dirección real de
-- cada juego (MetricDirection: mayor o menor es mejor) y sube la marca ya resuelta
-- como "la mejor". Por eso el servidor NO interpreta el número ni calcula el
-- máximo: la fusión (tomar la mejor marca) la resuelve el cliente, coherente con
-- la arquitectura local-first. Así evitamos un RPC con lógica de juego en SQL.
-- =============================================================================

create table public.player_game_progress (
    user_id     uuid        not null
                  references public.users (id) on delete cascade,
    game_id     uuid        not null
                  references public.games (id) on delete restrict,
    -- Mejor marca del jugador en la unidad natural del juego (valor crudo, sin
    -- normalizar). Sin default: solo existe fila cuando hay una marca real.
    best_metric integer     not null check (best_metric >= 0),
    -- Nivel donde retomar la próxima vez. NULL en juegos ENDLESS (Memoria,
    -- Reflejos): no se "reanudan", cada corrida empieza de cero.
    last_level  integer     check (last_level is null or last_level >= 1),
    -- Momento de la marca (lo fija el cliente = cuándo se jugó). Sirve para
    -- resolver "qué last_level gana" al fusionar entre dispositivos: el más
    -- reciente. Para best_metric no hace falta (gana la mejor, no la más nueva).
    updated_at  timestamptz not null default now(),

    -- Una sola fila por juego y usuario → el upsert desde la app es idempotente.
    primary key (user_id, game_id)
);

comment on table public.player_game_progress is
    'Estado de progresión por juego y usuario (récord + reanudación). Reescribible (UPDATE), sincronizado entre dispositivos. Distinto de user_progress (log inmutable).';
comment on column public.player_game_progress.best_metric is
    'Mejor marca en la unidad natural del juego (longitud de secuencia en Memoria, ms de reacción en Reflejos, nivel en juegos LEVELED). El cliente conoce si mayor o menor es mejor (MetricDirection) y sube la marca ya resuelta; el servidor no calcula el máximo.';
comment on column public.player_game_progress.last_level is
    'Nivel de reanudación (juegos LEVELED). NULL en juegos ENDLESS.';

-- -----------------------------------------------------------------------------
-- RLS — mismo principio que el resto: cada quien ve/edita SOLO lo suyo.
-- A diferencia de user_progress (inmutable), aquí sí permitimos UPDATE (récord
-- que sube) y DELETE (por si el usuario reinicia su progreso).
-- -----------------------------------------------------------------------------
alter table public.player_game_progress enable row level security;

create policy "player_game_progress_select_own"
    on public.player_game_progress for select
    using (auth.uid() = user_id);

create policy "player_game_progress_insert_own"
    on public.player_game_progress for insert
    with check (auth.uid() = user_id);

create policy "player_game_progress_update_own"
    on public.player_game_progress for update
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

create policy "player_game_progress_delete_own"
    on public.player_game_progress for delete
    using (auth.uid() = user_id);
