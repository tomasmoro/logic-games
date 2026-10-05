-- =============================================================================
-- Juegos premium (`GameInfo.premium` en el cliente).
-- -----------------------------------------------------------------------------
-- DECISIÓN DE PRODUCTO
-- Algunos juegos son premium: sin plan premium se juegan 5 partidas gratis al día
-- por juego y, agotadas, cada partida extra cuesta un anuncio recompensado (sin
-- tope). El plan premium las deja ilimitadas y sin anuncios.
--
-- POR QUÉ ES SOLO UNA COLUMNA ESPEJO
-- Igual que `is_new` (0043): hoy la app lee el flag de `GameCatalog.kt`, y esta
-- columna refleja el catálogo para poder verlo y ajustarlo desde el backend. El
-- cupo diario NO vive en la base de datos: lo cuenta el cliente en local
-- (`PlayQuotaManager`), con la misma confianza que ya asumen la pista, el revivir
-- y los intentos extra de torneo (0057). Validarlo en servidor rompería el modo
-- invitado offline, y lo que se arriesga (saltarse un anuncio) es poco.
--
-- Arranca en `false` y se enciende aquí para Neon Legion, Hexa Orbit y Quantum
-- Merge. Idempotente: aplicarla dos veces deja el mismo resultado.
-- =============================================================================

alter table public.games
    add column if not exists is_premium boolean not null default false;

comment on column public.games.is_premium is
    'Juego premium (espejo de GameInfo.premium en el cliente): sin plan premium, 5 partidas gratis al día y después una por anuncio recompensado. El cupo se cuenta en el cliente.';

update public.games
set is_premium = true,
    updated_at = now()
where slug in ('neon_legion', 'hexa_orbit', 'quantum_merge');
