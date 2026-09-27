-- =============================================================================
-- 0055 — El calendario de torneos también lo ve el invitado
-- -----------------------------------------------------------------------------
-- La decisión de producto es que el invitado VEA el torneo (cartel en Home,
-- cuenta atrás) y que la cuenta se le pida al ir a jugar: es el gancho de
-- conversión, y esconderle el evento lo desactiva. Con la policy de 0051
-- (`to authenticated`) el invitado no veía absolutamente nada, porque el modo
-- invitado de la app no tiene sesión de Supabase.
--
-- QUÉ SE EXPONE Y QUÉ NO
-- Se abre a `anon` **solo** `events`: título, fechas, juego y reglas de un torneo
-- ya publicado. Cero datos personales; es información de marketing.
-- NO se abre `event_entries` ni `get_event_leaderboard`, que llevan nombres de
-- jugadores: la tabla del torneo sigue exigiendo sesión, igual que el ranking
-- mundial (`get_game_ranking`). La clave `anon` es pública por definición, así
-- que todo lo que se le conceda es, en la práctica, internet entero.
--
-- Consecuencia en la UI: el invitado ve el cartel y la cuenta atrás, y en el
-- lugar de la tabla lee "inicia sesión para competir".
-- =============================================================================
drop policy "events_read_published" on public.events;

create policy "events_read_published"
    on public.events for select
    to anon, authenticated
    using (is_published);
