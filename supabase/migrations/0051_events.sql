-- =============================================================================
-- 0051 — EVENTOS (torneos programados de un juego concreto)
-- -----------------------------------------------------------------------------
-- QUÉ AÑADE
-- El esqueleto de datos de la Fase 7: un "evento" es un torneo acotado en el
-- tiempo sobre UN juego del catálogo (p. ej. "Sudoku del sábado", de las 00:00 a
-- las 23:59 del 4/10). Dos tablas:
--   * `events`        — el calendario (qué, cuándo, con qué reglas).
--   * `event_entries` — la marca de cada jugador en ese evento (el leaderboard).
--
-- POR QUÉ NO SE REUTILIZA `daily_challenges` (0001)
-- Aquella tabla modela "un reto por DÍA y por juego": su clave es una `date` y su
-- UNIQUE (challenge_date, game_id) impide dos eventos del mismo juego el mismo
-- día. Un torneo necesita una VENTANA (`starts_at`/`ends_at`, que puede durar
-- horas o cruzar la medianoche), nombre visible, regla de puntuación, tope de
-- intentos y un estado publicado/borrador. Estirar `daily_challenges` exigiría
-- tocar una migración ya aplicada (prohibido, CLAUDE.md §8) y dejaría una tabla
-- que no es ni una cosa ni la otra. `daily_challenges` se queda donde está (hoy
-- con 0 filas) para el "desafío diario" propiamente dicho.
--
-- POR QUÉ `event_entries` MATERIALIZA LA MEJOR MARCA
-- Se podría calcular el leaderboard agregando `user_progress` al vuelo, que es lo
-- que hace `get_game_ranking` (0027). Aquí no vale por dos razones:
--   1. FRECUENCIA. El ranking mundial se consulta UNA vez por partida terminada;
--      el del torneo se pinta en la tarjeta de Home, en la pantalla del evento y
--      al acabar cada intento. Agregar en cada lectura sobre una tabla
--      particionada de alto volumen no escala.
--   2. REGLAS. El tope de intentos y el criterio de "mejor marca" son reglas DEL
--      EVENTO, y hay que aplicarlas en el momento de escribir (si no, un jugador
--      con 50 intentos compite contra uno con 3 sin que nada lo impida).
-- `user_progress` sigue siendo el log inmutable de la verdad (la partida del
-- torneo cuenta además para misión diaria, logros y percentil global);
-- `event_entries` es la proyección consultable de ese log.
-- =============================================================================

-- Para la restricción de no-solapamiento de más abajo: permite combinar en un
-- mismo índice GiST una igualdad (game_id) con un rango temporal.
create extension if not exists btree_gist;


-- -----------------------------------------------------------------------------
-- Cómo se ordena la tabla del torneo. ENUM y no texto libre porque el dominio es
-- cerrado y el cliente tiene que saber pintar cada modo (CLAUDE.md §4).
-- Hoy solo se usa 'best_run'; los otros dos existen para no tener que migrar el
-- tipo cuando entren (añadir un valor a un ENUM en uso es más caro que preverlo).
-- -----------------------------------------------------------------------------
do $$ begin
  create type event_scoring_mode as enum (
    'best_run',   -- cuenta el MEJOR intento (modo de la v1)
    'sum_top_n',  -- suma de los N mejores intentos (premia constancia)
    'total'       -- suma de TODOS los intentos (premia volumen)
  );
exception when duplicate_object then null; end $$;


-- =============================================================================
-- 1) EVENTS — el calendario de torneos
-- =============================================================================
create table public.events (
    id                uuid        primary key default gen_random_uuid(),
    -- Identificador legible y estable, el que se usa al sembrar y en soporte
    -- ("el torneo 'sudoku-2026-10-04'"). El cliente NO lo necesita: navega por id.
    slug              text        not null,
    game_id           uuid        not null
                        references public.games (id) on delete restrict,

    title             text        not null,
    subtitle          text,

    starts_at         timestamptz not null,
    ends_at           timestamptz not null,

    -- Dificultad FIJA del torneo, o null si el jugador la elige. No es un capricho:
    -- en los juegos que separan ranking por dificultad (`GameRankingScopes`) un
    -- torneo sin dificultad fija tendría el mismo defecto que allí se corrigió —
    -- ganaría quien juega en fácil.
    difficulty_level  smallint    check (difficulty_level between 1 and 5),

    -- false = gana la puntuación más alta; true = gana el tiempo más bajo. Mismo
    -- criterio (y mismo porqué) que `get_game_ranking(p_rank_by_time)` en 0029.
    rank_by_time      boolean     not null default false,
    scoring_mode      event_scoring_mode not null default 'best_run',

    -- null = intentos ilimitados. Con tablero fijo conviene topar: sin tope, la
    -- tabla la gana quien más veces repita el MISMO puzzle memorizado.
    attempts_limit    smallint    check (attempts_limit > 0),

    -- Reto concreto que todos juegan, en la forma que cada juego entienda: para
    -- Neon Sudoku Matrix `{"puzzle_id": "<uuid de sudoku_puzzles>"}`; para un juego
    -- procedural, `{"seed": 123456}`. JSONB y no columnas sueltas por el mismo
    -- motivo que `games.engine_config`: es un atributo del propio evento y evita
    -- una migración por cada juego que estrene su forma de fijar el reto.
    -- Objeto vacío = cada jugador recibe su propio reto (torneo "libre").
    payload           jsonb       not null default '{}'::jsonb,

    -- Recompensa. 0 = solo el puesto; N = los N primeros lucen insignia.
    -- NO se modela como fila de `achievements`: ese motor es de condición genérica
    -- (`condition_type` + `threshold`: partidas jugadas, racha, precisión...) y
    -- "quedar 3º en el torneo del 4/10" no se expresa ahí sin inventar un tipo de
    -- condición nuevo y su lógica en el cliente. El puesto final es derivable de
    -- `event_entries` una vez cerrado el evento (ya no admite escrituras), así que
    -- la insignia no necesita tabla propia ni proceso de cierre.
    reward_top_n      smallint    not null default 0 check (reward_top_n >= 0),
    reward_badge_key  text,

    -- Borrador por defecto: un evento se siembra, se revisa y SOLO entonces se
    -- publica. Sin esto, cualquier error de fechas al sembrar sale en producción
    -- en cuanto se hace commit.
    is_published      boolean     not null default false,

    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),

    constraint events_slug_unique unique (slug),
    constraint events_window_valid check (ends_at > starts_at)
);

