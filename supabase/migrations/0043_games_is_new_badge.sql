-- =============================================================================
-- FASE 6 — Insignia "NUEVO" en el catálogo (`GameInfo.isNew` en el cliente).
-- -----------------------------------------------------------------------------
-- Columna espejo del flag de UI: hoy la app solo lee `GameCatalog.kt` para pintar
-- el catálogo (esta tabla es referencial, no se consulta en cada carga — ver
-- CLAUDE.md §2), pero la novedad de un juego es contenido editorial que también
-- queremos poder ver/ajustar desde el backend sin pasar por una release, así que
-- se espeja aquí igual que el resto de metadatos del juego.
--
-- Es una decisión EDITORIAL, no una regla de caducidad por fecha: no hay trigger
-- que la apague sola a los N días. Se retira a mano (UPDATE) cuando el juego deja
-- de ser una novedad, igual que se activa a mano al publicar uno nuevo.
--
-- Arranca en `false` por defecto y se enciende aquí solo para los tres últimos
-- juegos incorporados al catálogo (Neon Legion, Hexa Orbit y Neon Grid Switch —
-- ver GameCatalog.kt), que es el estado que debe reflejar tras esta migración.
-- Idempotente: re-aplicarla dos veces deja el mismo resultado.
-- =============================================================================

alter table public.games
    add column if not exists is_new boolean not null default false;

comment on column public.games.is_new is
    'Insignia "NUEVO" del catálogo (contenido editorial, espejo de GameInfo.isNew en el cliente). Se enciende/apaga a mano; no caduca sola por fecha.';

update public.games
set is_new = true,
    updated_at = now()
where slug in ('neon_legion', 'hexa_orbit', 'neon_grid_switch');
