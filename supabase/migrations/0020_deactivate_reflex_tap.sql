-- =============================================================================
-- FASE 5 — Retira "Reflejos de Toque Rápido" (reflex_tap) del catálogo.
--
-- El juego ya no existe en GameCatalog.kt del cliente, pero su fila seguía en
-- `games`. No se puede borrar con DELETE porque hay progreso histórico que lo
-- referencia (user_progress, player_game_progress) vía FK: eliminarlo destruiría
-- datos reales de jugadores. Se hace un "soft remove" con is_active = false, que
-- lo oculta del catálogo activo conservando el histórico intacto.
-- Idempotente: re-aplicarlo no tiene efecto adicional.
-- =============================================================================

update public.games
set is_active = false,
    updated_at = now()
where id = '22222222-2222-4222-8222-222222222222';