-- Dos torneos publicados del MISMO juego a la vez partirían la audiencia y dejarían
-- al cliente sin respuesta para "el evento de este juego". La base lo impide, en
-- vez de confiar en que quien siembra se acuerde.
alter table public.events
    add constraint events_no_overlap_per_game
    exclude using gist (
        game_id with =,
        tstzrange(starts_at, ends_at) with &&
    ) where (is_published);

-- La consulta caliente es siempre la misma: "¿qué hay vivo o a punto de empezar?".
create index events_window_idx
    on public.events (starts_at, ends_at)
    where is_published;

comment on table  public.events is
    'Torneos programados sobre un juego concreto. Calendario de solo lectura para el cliente; se siembra por migración/service_role.';
comment on column public.events.payload is
    'Reto fijo del torneo en la forma de cada juego (puzzle_id, seed...). {} = reto libre por jugador.';
comment on column public.events.is_published is
    'Borrador hasta que se revisa. El cliente solo ve los publicados (RLS).';


-- =============================================================================
-- 2) EVENT_ENTRIES — la marca de cada jugador (= el leaderboard)
-- =============================================================================
create table public.event_entries (
    event_id        uuid        not null
                      references public.events (id) on delete cascade,
    user_id         uuid        not null
                      references public.users (id) on delete cascade,

    -- Mejor marca según el criterio del evento. Se guardan las dos porque la UI
    -- enseña ambas ("1.250 pts · 3:41") aunque solo una ordene la tabla.
    best_score      integer     not null default 0 check (best_score >= 0),
    best_time_ms    integer     check (best_time_ms >= 0),

    -- Intentos consumidos, contra `events.attempts_limit`. Vive aquí y no se
    -- cuenta sobre `user_progress` para que el chequeo del tope sea una lectura de
    -- una sola fila en el camino crítico de cada partida.
    attempts        smallint    not null default 0 check (attempts >= 0),

    first_played_at timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    primary key (event_id, user_id)
);

-- Leaderboard por puntos (mayor gana).
create index event_entries_score_idx
    on public.event_entries (event_id, best_score desc);

-- Leaderboard por tiempo (menor gana). Parcial: quien no tiene partida cronometrada
-- queda fuera de la tabla, igual que en `get_game_ranking` (0029).
create index event_entries_time_idx
    on public.event_entries (event_id, best_time_ms asc)
    where best_time_ms is not null;

comment on table public.event_entries is
    'Mejor marca por jugador y evento. Proyección consultable de user_progress; la escribe solo submit_event_result (SECURITY DEFINER).';


-- =============================================================================
-- 3) Enganche partida ↔ evento en el historial
-- -----------------------------------------------------------------------------
-- Columna propia y no reutilizar `daily_challenge_id`: son dos conceptos distintos
-- (y una partida podría, el día de mañana, contar para ambos). Añadir una columna
-- anulable a la tabla particionada es instantáneo: no reescribe filas.
-- =============================================================================
alter table public.user_progress
    add column if not exists event_id uuid
        references public.events (id) on delete set null;

-- Auditoría del torneo: todas las partidas de un evento, de la mejor a la peor.
-- Parcial porque la inmensa mayoría de las filas no pertenecen a ningún evento.
create index user_progress_event_idx
    on public.user_progress (event_id, score desc)
    where event_id is not null;

comment on column public.user_progress.event_id is
    'Evento al que contó esta partida, si la hubo. El leaderboard se lee de event_entries; esto es el log de respaldo.';


-- =============================================================================
-- 4) RLS
-- -----------------------------------------------------------------------------
-- `events` es un catálogo: lectura para autenticados, escritura solo service_role
-- (misma política que games/categories/daily_challenges en 0002).
-- `event_entries` NO se lee en crudo: el leaderboard sale por RPC SECURITY DEFINER
-- que proyecta solo `display_name` + marca, nunca `user_id` (misma decisión de
-- privacidad que `get_game_ranking`, 0027). Lo único que un usuario puede leer
-- directamente es SU propia fila: la necesita para saber cuántos intentos le
-- quedan sin depender de una llamada extra.
-- =============================================================================
alter table public.events        enable row level security;
alter table public.event_entries enable row level security;

create policy "events_read_published"
    on public.events for select
    to authenticated
    using (is_published);

create policy "event_entries_read_own"
    on public.event_entries for select
    to authenticated
    using (user_id = auth.uid());

-- Sin policies de INSERT/UPDATE/DELETE a propósito: escribir la marca es
-- competencia exclusiva de submit_event_result (0052), que valida ventana,
-- dificultad y tope de intentos. Una policy de insert aquí dejaría al cliente
-- escribir su puesto a mano.
