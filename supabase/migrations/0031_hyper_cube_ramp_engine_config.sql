-- =============================================================================
-- Ajuste: rampa de dificultad del Neon Hyper-Cube demasiado empinada.
--
-- La rampa original subía un giro de mezcla por nivel desde el nivel 1 (2
-- giros), así que el nivel 3 ya mezclaba con 4 giros — para quien no
-- visualiza el cubo de memoria eso ya se sentía "imposible" (feedback de
-- usuario), mucho antes de lo que sugiere el número.
--
-- La nueva rampa (`HyperCubeEngine.scrambleDepthFor` en el cliente, que sigue
-- siendo la fuente de verdad) empieza en 1 solo giro y cada profundidad se
-- juega un TRAMO DE NIVELES CADA VEZ MÁS LARGO antes de subir el listón
-- (`rampWidths`: 2 niveles a 1 giro, 2 a 2 giros, 4 a 3, 5 a 4, 6 a 5, 7 a 6,
-- 8 a 7 y 9 a 8 giros — el techo, bajado de 9 a 8 giros a propósito). Esta
-- migración solo actualiza el `engine_config` documental de la fila ya
-- sembrada (0026); no reescribe esa migración porque ya está aplicada (ver
-- CLAUDE.md §5).
-- =============================================================================

update public.games
set engine_config = '{"maxLevel": 43, "rampWidths": [2, 2, 4, 5, 6, 7, 8, 9], "scrambleDepthBase": 1, "freeScrambleDepth": 20, "levelPoints": 500, "efficiencyBonus": 600, "speedBonus": 300, "targetSecondsPerTurn": 8}'::jsonb
where id = '3d5c8a17-6b24-4e9f-9a80-1c7e5b2d4f63';
