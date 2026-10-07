-- =============================================================================
-- Neon Shikaku Matrix: curva de dificultad gradual.
--
-- La curva inicial (0061) saltaba de 5×5 a 10×10 en tres niveles: el nivel 3 ya
-- era un tablero de experto con figura en L. Probado en dispositivo, sube
-- demasiado rápido. La nueva curva crece una cosa cada vez (primero el tamaño
-- sobre rejilla completa, después los recortes) y deja el 10×10 con figura rota
-- para el nivel 10 — ver `ShikakuLevelGenerator.configFor` en el cliente, que es
-- la fuente de verdad; `engine_config` es su espejo informativo.
--
-- Solo cambia `engine_config` de esa fila. No afecta a partidas guardadas: el
-- progreso se registra por número de nivel, no por tablero.
--
-- Archivo nuevo (no se edita la 0061, ya aplicada) e idempotente.
-- =============================================================================

update public.games
set engine_config = '{"tiers": [{"fromLevel": 1, "side": 5, "maxArea": 5}, {"fromLevel": 3, "side": 6, "maxArea": 6}, {"fromLevel": 5, "side": 7, "maxArea": 8}, {"fromLevel": 7, "side": 8, "maxArea": 10}, {"fromLevel": 9, "side": 9, "maxArea": 12}, {"fromLevel": 10, "side": 10, "maxArea": 14}, {"fromLevel": 16, "side": 11, "maxArea": 17}, {"fromLevel": 22, "side": 12, "maxArea": 20}], "maxSide": 12, "maxArea": 24, "targetMsPerCell": 2500, "restartPenalty": 60}'::jsonb,
    updated_at = now()
where slug = 'neon_shikaku';
